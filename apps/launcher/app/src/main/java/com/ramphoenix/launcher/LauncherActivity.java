package com.ramphoenix.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Phoenix dashboard — the HOME screen. CarPlay-style large widgets for the
 * portrait panel: live map + now-playing on the upper half, speed + vehicle
 * sensors below, a dock at the bottom. Plain framework widgets, no AndroidX.
 *
 * Data sources today:
 *   map / speed  -> GPS (LocationManager + osmdroid)
 *   media        -> MediaSessionManager (needs notification-listener access)
 *   vehicle      -> {@link VehicleData#STUB} until the canbus LSEC IPC indexes
 *                   for tire pressure / fuel / temps are pinned (see docs).
 */
public class LauncherActivity extends Activity {

    private static final int REQ_LOC = 1;

    private TextView tvTime, tvDate, tvOutside;
    private MapWidget map;
    private MediaWidget media;
    private SpeedWidget speed;
    private TruckSensorView truck;
    private StatCell fuel, coolant, battery, outside;

    private VehicleData vehicle = VehicleData.STUB;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.US);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, MMM d", Locale.US);

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            ui.postDelayed(this, 60000 - (now % 60000));
        }
    };
    private final Runnable vehicleTick = new Runnable() {
        @Override public void run() {
            updateVehicle();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        goImmersive();

        tvTime = findViewById(R.id.clock_time);
        tvDate = findViewById(R.id.clock_date);
        tvOutside = findViewById(R.id.status_outside);
        findViewById(R.id.btn_settings).setOnClickListener(v -> openSettings());

        map = new MapWidget(this, findViewById(R.id.map_container));
        findViewById(R.id.btn_nav).setOnClickListener(v -> launchOrToast(Dock.navIntent(this)));
        findViewById(R.id.search_bar).setOnClickListener(v -> launchOrToast(Dock.navIntent(this)));
        findViewById(R.id.btn_zoom_in).setOnClickListener(v -> map.zoomIn());
        findViewById(R.id.btn_zoom_out).setOnClickListener(v -> map.zoomOut());

        media = new MediaWidget(this, findViewById(R.id.media_card));
        speed = new SpeedWidget(this,
                findViewById(R.id.speed_value),
                findViewById(R.id.speed_unit),
                findViewById(R.id.speed_source));

        truck = findViewById(R.id.truck);
        fuel    = new StatCell(findViewById(R.id.stat_fuel),    getString(R.string.fuel));
        coolant = new StatCell(findViewById(R.id.stat_coolant), getString(R.string.coolant));
        battery = new StatCell(findViewById(R.id.stat_battery), getString(R.string.battery));
        outside = new StatCell(findViewById(R.id.stat_outside), getString(R.string.outside));

        Dock.build(this, (LinearLayout) findViewById(R.id.dock));

        ensureLocationPermission();
        updateClock();
        updateVehicle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        goImmersive();
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);
        ui.removeCallbacks(vehicleTick);
        ui.post(vehicleTick);
        map.onResume();
        media.onResume();
        speed.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(vehicleTick);
        speed.stop();
        media.onPause();
        map.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        map.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // A home screen swallows BACK.
    }

    // ---- data refresh ----

    private void updateClock() {
        Date now = new Date();
        tvTime.setText(timeFmt.format(now).toLowerCase(Locale.US));
        tvDate.setText(dateFmt.format(now));
    }

    private void updateVehicle() {
        truck.setData(vehicle);
        fuel.set(Fmt.percent(vehicle.fuelPercent()));
        coolant.set(Fmt.tempF(vehicle.coolantC()));
        battery.set(Fmt.volts(vehicle.batteryVolts()));
        String out = Fmt.tempF(vehicle.outsideC());
        outside.set(out);
        tvOutside.setText(out);
        speed.setVehicle(vehicle);
    }

    // ---- permissions ----

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureLocationPermission() {
        if (hasLocation()) {
            map.enableLocation();
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOC);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_LOC && hasLocation()) {
            map.enableLocation();
            speed.start();
        }
    }

    // ---- launching ----

    private void launchOrToast(Intent i) {
        if (i == null) {
            Toast.makeText(this, R.string.not_installed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_installed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings() {
        launchOrToast(new Intent(Settings.ACTION_SETTINGS));
    }

    private void goImmersive() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /** One value-over-label cell in the vehicle card (layout/stat_cell.xml). */
    static final class StatCell {
        private final TextView value;
        StatCell(View root, String label) {
            value = root.findViewById(R.id.stat_value);
            ((TextView) root.findViewById(R.id.stat_label)).setText(label);
        }
        void set(String v) { value.setText(v); }
    }
}
