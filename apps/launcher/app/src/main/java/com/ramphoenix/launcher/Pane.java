package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * One half of SPLIT mode. Shows one of the shared widgets (map / media / drive)
 * or its own "app" content, where a chosen app is launched into this half's
 * screen bounds. The selection and the chosen app are persisted per pane.
 */
public class Pane {

    public enum Content { MAP, MEDIA, DRIVE, APP }

    /** The activity hands out the shared widget views and arbitrates selections. */
    public interface Host {
        View widgetFor(Content c);
        void onSelect(Pane pane, Content c);
        Activity activity();
    }

    private final Host host;
    private final String key;
    private final SharedPreferences prefs;
    private final FrameLayout slot;
    private final ImageView selMap, selMedia, selDrive, selApp;

    private Content content;
    private ComponentName app;

    // app content (lazy)
    private View appView;
    private View runStrip;
    private ImageView runIcon;
    private TextView runLabel;
    private AppGridAdapter adapter;
    private final List<AppInfo> apps = new ArrayList<>();

    public Pane(Host host, FrameLayout container, String key, SharedPreferences prefs, Content def) {
        this.host = host;
        this.key = key;
        this.prefs = prefs;
        View root = LayoutInflater.from(host.activity()).inflate(R.layout.pane, container, false);
        container.addView(root);
        slot = root.findViewById(R.id.pane_slot);
        selMap = root.findViewById(R.id.sel_map);
        selMedia = root.findViewById(R.id.sel_media);
        selDrive = root.findViewById(R.id.sel_drive);
        selApp = root.findViewById(R.id.sel_app);
        selMap.setOnClickListener(v -> host.onSelect(this, Content.MAP));
        selMedia.setOnClickListener(v -> host.onSelect(this, Content.MEDIA));
        selDrive.setOnClickListener(v -> host.onSelect(this, Content.DRIVE));
        selApp.setOnClickListener(v -> host.onSelect(this, Content.APP));

        String saved = prefs.getString(key, def.name());
        try { content = Content.valueOf(saved); } catch (IllegalArgumentException e) { content = def; }
        String savedApp = prefs.getString(key + "_app", null);
        app = savedApp != null ? ComponentName.unflattenFromString(savedApp) : null;
        highlight();
    }

    public Content content() { return content; }

    /** Put {@code c} in this pane (re-parents the shared widget if needed). */
    public void show(Content c) {
        content = c;
        prefs.edit().putString(key, c.name()).apply();
        slot.removeAllViews();
        View v = (c == Content.APP) ? appView() : host.widgetFor(c);
        detach(v);
        slot.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        highlight();
    }

    /** Leaving SPLIT mode: release whatever widget we hold. */
    public void clear() {
        slot.removeAllViews();
    }

    static void detach(View v) {
        ViewParent p = v.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(v);
    }

    private void highlight() {
        selMap.setAlpha(content == Content.MAP ? 1f : 0.45f);
        selMedia.setAlpha(content == Content.MEDIA ? 1f : 0.45f);
        selDrive.setAlpha(content == Content.DRIVE ? 1f : 0.45f);
        selApp.setAlpha(content == Content.APP ? 1f : 0.45f);
    }

    // ---- app content ----

    private View appView() {
        if (appView != null) return appView;
        Activity act = host.activity();
        appView = LayoutInflater.from(act).inflate(R.layout.pane_app, slot, false);
        runStrip = appView.findViewById(R.id.run_strip);
        runIcon = appView.findViewById(R.id.run_icon);
        runLabel = appView.findViewById(R.id.run_label);
        appView.findViewById(R.id.btn_open).setOnClickListener(v -> relaunch());
        GridView grid = appView.findViewById(R.id.pane_app_grid);
        adapter = new AppGridAdapter(act, apps);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> launchApp(apps.get(position)));
        new Thread(() -> {
            final List<AppInfo> loaded = AppRepository.loadLaunchable(act);
            act.runOnUiThread(() -> {
                apps.clear();
                apps.addAll(loaded);
                adapter.notifyDataSetChanged();
            });
        }).start();
        showRunning();
        return appView;
    }

    private void launchApp(AppInfo a) {
        app = a.component;
        prefs.edit().putString(key + "_app", app.flattenToString()).apply();
        showRunning();
        relaunch();
    }

    private void relaunch() {
        if (app == null) return;
        Activity act = host.activity();
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app);
        try {
            AppLaunch.inBounds(act, i, bounds());
            if (!AppLaunch.freeformEnabled(act)) {
                Toast.makeText(act, R.string.freeform_hint, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(act, R.string.not_installed, Toast.LENGTH_SHORT).show();
        }
    }

    /** This pane's rectangle in screen pixels: where the app window should go. */
    private Rect bounds() {
        int[] loc = new int[2];
        slot.getLocationOnScreen(loc);
        return new Rect(loc[0], loc[1], loc[0] + slot.getWidth(), loc[1] + slot.getHeight());
    }

    private void showRunning() {
        if (runStrip == null) return;
        if (app == null) { runStrip.setVisibility(View.GONE); return; }
        PackageManager pm = host.activity().getPackageManager();
        try {
            runIcon.setImageDrawable(pm.getActivityIcon(app));
            runLabel.setText(pm.getActivityInfo(app, 0).loadLabel(pm));
            runStrip.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            runStrip.setVisibility(View.GONE);
        }
    }
}
