package com.ramphoenix.launcher;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Own only our app-window roots. Generation checks retire queued/stale launches. */
final class PaneWindows {
    private static final String TAG = "PhoenixLauncher";
    private static final String[] KEYS = {"pane_a", "pane_b", "external"};
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static boolean reverse;
    private PaneWindows() {}

    static Class<?> root(String key) {
        switch (key) {
            case "pane_a": return PaneWindowA.class;
            case "pane_b": return PaneWindowB.class;
            case "external": return ExternalWindowActivity.class;
            default: throw new IllegalArgumentException("Unknown app window");
        }
    }

    private static SharedPreferences state(Context context) {
        return context.getSharedPreferences("app_windows", Context.MODE_PRIVATE);
    }

    static ActivityManager.AppTask find(Context context, String key) {
        ComponentName root = new ComponentName(context, root(key));
        for (ActivityManager.AppTask task : context.getSystemService(ActivityManager.class).getAppTasks()) {
            try {
                if (root.equals(task.getTaskInfo().baseIntent.getComponent())) return task;
            } catch (IllegalArgumentException retired) { /* Task ended during enumeration. */ }
        }
        return null;
    }

    static boolean wanted(Context context, String key) {
        if (!key.equals("external")) return WindowBridge.get(context).wanted(key);
        return !state(context).getString(key + ".target", "").isEmpty();
    }

    static boolean current(Context context, String key, long generation) {
        return !reverse && wanted(context, key)
                && generation == state(context).getLong(key + ".generation", 0);
    }

    static boolean reversing() { return reverse; }

    static void reverse(Context context, boolean engaged) {
        reverse = engaged;
        WindowBridge.get(context).reverse(engaged);
        if (engaged) close(context, "external");
    }

    static void close(Context context, String key) {
        if (!key.equals("external")) WindowBridge.get(context).close(key);
        SharedPreferences state = state(context);
        state.edit().putLong(key + ".generation", state.getLong(key + ".generation", 0) + 1)
                .remove(key + ".target").remove(key + ".bounds").apply();
        ComponentName root = new ComponentName(context, root(key));
        for (ActivityManager.AppTask task : context.getSystemService(ActivityManager.class).getAppTasks()) {
            try {
                if (root.equals(task.getTaskInfo().baseIntent.getComponent())) {
                    Log.i(TAG, "Dismiss app window " + key + " task=" + task.getTaskInfo().taskId);
                    task.finishAndRemoveTask();
                }
            } catch (IllegalArgumentException retired) { /* Task already ended. */ }
        }
    }

    static void closePanes(Context context) { close(context, "pane_a"); close(context, "pane_b"); }
    static void closeAll(Context context) { for (String key : KEYS) close(context, key); }

    static void finished(Context context, String key, long generation) {
        if (current(context, key, generation)) {
            state(context).edit().remove(key + ".target").remove(key + ".bounds").apply();
        }
    }

    static void open(Context context, String key, ComponentName target, Rect bounds) {
        open(context, key, new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(target), bounds);
    }

    static void open(Context context, String key, Intent target, Rect bounds) {
        if (reverse) return;
        if (target.getComponent() == null || (bounds != null && bounds.isEmpty()))
            throw new IllegalArgumentException("App window needs a target and valid bounds");
        if (!key.equals("external")) {
            OemBars.keepHidden(context);
            WindowBridge.get(context).open(key, target.getComponent(), bounds);
            return;
        }
        Context app = context.getApplicationContext();
        String flat = target.getComponent().flattenToString();
        // One activity window per package, including a previous full-screen launch.
        for (String other : KEYS) {
            String previous = state(app).getString(other + ".target", "");
            ComponentName component = ComponentName.unflattenFromString(previous);
            if (other.equals(key) || (component != null
                    && component.getPackageName().equals(target.getComponent().getPackageName()))) close(app, other);
        }
        long generation = state(app).getLong(key + ".generation", 0) + 1;
        state(app).edit().putLong(key + ".generation", generation).putString(key + ".target", flat)
                .putString(key + ".bounds", bounds == null ? "" : bounds.flattenToString()).apply();
        Intent root = new Intent(app, root(key))
                .putExtra(PaneWindowActivity.TARGET, new Intent(target))
                .putExtra(PaneWindowActivity.KEY, key).putExtra(PaneWindowActivity.GENERATION, generation)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Rect frame = bounds == null ? null : new Rect(bounds);
        OemBars.keepHidden(app);
        // Finish old native/MAUI windows before creating the replacement, never MULTIPLE_TASK.
        UI.postDelayed(() -> {
            if (!current(app, key, generation)) return;
            try {
                Log.i(TAG, "Open app window " + key + " " + flat + " bounds=" + frame);
                app.startActivity(root, options(frame).toBundle());
            } catch (RuntimeException failure) {
                Log.w(TAG, "App window launch failed", failure);
                close(app, key);
            }
        }, 180);
    }

    static void resize(Context context, String key, ComponentName target, Rect bounds) {
        if (!key.equals("external")) {
            WindowBridge.get(context).resize(key,bounds);
            return;
        }
        if (!wanted(context, key) || bounds.isEmpty()
                || bounds.flattenToString().equals(state(context).getString(key + ".bounds", ""))) return;
        // This firmware ignores new bounds on AppTask.startActivity and can duplicate its child.
        // Recreate our owned window at the settled frame rather than leaving an orphaned task.
        Log.i(TAG, "Reflow app window " + key + " -> " + bounds);
        open(context, key, target, bounds);
    }

    static ActivityOptions options(Rect bounds) {
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchBounds(bounds);
        try { ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(options, bounds == null ? 1 : 5); }
        catch (ReflectiveOperationException unavailable) {
            if (bounds != null) throw new IllegalStateException("Freeform mode unavailable", unavailable);
        }
        return options;
    }
}
