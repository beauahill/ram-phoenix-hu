package com.ramphoenix.camera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * The real Milestone-1 trigger. A foreground service that polls the reverse
 * flag independently of whatever is on screen, and brings up {@link MainActivity}
 * the instant reverse engages — even from the launcher or nav.
 *
 * Background activity starts are restricted on Android 10+. On a head unit the
 * only routes that work are:
 *   1. SYSTEM_ALERT_WINDOW granted via appops (what the install steps do), or
 *   2. installing as priv-app with START_ACTIVITIES_FROM_BACKGROUND.
 * A blocked start does NOT throw: ActivityTaskManager logs "Background activity
 * start ... blocked" and drops it silently. So after starting we verify that
 * MainActivity actually resumed, and if it did not we log exactly what is
 * missing and post a full-screen-intent notification as a best-effort fallback.
 * That fallback only launches an activity while the screen is off or the
 * keyguard is showing (otherwise Android shows a heads-up instead), so it covers
 * reverse-at-boot with a locked screen, not the normal driving case.
 *
 * Started from {@link ReverseReceiver} at BOOT_COMPLETED and from MainActivity.
 */
public class ReverseService extends Service {

    private static final String TAG = "PhoenixCam";
    private static final String CH_ID = "phoenix_reverse";
    private static final int FG_ID = 1;
    private static final int FULLSCREEN_ID = 2;
    /** How long we give the activity to resume before declaring the start blocked. */
    private static final long VERIFY_MS = 700;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ReverseMonitor monitor;

    @Override public void onCreate() {
        super.onCreate();
        startForeground(FG_ID, buildOngoing());
        logLaunchCapability();
        monitor = new ReverseMonitor(engaged -> {
            Log.i(TAG, "service reverse -> " + engaged);
            if (engaged) {
                showCamera();
            } else if (MainActivity.isVisible()) {
                // tell the activity it can step back if it came up for reverse
                Intent hide = new Intent(this, MainActivity.class)
                        .setAction(MainActivity.ACTION_HIDE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(hide);
            }
        });
        monitor.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // survive the SYU service churn
    }

    /** True when Android will let this app start an activity from the background. */
    private boolean canLaunchFromBackground() {
        return Settings.canDrawOverlays(this);
    }

    private void logLaunchCapability() {
        if (canLaunchFromBackground()) {
            Log.i(TAG, "background launch OK (SYSTEM_ALERT_WINDOW granted)");
        } else {
            Log.w(TAG, "background launch NOT granted: reverse will not bring the camera up "
                    + "from another screen. Fix: adb shell appops set " + getPackageName()
                    + " SYSTEM_ALERT_WINDOW allow   (or install as priv-app)");
        }
    }

    private void showCamera() {
        final Intent show = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_SHOW)
                .putExtra(MainActivity.EXTRA_REVERSE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        if (MainActivity.isVisible()) {
            // Already in front: just deliver the intent so it switches to REAR.
            startActivity(show);
            return;
        }

        // Path 1: direct start. Works when SYSTEM_ALERT_WINDOW is granted or we are
        // priv-app. Silently dropped otherwise, so verify below.
        startActivity(show);
        main.postDelayed(() -> {
            if (MainActivity.isVisible()) return;
            Log.e(TAG, "camera did not come to front within " + VERIFY_MS
                    + "ms: background start blocked by ActivityTaskManager.");
            logLaunchCapability();
            // Path 2: full-screen intent. Only launches while screen off / keyguard
            // showing; otherwise a heads-up notification. Best effort.
            postFullScreenIntent(show);
        }, VERIFY_MS);
    }

    private void postFullScreenIntent(Intent show) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Reverse camera")
                .setContentText("Tap to open (grant overlay permission for automatic launch)")
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_CALL)
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
        main.removeCallbacksAndMessages(null);
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
