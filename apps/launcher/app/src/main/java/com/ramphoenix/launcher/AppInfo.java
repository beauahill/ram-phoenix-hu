package com.ramphoenix.launcher;

import android.content.ComponentName;

/** One launchable app on the head unit. */
public class AppInfo {
    public final String label;
    public final String packageName;
    public final ComponentName component;

    public AppInfo(String label, ComponentName component) {
        this.label = label;
        this.component = component;
        this.packageName = component.getPackageName();
    }
}
