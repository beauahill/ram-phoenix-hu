package com.ramphoenix.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;

import java.util.List;

/** "Choose an app" dialog over every launchable activity; shared by the dock and the apps card. */
public final class AppPicker {
    private AppPicker() {}

    public interface Result { void chosen(ComponentName cn); }   // null = reset to default

    public static void pick(final Activity act, final String title, final String resetLabel, final Result r) {
        new Thread(() -> {
            final List<AppInfo> apps = AppRepository.loadLaunchable(act);
            final String[] names = new String[apps.size() + 1];
            names[0] = resetLabel;
            for (int i = 0; i < apps.size(); i++) names[i + 1] = apps.get(i).label;
            act.runOnUiThread(() -> new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(title)
                    .setItems(names, (d, which) -> r.chosen(which == 0 ? null : apps.get(which - 1).component))
                    .show());
        }, "app-pick").start();
    }
}
