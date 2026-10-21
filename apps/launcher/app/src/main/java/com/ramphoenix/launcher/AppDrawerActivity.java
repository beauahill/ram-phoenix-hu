package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.GridView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The all-apps grid, opened from the dock's Apps button. */
public class AppDrawerActivity extends Activity {

    private final List<AppInfo> apps = new ArrayList<>();
    private AppGridAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drawer);
        goImmersive();
        GridView grid = findViewById(R.id.app_grid);
        adapter = new AppGridAdapter(this, apps);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> launch(apps.get(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            openAppDetails(apps.get(position));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
        new Thread(() -> {
            final List<AppInfo> loaded = AppRepository.loadLaunchable(this);
            runOnUiThread(() -> {
                apps.clear();
                apps.addAll(loaded);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void launch(AppInfo app) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            finish();
        } catch (ActivityNotFoundException e) {
            Intent fb = getPackageManager().getLaunchIntentForPackage(app.packageName);
            if (fb != null) {
                fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fb);
                finish();
            } else {
                Toast.makeText(this, "Can't open " + app.label, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openAppDetails(AppInfo app) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + app.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void goImmersive() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        View root = findViewById(android.R.id.content);
        if (root != null) root.setKeepScreenOn(false);
    }
}
