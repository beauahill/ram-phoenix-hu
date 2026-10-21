package com.ramphoenix.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.provider.Settings;

/**
 * Launch an app into a rectangle of the screen (one SPLIT pane). Uses the
 * public {@link ActivityOptions#setLaunchBounds} API, which Android honours
 * only when freeform windows are enabled on the device:
 *
 *   adb shell settings put global enable_freeform_support 1
 *   adb shell settings put global force_resizable_activities 1   # apps that aren't resizable
 *   adb reboot
 *
 * Without that the bounds are ignored and the app opens full-screen, which is
 * still a sane fallback. {@link #freeformEnabled} lets the UI say which happened.
 */
final class AppLaunch {
    private AppLaunch() {}

    static boolean freeformEnabled(Context ctx) {
        if (ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) return true;
        return Settings.Global.getInt(ctx.getContentResolver(), "enable_freeform_support", 0) == 1;
    }

    static void inBounds(Activity act, Intent intent, Rect boundsPx) {
        Intent i = new Intent(intent);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchBounds(boundsPx);
        act.startActivity(i, o.toBundle());
    }
}
