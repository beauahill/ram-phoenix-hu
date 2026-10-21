package com.ramphoenix.camera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

/**
 * The whole camera UI. Portrait, fullscreen, immersive.
 *
 * Triggers:
 *   - reverse gear  -> ReverseService (background) or the in-activity monitor
 *                      (foreground) -> REAR view, instant.
 *   - manual (launcher / ACTION_SHOW intent / view buttons) -> last/selected view.
 *
 * View modes (per Beau's photo): Rear, Front, 360 (rear + overhead aux).
 * The 360 "overhead" panel currently shows the second ISP input (dev 10); true
 * stitched bird's-eye is a later milestone, and what dev 10 actually shows still
 * needs a live look (review 1C).
 *
 * Review fixes (2026-10-21):
 *  - #2 letterbox: rear/front feed + guideline overlay live in a runtime-sized
 *    16:9 box instead of a stretched full-screen portrait surface.
 *  - #4 sensor ticker is cancelled in onPause/onDestroy (no leaked activity).
 *  - 2A/2B: reverse-initiated launches return to the previous screen on
 *    disengage; ReverseService is kept running for reverse-from-anywhere.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PhoenixCam";
    private static final int REQ_CAM = 1;

    public static final String ACTION_SHOW   = "com.ramphoenix.camera.SHOW";
    public static final String ACTION_HIDE   = "com.ramphoenix.camera.HIDE";
    public static final String EXTRA_REVERSE = "reverse";

    private FrameLayout feedBox;
    private SurfaceView surfaceMain, surfaceAux;
    private GuidelineOverlay guidelines;
    private ParkingSensorView sensors;
    private LinearLayout selector;

    private CameraController camMain, camAux;
    private ReverseMonitor reverse;
    private ParkingSensorSource sensorSource = ParkingSensorSource.STUB;

    private CameraMode mode = CameraMode.REAR;
    private boolean mainSurfaceReady, auxSurfaceReady, permGranted;
    /** True when the current foregrounding was caused by shifting into reverse. */
    private boolean reverseInitiated;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable sensorTicker;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_camera);

        feedBox     = findViewById(R.id.feed_box);
        surfaceMain = findViewById(R.id.surface_main);
        surfaceAux  = findViewById(R.id.surface_aux);
        guidelines  = findViewById(R.id.guidelines);
        sensors     = findViewById(R.id.sensors);
        selector    = findViewById(R.id.selector);

        camMain = new CameraController(this);
        camAux  = new CameraController(this);
        sensors.setSource(sensorSource);

        buildSelector();
        guidelines.setMirror(Sysfs.readInt(HwConfig.NODE_MIRROR, 0) == 1);
        sizeFeedBoxes();

        surfaceMain.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(@NonNull SurfaceHolder h) { mainSurfaceReady = true; applyMode(); }
            @Override public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int ht) {}
            @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) { mainSurfaceReady = false; camMain.close(); }
        });
        surfaceAux.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(@NonNull SurfaceHolder h) { auxSurfaceReady = true; applyMode(); }
            @Override public void surfaceChanged(@NonNull SurfaceHolder h, int f, int w, int ht) {}
            @Override public void surfaceDestroyed(@NonNull SurfaceHolder h) { auxSurfaceReady = false; camAux.close(); }
        });

        reverse = new ReverseMonitor(engaged -> {
            if (engaged) { reverseInitiated = true; setMode(CameraMode.REAR); }
            else if (reverseInitiated) { reverseInitiated = false; moveTaskToBack(true); }
        });

        permGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!permGranted) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        }

        // Keep the background reverse watcher alive so reverse works from anywhere.
        ReverseService.ensureRunning(this);
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (ACTION_HIDE.equals(intent.getAction())) {
            if (reverseInitiated) { reverseInitiated = false; moveTaskToBack(true); }
            return;
        }
        if (intent.getBooleanExtra(EXTRA_REVERSE, false)) {
            reverseInitiated = true;
            setMode(CameraMode.REAR);
        }
    }

    /** Compute the 16:9 letterbox height from the real panel width and pin both
     *  the main feed box and the aux (overhead) surface to it. Done at runtime
     *  because dp heights drift across densities (review #2/#5). */
    private void sizeFeedBoxes() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int boxH = Math.round(screenW * 9f / 16f);
        if (feedBox != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) feedBox.getLayoutParams();
            lp.height = boxH;
            feedBox.setLayoutParams(lp);
        }
        if (surfaceAux != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceAux.getLayoutParams();
            lp.height = boxH;
            surfaceAux.setLayoutParams(lp);
        }
    }

    private void buildSelector() {
        selector.removeAllViews();
        for (final CameraMode m : CameraMode.values()) {
            Button btn = new Button(this);
            btn.setText(m.label);
            btn.setAllCaps(false);
            btn.setBackgroundResource(R.drawable.btn_view);
            btn.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 18;
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> setMode(m));
            selector.addView(btn);
        }
    }

    private void setMode(CameraMode m) {
        if (mode != m) Log.i(TAG, "mode -> " + m);
        mode = m;
        applyMode();
    }

    /** Idempotent: binds cameras + overlays to whatever the current mode is. */
    private void applyMode() {
        if (!permGranted || !mainSurfaceReady) return;

        switch (mode) {
            case REAR:
                setFeedGravity(Gravity.CENTER);
                surfaceAux.setVisibility(View.GONE);
                guidelines.setVisibility(View.VISIBLE);
                camAux.close();
                camMain.open(HwConfig.REAR_CAM_ID, surfaceMain.getHolder());
                break;
            case FRONT:
                setFeedGravity(Gravity.CENTER);
                surfaceAux.setVisibility(View.GONE);
                guidelines.setVisibility(View.GONE);
                camAux.close();
                camMain.open(HwConfig.FRONT_CAM_ID, surfaceMain.getHolder());
                break;
            case SURROUND_360:
                // overhead on top, rear below — each its own 16:9 box
                setFeedGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                guidelines.setVisibility(View.GONE);
                surfaceAux.setVisibility(View.VISIBLE);
                camMain.open(HwConfig.REAR_CAM_ID, surfaceMain.getHolder());
                if (auxSurfaceReady) {
                    camAux.open(HwConfig.SURROUND_AUX_ID, surfaceAux.getHolder());
                }
                break;
        }
        sensors.setVisibility(View.VISIBLE);
        highlightSelector();
    }

    private void setFeedGravity(int gravity) {
        if (feedBox == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) feedBox.getLayoutParams();
        lp.gravity = gravity;
        feedBox.setLayoutParams(lp);
    }

    private void highlightSelector() {
        for (int i = 0; i < selector.getChildCount(); i++) {
            Button b = (Button) selector.getChildAt(i);
            b.setSelected(CameraMode.values()[i] == mode);
            b.setAlpha(CameraMode.values()[i] == mode ? 1f : 0.6f);
        }
    }

    private void startSensorTicker() {
        stopSensorTicker();
        sensorTicker = new Runnable() {
            @Override public void run() {
                sensors.tick();
                ui.postDelayed(this, 66); // ~15fps for flashing
            }
        };
        ui.post(sensorTicker);
    }

    private void stopSensorTicker() {
        if (sensorTicker != null) { ui.removeCallbacks(sensorTicker); sensorTicker = null; }
    }

    @Override public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_CAM && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
            permGranted = true;
            applyMode();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (reverse != null) reverse.start();
        startSensorTicker();
        applyMode();
    }

    @Override protected void onPause() {
        super.onPause();
        if (reverse != null) reverse.stop();
        stopSensorTicker();
        camMain.close();
        camAux.close();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopSensorTicker();
        camMain.shutdown();
        camAux.shutdown();
    }

    private void hideSystemBars() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
              | View.SYSTEM_UI_FLAG_FULLSCREEN
              | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
