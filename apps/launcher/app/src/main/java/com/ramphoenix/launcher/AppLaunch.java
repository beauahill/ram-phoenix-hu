package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.provider.Settings;
import android.widget.Toast;

/** Shared launch path: controlled app tasks, explicit full-screen fallback, no duplicates. */
final class AppLaunch {
    private AppLaunch() {}

    static boolean freeformEnabled(Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
                || Settings.Global.getInt(ctx.getContentResolver(), "enable_freeform_support", 0) == 1;
    }

    private static Intent explicit(Context context, Intent original) {
        Intent intent = new Intent(original);
        if (intent.getComponent() == null) intent.setComponent(intent.resolveActivity(context.getPackageManager()));
        if (intent.getComponent() == null) throw new android.content.ActivityNotFoundException();
        intent.setSelector(null);
        intent.setFlags(intent.getFlags() & ~(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        return intent;
    }

    private static boolean hostable(Context context, Intent intent) {
        String pkg = intent.getComponent().getPackageName();
        if (pkg.equals(context.getPackageName()) || pkg.equals("com.ramphoenix.camera")) return false;
        try {
            int mode = context.getPackageManager().getActivityInfo(intent.getComponent(), 0).launchMode;
            return mode == ActivityInfo.LAUNCH_MULTIPLE || mode == ActivityInfo.LAUNCH_SINGLE_TOP;
        } catch (PackageManager.NameNotFoundException missing) { return false; }
    }

    static void fullscreen(Activity activity, Intent original) {
        if (PaneWindows.reversing()) return;
        Intent intent = explicit(activity, original);
        PaneWindows.closeAll(activity);
        OemBars.keepHidden(activity);
        WindowBridge.get(activity).afterClose(() -> launchFullscreen(activity,intent));
    }

    private static void launchFullscreen(Activity activity,Intent intent) {
        if (activity.isDestroyed() || PaneWindows.reversing()) return;
        if (hostable(activity, intent)) {
            PaneWindows.open(activity, "external", intent, null);
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            activity.startActivity(intent, PaneWindows.options(null).toBundle());
        }
    }

    static void inPane(Activity activity, String key, Intent original, Rect frame) {
        if (PaneWindows.reversing()) return;
        Intent intent = explicit(activity, original);
        if (!freeformEnabled(activity) || intent.getComponent().getPackageName().startsWith("com.ramphoenix.")) {
            fullscreen(activity, intent);
            Toast.makeText(activity, "This app opens full screen", Toast.LENGTH_SHORT).show();
            return;
        }
        PaneWindows.open(activity, key, intent, frame);
    }
}
