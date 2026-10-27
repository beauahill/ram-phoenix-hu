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
    /** HIGH-importance channel: only for the full-screen-intent fallback. */
    private static final String CH_ID = "phoenix_reverse";
    /** MIN-importance channel for the permanent "watching" notification (review
     *  C3): keeps it out of the heads-up slot on every boot / service restart. */
    private static final String CH_ONGOING_ID = "phoenix_reverse_watch";
    private static final int FG_ID = 1;
    private static final int FULLSCREEN_ID = 2;
    /** How long we give the activity to resume before declaring the start blocked. */
    private static final long VERIFY_MS = 700;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ReverseMonitor monitor;
    private static volatile ReverseService sInstance;
    private volatile boolean reverseEngaged;
    private volatile long reverseSession;
    private Runnable verifyLaunch;
    private PendingIntent pendingShow;

    /** Reject SHOW intents left over from an earlier shift or service instance. */
    public static boolean isReverseSessionCurrent(long session) {
        ReverseService s = sInstance;
        return s != null && s.reverseEngaged && session == s.reverseSession;
    }

    /** Send a raw ToolkitService command through the service's IPC. False if the service
     *  or its IPC is down. Used by MainActivity to forward taps on the factory OSD. */
    public static boolean ipcCmd(int module, int code, int[] ints) {
        ReverseService s = sInstance;
        return s != null && s.monitor != null && s.monitor.cmd(module, code, ints);
    }

    /** adb probe (debug builds only):
     *  am broadcast -a com.ramphoenix.camera.IPC_CMD --ei module 0 --ei code 56 --eia ints 0,400,240 */
    public static final String ACTION_IPC_CMD = "com.ramphoenix.camera.IPC_CMD";
    private final android.content.BroadcastReceiver debugRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            int module = i.getIntExtra("module", 0), code = i.getIntExtra("code", -1);
            int[] ints = i.getIntArrayExtra("ints");
            boolean ok = code >= 0 && ipcCmd(module, code, ints);
            Log.i(TAG, "debug cmd module=" + module + " code=" + code + " ints=" + java.util.Arrays.toString(ints)
                    + " -> " + (ok ? "sent" : "FAILED"));
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        startForeground(FG_ID, buildOngoing());
        logLaunchCapability();
        if (debuggable()) {
            registerReceiver(debugRx, new android.content.IntentFilter(ACTION_IPC_CMD),
                    android.content.Context.RECEIVER_EXPORTED);
        }
        monitor = new ReverseMonitor(this, this::onReverseChanged);
        monitor.start();
        // Review C2: the monitor only fires on a CHANGE, so if the unit boots (or
        // this service restarts) with the truck already in R — backing out of
        // the garage as it boots — nothing would ever call showCamera(). Mirror
        // the stock app's GET_BOOT_REVERSE_STATUS by acting on the seed state.
        if (monitor.isEngaged()) {
            Log.i(TAG, "reverse already engaged at service start -> showing camera");
            onReverseChanged(true);
        }
    }

    private void onReverseChanged(boolean engaged) {
        Log.i(TAG, "service reverse -> " + engaged);
        if (engaged && !reverseEngaged) reverseSession = android.os.SystemClock.elapsedRealtimeNanos();
        reverseEngaged = engaged;
        if (engaged) showCamera();
        else {
            cancelPendingShow();
            MainActivity.requestHide();
        }
    }

    private void cancelPendingShow() {
        if (verifyLaunch != null) { main.removeCallbacks(verifyLaunch); verifyLaunch = null; }
        if (pendingShow != null) { pendingShow.cancel(); pendingShow = null; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(FULLSCREEN_ID);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // survive the SYU service churn
    }

    private boolean debuggable() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
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
        cancelPendingShow();
        final long session = reverseSession;
        final Intent show = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_SHOW)
                .putExtra(MainActivity.EXTRA_REVERSE, true)
                .putExtra(MainActivity.EXTRA_REVERSE_SESSION, session)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);

        if (MainActivity.isVisible()) {
            // Already in front: switch it directly, no intent round-trip.
            MainActivity.requestShowRear(session);
            return;
        }

        // Path 1: direct start. Works when SYSTEM_ALERT_WINDOW is granted or we are
        // priv-app. Silently dropped otherwise, so verify below.
        try { startActivity(show); }
        catch (RuntimeException e) { Log.e(TAG, "camera start failed", e); }
        verifyLaunch = () -> {
            verifyLaunch = null;
            if (!isReverseSessionCurrent(session) || MainActivity.isVisible()) return;
            Log.e(TAG, "camera did not come to front within " + VERIFY_MS
                    + "ms: background start blocked by ActivityTaskManager.");
            logLaunchCapability();
            // Path 2: full-screen intent. Only launches while screen off / keyguard
            // showing; otherwise a heads-up notification. Best effort.
            postFullScreenIntent(show);
        };
        main.postDelayed(verifyLaunch, VERIFY_MS);
    }

    private void postFullScreenIntent(Intent show) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        pendingShow = pi;
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

            NotificationChannel watch = new NotificationChannel(
                    CH_ONGOING_ID, "Reverse watcher", NotificationManager.IMPORTANCE_MIN);
            watch.setDescription("Background service that watches the reverse gear");
            watch.setShowBadge(false);
            watch.setSound(null, null);
            watch.enableVibration(false);
            nm.createNotificationChannel(watch);
        }
        return new Notification.Builder(this, CH_ONGOING_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Phoenix camera ready")
                .setContentText("Watching for reverse gear")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        reverseEngaged = false;
        cancelPendingShow();
        sInstance = null;
        if (debuggable()) { try { unregisterReceiver(debugRx); } catch (Exception ignored) {} }
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
