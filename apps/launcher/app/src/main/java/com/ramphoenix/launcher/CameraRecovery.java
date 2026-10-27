package com.ramphoenix.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Vendor sleep cleanup force-stops third-party apps without a new Android boot.
 * Restore only Phoenix's monitor; it decides whether reverse requires a picture. */
final class CameraRecovery {
    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean visible;
    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            ensureMonitor();
            ui.postDelayed(this, 15000);
        }
    };
    CameraRecovery(Context context) { this.context = context.getApplicationContext(); }
    void visible(boolean value) {
        visible = value;
        ui.removeCallbacks(pulse);
        if (value) ui.post(pulse);
    }
    void ensureMonitor() {
        try {
            // Signature permission on the camera service restricts this bridge
            // to apps signed with Phoenix's own key. No camera activity is opened.
            ComponentName service = context.startForegroundService(new Intent().setComponent(
                    new ComponentName("com.ramphoenix.camera", "com.ramphoenix.camera.ReverseService")));
            if (service == null) Log.w("PhoenixCameraRecovery", "Phoenix Camera is not installed or enabled");
        } catch (RuntimeException unavailable) {
            Log.w("PhoenixCameraRecovery", "Could not restore reverse monitor", unavailable);
        }
    }
}
