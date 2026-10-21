package com.ramphoenix.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.Surface;

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
 * Review fixes (2026-10-21):
 *  - #1 stream size: before opening we read the HAL's advertised output sizes and
 *    call holder.setFixedSize(w,h) with a real 16:9 size, so the surface no longer
 *    asks the analog HAL for the raw 1080x1920 portrait buffer (onConfigureFailed).
 *  - #3 stale-open guard: every open() bumps a generation token; late callbacks
 *    from a superseded open are closed and ignored so two devices can't both win.
 */
public class CameraController {

    private static final String TAG = "PhoenixCam";

    private final Context ctx;
    private final CameraManager manager;
    private HandlerThread thread;
    private Handler bg;

    private String cameraId;
    private SurfaceHolder holder;
    private Surface outputSurface;
    private CameraDevice device;
    private CameraCaptureSession session;
    private volatile boolean wantOpen;
    private int reopenDelayMs = 250;
    /** Bumped on every open()/switch; callbacks older than this are stale. */
    private volatile int generation = 0;

    public CameraController(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.manager = (CameraManager) this.ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    /** Begin (or switch to) showing {@code id} on {@code holder}. Safe to call repeatedly. */
    public synchronized void open(String id, SurfaceHolder holder) {
        this.cameraId = id;
        this.holder = holder;
        this.outputSurface = holder.getSurface();
        this.wantOpen = true;
        this.reopenDelayMs = 250;
        this.generation++;
        ensureThread();
        final int gen = generation;
        bg.post(() -> doOpen(gen));
    }

    public synchronized void close() {
        this.wantOpen = false;
        this.generation++;
        if (bg != null) bg.post(this::doClose);
    }

    public synchronized void shutdown() {
        close();
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            bg = null;
        }
    }

    private void ensureThread() {
        if (thread == null) {
            thread = new HandlerThread("cam-" + cameraId);
            thread.start();
            bg = new Handler(thread.getLooper());
        }
    }

    private void doClose() {
        try {
            if (session != null) { session.close(); session = null; }
            if (device != null) { device.close(); device = null; }
        } catch (Exception e) {
            Log.w(TAG, "close err: " + e.getMessage());
        }
    }

    /**
     * Pick a sane 16:9 output size the HAL actually advertises and pin the
     * surface to it. Analog decoders typically expose 1280x720 / 1920x1080;
     * we prefer 1280x720 (lower latency, plenty for a reverse cam) and fall
     * back to the largest advertised size, then to HwConfig defaults.
     */
    private void applyFixedSize() {
        int w = HwConfig.CAP_WIDTH, h = HwConfig.CAP_HEIGHT;
        try {
            CameraCharacteristics cc = manager.getCameraCharacteristics(cameraId);
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
                        // largest advertised
                        for (Size s : sizes) {
                            if (pick == null || (long) s.getWidth() * s.getHeight()
                                    > (long) pick.getWidth() * pick.getHeight()) pick = s;
                        }
                    }
                    if (pick != null) { w = pick.getWidth(); h = pick.getHeight(); }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "size query failed for " + cameraId + ", using defaults: " + t.getMessage());
        }
        Log.i(TAG, "cam " + cameraId + " fixed size " + w + "x" + h);
        if (holder != null) holder.setFixedSize(w, h);
    }

    @SuppressWarnings("MissingPermission")
    private void doOpen(int gen) {
        if (gen != generation || !wantOpen || outputSurface == null || cameraId == null) return;
        doClose();
        try {
            applyFixedSize();
            manager.openCamera(cameraId, newStateCb(gen), bg);
        } catch (CameraAccessException | IllegalArgumentException e) {
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
            @Override public void onOpened(CameraDevice cam) {
                if (gen != generation || !wantOpen) { cam.close(); return; } // stale open loses
                device = cam;
                reopenDelayMs = 250; // healthy open resets backoff
                startSession(gen);
            }
            @Override public void onDisconnected(CameraDevice cam) {
                Log.w(TAG, "cam " + cameraId + " disconnected");
                cam.close();
                if (device == cam) device = null;
                scheduleReopen(gen);
            }
            @Override public void onError(CameraDevice cam, int error) {
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
            final CaptureRequest.Builder req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(outputSurface);
            device.createCaptureSession(Collections.singletonList(outputSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        if (gen != generation || !wantOpen) { s.close(); return; }
                        session = s;
                        try {
                            s.setRepeatingRequest(req.build(), null, bg);
                        } catch (Exception e) {
                            Log.e(TAG, "repeating req failed: " + e.getMessage());
                            scheduleReopen(gen);
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
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
