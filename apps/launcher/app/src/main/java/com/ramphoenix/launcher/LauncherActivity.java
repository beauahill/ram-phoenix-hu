package com.ramphoenix.launcher;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Phoenix Launcher — clean, modern, portrait home screen that replaces the stock
 * com.android.launcher6. Big glanceable clock, a curated quick-launch tile row
 * (Camera first), and the full app drawer in a 4-column grid.
 *
 * It is a HOME app: pressing the stock HOME key lands here. It never reimplements
 * anything in the SYU HAL — it just launches apps.
 */
public class LauncherActivity extends AppCompatActivity {

    private static final int GRID_COLUMNS = 4;

    private TextView tvTime;
    private TextView tvDate;
    private RecyclerView rvPinned;
    private RecyclerView rvApps;

    private final List<AppInfo> apps = new ArrayList<>();
    private AppAdapter appAdapter;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm", Locale.US);
    private final SimpleDateFormat ampmFmt = new SimpleDateFormat("a", Locale.US);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, MMMM d", Locale.US);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            // Align the next tick to the top of the next minute for efficiency.
            long now = System.currentTimeMillis();
            long delay = 60000 - (now % 60000);
            ui.postDelayed(this, delay);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        goImmersive();
        applyWallpaperBlur();

        tvTime = findViewById(R.id.clock_time);
        tvDate = findViewById(R.id.clock_date);
        rvPinned = findViewById(R.id.pinned_row);
        rvApps = findViewById(R.id.app_grid);

        View gear = findViewById(R.id.btn_settings);
        gear.setOnClickListener(v -> openSettings());

        rvPinned.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvApps.setLayoutManager(new GridLayoutManager(this, GRID_COLUMNS));
        rvApps.setHasFixedSize(true);

        appAdapter = new AppAdapter(this, apps, new AppAdapter.Listener() {
            @Override public void onLaunch(AppInfo app) { launch(app); }
            @Override public void onDetails(AppInfo app) { openAppDetails(app); }
        });
        rvApps.setAdapter(appAdapter);

        reloadApps();
        updateClock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);
        // Refresh in case apps were installed/removed while we were away.
        reloadApps();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(clockTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while we are already foreground: scroll back to top.
        if (rvApps != null) rvApps.scrollToPosition(0);
    }

    @Override
    public void onBackPressed() {
        // A launcher should swallow BACK (never exit to a blank screen).
        if (rvApps != null) rvApps.scrollToPosition(0);
    }

    private void updateClock() {
        Date now = new Date();
        tvTime.setText(timeFmt.format(now) + " " + ampmFmt.format(now).toLowerCase(Locale.US));
        tvDate.setText(dateFmt.format(now));
    }

    private void reloadApps() {
        new Thread(() -> {
            final List<AppInfo> loaded = AppRepository.loadLaunchable(this);
            final List<AppInfo> pinned = AppRepository.resolvePinned(this);
            ui.post(() -> {
                apps.clear();
                apps.addAll(loaded);
                appAdapter.notifyDataSetChanged();

                TileAdapter tileAdapter = new TileAdapter(this, pinned, this::launch);
                rvPinned.setAdapter(tileAdapter);
                rvPinned.setVisibility(pinned.isEmpty() ? View.GONE : View.VISIBLE);
            });
        }).start();
    }

    private void launch(AppInfo app) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(app.component);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            // Fall back to the package's default launch intent.
            Intent fb = getPackageManager().getLaunchIntentForPackage(app.packageName);
            if (fb != null) {
                fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fb);
            } else {
                Toast.makeText(this, "Can't open " + app.label, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openAppDetails(AppInfo app) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + app.packageName));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Settings unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    /** Real frosted-glass depth: blur the wallpaper layer (API 31+ / this unit is API 33). */
    private void applyWallpaperBlur() {
        View wall = findViewById(R.id.wallpaper);
        if (wall == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            wall.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    55f, 55f, android.graphics.Shader.TileMode.CLAMP));
        }
    }

    private void goImmersive() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
