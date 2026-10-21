package com.ramphoenix.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.util.Collections;

/**
 * Opens one Camera2 device onto a single {@link Surface} and keeps a repeating
 * preview running. On device error it re-opens with a short backoff INSTEAD of
 * tearing down the whole app the way the stock com.syu.doublecamera does
 * (restartAppMin / waitRestartBackErrorTime) — that teardown is the black-flash
 * lag Beau sees entering reverse.
 *
 * One instance drives one camera. For 360 we run two instances (dev 14 + dev 10).
 */
public class CameraController {

    private static final String TAG = "PhoenixCam";

    private final Context ctx;
    private final CameraManager manager;
    private HandlerThread thread;
    private Handler bg;

    private String cameraId;
    private Surface outputSurface;
    private CameraDevice device;
    private CameraCaptureSession session;
    private volatile boolean wantOpen;
    private int reopenDelayMs = 250;

    public CameraController(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.manager = (CameraManager) this.ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    /** Begin (or switch to) showing {@code id} on {@code surface}. Safe to call repeatedly. */
    public synchronized void open(String id, Surface surface) {
        this.cameraId = id;
        this.outputSurface = surface;
        this.wantOpen = true;
        this.reopenDelayMs = 250;
        ensureThread();
        bg.post(this::doOpen);
    }

    public synchronized void close() {
        this.wantOpen = false;
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

    @SuppressWarnings("MissingPermission")
    private void doOpen() {
        if (!wantOpen || outputSurface == null || cameraId == null) return;
        doClose();
        try {
            manager.openCamera(cameraId, stateCb, bg);
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG, "openCamera(" + cameraId + ") failed: " + e.getMessage());
            scheduleReopen();
        }
    }

    private void scheduleReopen() {
        if (!wantOpen || bg == null) return;
        bg.postDelayed(this::doOpen, reopenDelayMs);
        // gentle exponential backoff, capped — never a hard app restart
        reopenDelayMs = Math.min(reopenDelayMs * 2, 2000);
    }

    private final CameraDevice.StateCallback stateCb = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice cam) {
            device = cam;
            reopenDelayMs = 250; // healthy open resets backoff
            startSession();
        }
        @Override public void onDisconnected(CameraDevice cam) {
            Log.w(TAG, "cam " + cameraId + " disconnected");
            cam.close();
            if (device == cam) device = null;
            scheduleReopen();
        }
        @Override public void onError(CameraDevice cam, int error) {
            Log.w(TAG, "cam " + cameraId + " error " + error + " -> reopen (no app restart)");
            cam.close();
            if (device == cam) device = null;
            scheduleReopen();
        }
    };

    @SuppressWarnings("deprecation")
    private void startSession() {
        try {
            final CaptureRequest.Builder req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(outputSurface);
            device.createCaptureSession(Collections.singletonList(outputSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        try {
                            s.setRepeatingRequest(req.build(), null, bg);
                        } catch (Exception e) {
                            Log.e(TAG, "repeating req failed: " + e.getMessage());
                            scheduleReopen();
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                        Log.e(TAG, "session config failed for cam " + cameraId);
                        scheduleReopen();
                    }
                }, bg);
        } catch (Exception e) {
            Log.e(TAG, "startSession failed: " + e.getMessage());
            scheduleReopen();
        }
    }
}
