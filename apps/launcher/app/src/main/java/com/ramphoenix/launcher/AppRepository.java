package com.ramphoenix.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Queries the system for every launchable app and builds the sorted drawer list,
 * plus resolves the curated "pinned" quick-launch row.
 */
public class AppRepository {

    /** Our own package + the stock launcher we are replacing — hidden from the drawer. */
    private static final Set<String> HIDE = new LinkedHashSet<>();
    static {
        HIDE.add("com.ramphoenix.launcher");
        HIDE.add("com.android.launcher6");
    }

    /**
     * Curated quick-launch tiles, in display order. Camera first (our own app).
     * Only the ones actually installed get shown, so this list can be aspirational.
     */
    public static final String[][] PINNED = {
            {"Camera",   "com.ramphoenix.camera"},
            {"Radio",    "com.syu.radio"},
            {"Media",    "com.syu.music"},
            {"CarPlay",  "com.syu.carlink"},
            {"Phone",    "com.syu.bt"},
            {"Settings", "com.android.settings"},
    };

    public static List<AppInfo> loadLaunchable(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> seen = new LinkedHashSet<>();
        List<AppInfo> out = new ArrayList<>();

        for (String category : new String[]{
                Intent.CATEGORY_LAUNCHER,
                Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent main = new Intent(Intent.ACTION_MAIN, null).addCategory(category);
            List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (HIDE.contains(pkg)) continue;
                ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
                String key = cn.flattenToShortString();
                if (!seen.add(key)) continue;
                CharSequence lbl = ri.loadLabel(pm);
                out.add(new AppInfo(lbl == null ? pkg : lbl.toString(), cn));
            }
        }

        Collections.sort(out, new Comparator<AppInfo>() {
            @Override public int compare(AppInfo a, AppInfo b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    /** Resolve the pinned list to the subset that is actually installed and launchable. */
    public static List<AppInfo> resolvePinned(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<AppInfo> out = new ArrayList<>();
        for (String[] entry : PINNED) {
            String label = entry[0];
            String pkg = entry[1];
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            ComponentName cn = launch.getComponent();
            if (cn == null) continue;
            out.add(new AppInfo(label, cn));
        }
        return out;
    }
}
