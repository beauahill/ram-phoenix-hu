package com.ramphoenix.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Queries the system for every launchable app and builds the sorted drawer list. */
public class AppRepository {

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
                if ("com.android.launcher6".equals(pkg)
                        || LauncherActivity.class.getName().equals(ri.activityInfo.name)) continue;
                ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
                String key = cn.flattenToShortString();
                if (!seen.add(key)) continue;
                CharSequence lbl = ri.loadLabel(pm);
                out.add(new AppInfo(lbl == null ? pkg : lbl.toString(), cn));
            }
        }

        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }
}
