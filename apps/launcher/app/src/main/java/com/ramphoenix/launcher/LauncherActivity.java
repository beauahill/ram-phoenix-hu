package com.ramphoenix.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Phoenix dashboard — the HOME screen. Status row and dock on top, then one of
 * two layouts (toggle in the status row, persisted):
 *
 *   FULL  — live map, now-playing and drive gauges stacked to fill the screen.
 *   SPLIT — two 50/50 landscape panes; each shows the widget (or app) its own
 *           picker selects, so each half behaves like its own screen.
 *
 * The three widgets are inflated once and re-parented between the FULL slots
 * and the panes. Data sources: map/speed = GPS; media = MediaSessionManager;
 * vehicle = {@link VehicleData#STUB} until the canbus indexes are pinned.
 */
public class LauncherActivity extends Activity implements Pane.Host {

    private static final int REQ_LOC = 1;
    private static final String PREFS = "phoenix";
    private static final String PREF_MODE = "mode";
    private static final String MODE_FULL = "full", MODE_SPLIT = "split";

    private SharedPreferences prefs;
    private String mode = MODE_FULL;

    private TextView tvTime, tvDate, tvOutside;
    private ImageView btnLayout;

    // shared widgets (inflated once, moved between containers)
    private View mapCard, mediaCard, driveRow;
    private MapWidget map;
    private MediaWidget media;
    private SpeedWidget speed;
    private VehicleCardView vehicleCard;

    // containers
    private View fullContainer, splitContainer;
    private FrameLayout slotFullMap, slotFullMedia, slotFullDrive;
    private Pane paneA, paneB;

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
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        mode = prefs.getString(PREF_MODE, MODE_FULL);

        tvTime = findViewById(R.id.clock_time);
        tvDate = findViewById(R.id.clock_date);
        tvOutside = findViewById(R.id.status_outside);
        btnLayout = findViewById(R.id.btn_layout);
        btnLayout.setOnClickListener(v -> toggleMode());
        findViewById(R.id.btn_settings).setOnClickListener(v -> openSettings());

        Dock.build(this, (LinearLayout) findViewById(R.id.dock));

        // --- widgets ---
        ViewGroup content = findViewById(R.id.content);
        LayoutInflater li = LayoutInflater.from(this);
        mapCard = li.inflate(R.layout.widget_map, content, false);
        mediaCard = li.inflate(R.layout.widget_media, content, false);
        driveRow = li.inflate(R.layout.widget_drive, content, false);

        map = new MapWidget(this, mapCard.findViewById(R.id.map_container));
        mapCard.findViewById(R.id.btn_nav).setOnClickListener(v -> launchOrToast(Dock.navIntent(this)));
        mapCard.findViewById(R.id.search_bar).setOnClickListener(v -> launchOrToast(Dock.navIntent(this)));
        mapCard.findViewById(R.id.btn_zoom_in).setOnClickListener(v -> map.zoomIn());
        mapCard.findViewById(R.id.btn_zoom_out).setOnClickListener(v -> map.zoomOut());

        media = new MediaWidget(this, mediaCard);

        vehicleCard = driveRow.findViewById(R.id.vehicle_card);
        speed = new SpeedWidget(this, vehicleCard);

        // --- containers ---
        fullContainer = findViewById(R.id.full_container);
        splitContainer = findViewById(R.id.split_container);
        slotFullMap = findViewById(R.id.slot_full_map);
        slotFullMedia = findViewById(R.id.slot_full_media);
        slotFullDrive = findViewById(R.id.slot_full_drive);
        paneA = new Pane(this, findViewById(R.id.pane_a), "pane_a", prefs, Pane.Content.MAP);
        paneB = new Pane(this, findViewById(R.id.pane_b), "pane_b", prefs, Pane.Content.MEDIA);

        applyMode();
        ensureLocationPermission();
        updateClock();
        updateVehicle();
    }

    // ---- layout modes ----

    private void toggleMode() {
        mode = MODE_FULL.equals(mode) ? MODE_SPLIT : MODE_FULL;
        prefs.edit().putString(PREF_MODE, mode).apply();
        applyMode();
    }

    private void applyMode() {
        boolean split = MODE_SPLIT.equals(mode);
        btnLayout.setImageResource(split ? R.drawable.ic_layout_full : R.drawable.ic_layout_split);
        if (split) {
            slotFullMap.removeAllViews();
            slotFullMedia.removeAllViews();
            slotFullDrive.removeAllViews();
            fullContainer.setVisibility(View.GONE);
            splitContainer.setVisibility(View.VISIBLE);
            Pane.Content a = paneA.content(), b = paneB.content();
            if (a == b && a != Pane.Content.APP) b = (a == Pane.Content.MAP) ? Pane.Content.MEDIA : Pane.Content.MAP;
            paneA.show(a);
            paneB.show(b);
            media.setLarge(true);
        } else {
            paneA.clear();
            paneB.clear();
            splitContainer.setVisibility(View.GONE);
            fullContainer.setVisibility(View.VISIBLE);
            place(mapCard, slotFullMap);
            place(mediaCard, slotFullMedia);
            place(driveRow, slotFullDrive);
            media.setLarge(false);
        }
    }

    private static void place(View v, FrameLayout slot) {
        Pane.detach(v);
        slot.removeAllViews();
        slot.addView(v, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    // Pane.Host
    @Override public View widgetFor(Pane.Content c) {
        switch (c) {
            case MAP: return mapCard;
            case MEDIA: return mediaCard;
            case DRIVE: return driveRow;
            default: throw new IllegalArgumentException("no shared widget for " + c);
        }
    }

    @Override public void onSelect(Pane pane, Pane.Content c) {
        Pane other = (pane == paneA) ? paneB : paneA;
        if (c != Pane.Content.APP && other.content() == c) {
            // the shared widget can only live in one pane: swap
            other.show(pane.content());
        }
        pane.show(c);
        media.setLarge(true);
    }

    @Override public Activity activity() { return this; }

    // ---- lifecycle ----

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
    public void onLowMemory() {
        super.onLowMemory();
        map.onLowMemory();
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
        vehicleCard.setData(vehicle);
        tvOutside.setText(Fmt.tempF(vehicle.outsideC()));
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
}
