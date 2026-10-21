package com.ramphoenix.camera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * The real Milestone-1 trigger. A foreground service that polls the reverse
 * flag independently of whatever is on screen, and brings up {@link MainActivity}
 * the instant reverse engages — even from the launcher or nav. This is the gap
 * the review flagged: MainActivity's own monitor only ran while already visible.
 *
 * Launching an activity from the background is restricted on Android 10+. We use
 * a full-screen-intent notification (the same mechanism an incoming-call screen
 * uses) which is the sanctioned path and does not require SYSTEM_ALERT_WINDOW.
 * We ALSO try a direct startActivity, which succeeds on this userdebug unit if
 * the app is installed priv-app or granted SYSTEM_ALERT_WINDOW via appops. Either
 * route needs a live-truck confirm (see docs/CAMERA-MILESTONE-1.md).
 *
 * Started from {@link ReverseReceiver} at BOOT_COMPLETED and from MainActivity.
 */
public class ReverseService extends Service {

    private static final String TAG = "PhoenixCam";
    private static final String CH_ID = "phoenix_reverse";
    private static final int FG_ID = 1;
    private static final int FULLSCREEN_ID = 2;

    private ReverseMonitor monitor;

    @Override public void onCreate() {
        super.onCreate();
        startForeground(FG_ID, buildOngoing());
        monitor = new ReverseMonitor(engaged -> {
            Log.i(TAG, "service reverse -> " + engaged);
            if (engaged) {
                showCamera();
            } else {
                // tell the activity it can step back if it came up for reverse
                Intent hide = new Intent(this, MainActivity.class)
                        .setAction(MainActivity.ACTION_HIDE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(hide); } catch (Exception ignored) {}
            }
        });
        monitor.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // survive the SYU service churn
    }

    private void showCamera() {
        Intent show = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_SHOW)
                .putExtra(MainActivity.EXTRA_REVERSE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        // Path 1: direct start (works if priv-app / SYSTEM_ALERT_WINDOW granted).
        try { startActivity(show); } catch (Exception e) {
            Log.w(TAG, "direct start blocked: " + e.getMessage());
        }

        // Path 2: full-screen intent — the sanctioned background-launch route.
        PendingIntent pi = PendingIntent.getActivity(this, 0, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Reverse camera")
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(Notification.PRIORITY_MAX)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .setOngoing(false)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(FULLSCREEN_ID, n);
    }

    private Notification buildOngoing() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "Reverse camera", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Brings up the rear camera when you shift to reverse");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Phoenix camera ready")
                .setContentText("Watching for reverse gear")
                .setOngoing(true)
                .build();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        if (monitor != null) monitor.stop();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Helper: start this service (idempotent). */
    public static void ensureRunning(Context ctx) {
        Intent i = new Intent(ctx, ReverseService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }
}
