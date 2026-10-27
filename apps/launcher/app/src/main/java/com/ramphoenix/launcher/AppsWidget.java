package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Eight shortcut tiles; long-press a tile to assign any launchable app (YouTube, Waze, ...). */
public class AppsWidget {
    private static final int COLS = 4, ROWS = 2;
    private final Activity act;
    private final SharedPreferences prefs;
    private final LinearLayout[] rows;

    public AppsWidget(Activity act, View card) {
        this.act = act;
        prefs = act.getSharedPreferences("apps_card", Context.MODE_PRIVATE);
        rows = new LinearLayout[]{card.findViewById(R.id.apps_row1), card.findViewById(R.id.apps_row2)};
        build();
    }

    public void build() {
        float d = act.getResources().getDisplayMetrics().density;
        for (int r = 0; r < ROWS; r++) {
            rows[r].removeAllViews();
            for (int c = 0; c < COLS; c++) {
                final int slot = r * COLS + c;
                LinearLayout tile = new LinearLayout(act);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setBackgroundResource(R.drawable.glass_tile_selector_sq);
                tile.setClickable(true); tile.setFocusable(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(Math.round(3 * d), Math.round(3 * d), Math.round(3 * d), Math.round(3 * d));
                ImageView icon = new ImageView(act);
                int sz = Math.round(36 * d);
                icon.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
                TextView label = new TextView(act);
                label.setTextColor(act.getColor(R.color.text_secondary)); label.setTextSize(10); label.setMaxLines(1);
                label.setGravity(Gravity.CENTER); label.setPadding(0, Math.round(4 * d), 0, 0);
                tile.addView(icon); tile.addView(label);
                rows[r].addView(tile, lp);

                ComponentName cn = ComponentName.unflattenFromString(prefs.getString("slot" + slot, ""));
                if (cn != null) {
                    try {
                        Drawable ic = act.getPackageManager().getActivityIcon(cn);
                        icon.setImageDrawable(ic);
                        label.setText(act.getPackageManager().getActivityInfo(cn, 0).loadLabel(act.getPackageManager()));
                    } catch (Exception e) { cn = null; }
                }
                if (cn == null) { icon.setImageResource(R.drawable.ic_m_add); icon.setAlpha(0.35f); label.setText(""); }
                final ComponentName target = cn;
                tile.setOnClickListener(v -> {
                    if (target == null) { choose(slot); return; }
                    Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(target)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    try { AppLaunch.fullscreen(act, i); } catch (Exception e) { choose(slot); }
                });
                tile.setOnLongClickListener(v -> { choose(slot); return true; });
            }
        }
    }

    private void choose(int slot) {
        AppPicker.pick(act, act.getString(R.string.apps_pick), act.getString(R.string.apps_clear), cn -> {
            SharedPreferences.Editor e = prefs.edit();
            if (cn == null) e.remove("slot" + slot); else e.putString("slot" + slot, cn.flattenToString());
            e.apply();
            build();
        });
    }
}
