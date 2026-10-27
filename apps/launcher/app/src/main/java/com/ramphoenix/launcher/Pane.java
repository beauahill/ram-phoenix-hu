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

    public enum Content { MAP, MEDIA, DRIVE, CLIMATE, APP }

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
    private final ImageView chevron;
    private final PaneControls controls;
    private boolean visible;
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener geometry = this::geometryChanged;
    private final Runnable reflow = this::reflowApp;

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
        slot.getViewTreeObserver().addOnGlobalLayoutListener(geometry);
        chevron = root.findViewById(R.id.pane_chevron);
        controls = new PaneControls(host.activity(), this::choose);
        chevron.setOnClickListener(v -> choose());

        String saved = prefs.getString(key, def.name());
        try { content = Content.valueOf(saved); } catch (IllegalArgumentException e) { content = def; }
        String savedApp = prefs.getString(key + "_app", null);
        app = savedApp != null ? ComponentName.unflattenFromString(savedApp) : null;
    }

    public Content content() { return content; }

    /** Put {@code c} in this pane (re-parents the shared widget if needed). */
    public void show(Content c) {
        slot.removeCallbacks(reflow);
        PaneWindows.close(host.activity(), key);
        content = c;
        prefs.edit().putString(key, c.name()).apply();
        slot.removeAllViews();
        View v = (c == Content.APP) ? appView() : host.widgetFor(c);
        detach(v);
        slot.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        updateControls();
    }

    /** Leaving SPLIT mode: release whatever widget we hold. */
    public void clear() {
        slot.removeCallbacks(reflow);
        PaneWindows.close(host.activity(), key);
        slot.removeAllViews();
        controls.hide();
    }

    public void dispose() {
        clear();
        if (slot.getViewTreeObserver().isAlive()) slot.getViewTreeObserver().removeOnGlobalLayoutListener(geometry);
    }

    private void geometryChanged() {
        updateControls();
        if (content != Content.APP || !PaneWindows.wanted(host.activity(), key)) return;
        slot.removeCallbacks(reflow);
        slot.postDelayed(reflow, 160);
    }

    private void reflowApp() {
        if (content != Content.APP || app == null || !slot.isShown()) return;
        PaneWindows.resize(host.activity(), key, app, bounds());
    }

    static void detach(View v) {
        ViewParent p = v.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(v);
    }

    public void visible(boolean value) { visible=value; updateControls(); }

    public void updateControls() {
        boolean overlay = visible && slot.isShown() && slot.getWidth()>0 && slot.getHeight()>0 && !PaneWindows.reversing();
        controls.update(overlay, bounds());
        chevron.setVisibility(overlay && android.provider.Settings.canDrawOverlays(host.activity()) ? View.INVISIBLE : View.VISIBLE);
    }

    private void choose() {
        if (android.provider.Settings.canDrawOverlays(host.activity())) {
            controls.menu(bounds(), content, c -> host.onSelect(this,c));
        } else {
            String[] labels={"Map","Media","Vehicle","Climate","Apps"};
            new android.app.AlertDialog.Builder(host.activity()).setTitle("Pane content")
                    .setItems(labels,(dialog,index) -> host.onSelect(this,Content.values()[index])).show();
        }
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
        appView.findViewById(R.id.window_setup).setOnClickListener(v ->
            new android.app.AlertDialog.Builder(act).setTitle("Enable split app windows")
                .setMessage("Android needs to approve this launcher's local window connection once. On the debugging prompt, choose Always allow and Allow. The key stays on this head unit.")
                .setPositiveButton("Set up",(dialog,which) -> WindowBridge.get(act).setup(() ->
                    Toast.makeText(act,WindowBridge.get(act).status(),Toast.LENGTH_LONG).show()))
                .setNegativeButton("Cancel",null).show());
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
        if (!WindowBridge.get(act).ready()) {
            appView.findViewById(R.id.window_setup).setVisibility(View.VISIBLE);
            Toast.makeText(act,WindowBridge.get(act).status(),Toast.LENGTH_LONG).show();
            return;
        }
        appView.findViewById(R.id.window_setup).setVisibility(View.GONE);
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app);
        try {
            AppLaunch.inPane(act, key, i, bounds());
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
        appView.findViewById(R.id.window_setup).setVisibility(WindowBridge.get(host.activity()).ready() ? View.GONE : View.VISIBLE);
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
