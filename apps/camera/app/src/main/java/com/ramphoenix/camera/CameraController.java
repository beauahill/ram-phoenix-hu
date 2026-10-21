package com.ramphoenix.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import java.util.Collections;

/**
 * Opens one Camera2 device onto a single {@link SurfaceHolder} and keeps a
 * repeating preview running. On device error it re-opens with a short backoff
 * INSTEAD of tearing down the whole app the way the stock com.syu.doublecamera
 * does (restartAppMin / waitRestartBackErrorTime) — that teardown is the
 * black-flash lag Beau sees entering reverse.
 *
 * One instance drives one camera. For 360 we run two instances (dev 14 + dev 10).
 *
 * Surface sizing (review fix #1, corrected 2026-10-21):
 *   The surface must be pinned to a size the HAL actually advertises, or the
 *   session asks the analog HAL for a 1080x1920 portrait buffer and fails.
 *   {@code SurfaceHolder.setFixedSize()} triggers a relayout, which Android only
 *   allows from the UI thread, so the whole negotiation runs on the main thread:
 *   {@link #open} picks the size, requests it, and the device is opened only
 *   after {@code surfaceChanged} confirms the surface now has that size.
 *
 * Stale-open guard (review fix #3): every open()/close() bumps a generation
 * token; callbacks from a superseded open close their device and bow out.
 */
public class CameraController {

    private static final String TAG = "PhoenixCam";

    private final Context ctx;
    private final CameraManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;

    private String cameraId;
    private Surface outputSurface;
    private CameraDevice device;
    private CameraCaptureSession session;
    private volatile boolean wantOpen;
    private int reopenDelayMs = 250;
    /** Bumped on every open()/close(); callbacks older than this are stale. */
    private volatile int generation = 0;

    // --- main-thread-only size negotiation state ---
    private SurfaceHolder attachedHolder;
    private Size pendingSize;
    private int pendingGen;

    public CameraController(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.manager = (CameraManager) this.ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    /**
     * Begin (or switch to) showing {@code id} on {@code holder}. Safe to call
     * repeatedly. Runs on the main thread (re-posts itself if called elsewhere)
     * because it may resize the SurfaceView.
     */
    public void open(final String id, final SurfaceHolder holder) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> open(id, holder));
            return;
        }
        final int gen;
        synchronized (this) {
            cameraId = id;
            outputSurface = holder.getSurface();
            wantOpen = true;
            reopenDelayMs = 250;
            generation++;
            gen = generation;
            ensureThread();
        }
        attachHolder(holder);

        final Size want = pickSize(id);
        final Rect frame = holder.getSurfaceFrame();
        if (frame.width() == want.getWidth() && frame.height() == want.getHeight()) {
            pendingSize = null;
            bg.post(() -> doOpen(gen));
        } else {
            // Ask for the size; surfaceChanged() below completes the open once
            // the surface really is that size. UI thread only.
            pendingSize = want;
            pendingGen = gen;
            Log.i(TAG, "cam " + id + ": surface " + frame.width() + "x" + frame.height()
                    + " -> requesting fixed size " + want);
            holder.setFixedSize(want.getWidth(), want.getHeight());
        }
    }

    public synchronized void close() {
        this.wantOpen = false;
        this.generation++;
        if (bg != null) bg.post(this::doClose);
    }

    /** Main thread. */
    public void shutdown() {
        close();
        if (attachedHolder != null) {
            attachedHolder.removeCallback(holderCb);
            attachedHolder = null;
        }
        pendingSize = null;
        synchronized (this) {
            if (thread != null) {
                thread.quitSafely();
                thread = null;
                bg = null;
            }
        }
    }

    private void ensureThread() {
        if (thread == null) {
            thread = new HandlerThread("cam-" + cameraId);
            thread.start();
            bg = new Handler(thread.getLooper());
        }
    }

    private void attachHolder(SurfaceHolder h) {
        if (attachedHolder == h) return;
        if (attachedHolder != null) attachedHolder.removeCallback(holderCb);
        attachedHolder = h;
        h.addCallback(holderCb);
    }

    /** Completes a pending open once the surface has taken the requested size. */
    private final SurfaceHolder.Callback holderCb = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(@NonNull SurfaceHolder h) {}
        @Override public void surfaceChanged(@NonNull SurfaceHolder h, int fmt, int w, int ht) {
            if (pendingSize == null) return;
            if (w != pendingSize.getWidth() || ht != pendingSize.getHeight()) return;
            final int gen = pendingGen;
            pendingSize = null;
            if (!wantOpen || gen != generation) return; // superseded meanwhile
            synchronized (CameraController.this) { outputSurface = h.getSurface(); }
            Log.i(TAG, "cam " + cameraId + ": surface now " + w + "x" + ht + ", opening");
            if (bg != null) bg.post(() -> doOpen(gen));
        }
        @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) { pendingSize = null; }
    };

    /**
     * Pick a sane 16:9 output size the HAL actually advertises. Analog decoders
     * typically expose 1280x720 / 1920x1080; prefer 1280x720 (lower latency,
     * plenty for a reverse cam), then the largest advertised, then HwConfig
     * defaults. Characteristics lookup is a quick binder call; fine on main.
     */
    private Size pickSize(String id) {
        int w = HwConfig.CAP_WIDTH, h = HwConfig.CAP_HEIGHT;
        try {
            CameraCharacteristics cc = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(SurfaceHolder.class);
                if (sizes == null || sizes.length == 0) {
                    sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
                }
                if (sizes != null && sizes.length > 0) {
                    Size pick = null;
                    for (Size s : sizes) {
                        if (s.getWidth() == HwConfig.CAP_WIDTH && s.getHeight() == HwConfig.CAP_HEIGHT) {
                            pick = s; break;
                        }
                    }
                    if (pick == null) {
                        for (Size s : sizes) {
                            if (pick == null || (long) s.getWidth() * s.getHeight()
                                    > (long) pick.getWidth() * pick.getHeight()) pick = s;
                        }
                    }
                    if (pick != null) { w = pick.getWidth(); h = pick.getHeight(); }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "size query failed for " + id + ", using defaults: " + t.getMessage());
        }
        Log.i(TAG, "cam " + id + " stream size " + w + "x" + h);
        return new Size(w, h);
    }

    private void doClose() {
        try {
            if (session != null) { session.close(); session = null; }
            if (device != null) { device.close(); device = null; }
        } catch (Exception e) {
            Log.w(TAG, "close err: " + e.getMessage());
        }
    }

    @SuppressWarnings("MissingPermission")
    private void doOpen(int gen) {
        if (gen != generation || !wantOpen || outputSurface == null || cameraId == null) return;
        doClose();
        try {
            manager.openCamera(cameraId, newStateCb(gen), bg);
        } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "openCamera(" + cameraId + ") failed: " + e.getMessage());
            scheduleReopen(gen);
        }
    }

    private void scheduleReopen(int gen) {
        if (gen != generation || !wantOpen || bg == null) return;
        bg.postDelayed(() -> doOpen(gen), reopenDelayMs);
        // gentle exponential backoff, capped — never a hard app restart
        reopenDelayMs = Math.min(reopenDelayMs * 2, 2000);
    }

    /** Fresh callback per open, bound to that open's generation, so a late
     *  callback from a superseded open closes its device and bows out. */
    private CameraDevice.StateCallback newStateCb(final int gen) {
        return new CameraDevice.StateCallback() {
            @Override public void onOpened(@NonNull CameraDevice cam) {
                if (gen != generation || !wantOpen) { cam.close(); return; } // stale open loses
                device = cam;
                reopenDelayMs = 250; // healthy open resets backoff
                startSession(gen);
            }
            @Override public void onDisconnected(@NonNull CameraDevice cam) {
                Log.w(TAG, "cam " + cameraId + " disconnected (another client took it?)");
                cam.close();
                if (device == cam) device = null;
                scheduleReopen(gen);
            }
            @Override public void onError(@NonNull CameraDevice cam, int error) {
                Log.w(TAG, "cam " + cameraId + " error " + error + " -> reopen (no app restart)");
                cam.close();
                if (device == cam) device = null;
                scheduleReopen(gen);
            }
        };
    }

    @SuppressWarnings("deprecation")
    private void startSession(final int gen) {
        try {
            final Surface target = outputSurface;
            final CaptureRequest.Builder req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(target);
            device.createCaptureSession(Collections.singletonList(target),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession s) {
                        if (gen != generation || !wantOpen) { s.close(); return; }
                        session = s;
                        try {
                            s.setRepeatingRequest(req.build(), null, bg);
                        } catch (Exception e) {
                            Log.e(TAG, "repeating req failed: " + e.getMessage());
                            scheduleReopen(gen);
                        }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                        Log.e(TAG, "session config failed for cam " + cameraId);
                        scheduleReopen(gen);
                    }
                }, bg);
        } catch (Exception e) {
            Log.e(TAG, "startSession failed: " + e.getMessage());
            scheduleReopen(gen);
        }
    }
}
