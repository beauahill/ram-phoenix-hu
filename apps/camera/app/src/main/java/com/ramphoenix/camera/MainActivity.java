package com.ramphoenix.camera;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
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
 * Reverse handling is owned by {@link ReverseService} (the only IPC subscriber):
 *   - app not on screen: the service starts us with ACTION_SHOW + EXTRA_REVERSE.
 *   - app on screen: the service calls {@link #requestShowRear()} /
 *     {@link #requestHide()} directly (same process). No intents are delivered
 *     to a resumed activity any more: on this unit that caused a
 *     pause/newIntent/resume cycle that closed and re-opened camera 14 on every
 *     shift (the "re-opened for ~7 s after HIDE" seen live 2026-10-21).
 *
 * What device 14 is (live 2026-10-21): the 360 ECU's composite CVBS output,
 * 720x504, with the ECU's own OSD (view buttons, centerline) burned into the
 * pixels. So our guideline overlay is off by default (HwConfig.SHOW_GUIDELINES)
 * and view switching is an ECU command still to be found, not a Camera2 id.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PhoenixCam";
    private static final int REQ_CAM = 1;

    public static final String ACTION_SHOW   = "com.ramphoenix.camera.SHOW";
    public static final String ACTION_HIDE   = "com.ramphoenix.camera.HIDE";
    public static final String EXTRA_REVERSE = "reverse";

    /** The resumed instance, if any. ReverseService drives a visible activity
     *  through this instead of intents (see class doc). */
    private static volatile MainActivity sResumed;
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    public static boolean isVisible() { return sResumed != null; }

    /** Reverse engaged while we are on screen: switch to REAR, mark reverse-initiated. */
    public static void requestShowRear() {
        sMain.post(() -> {
            MainActivity a = sResumed;
            if (a == null) return;
            a.reverseInitiated = true;
            a.setMode(CameraMode.REAR);
        });
    }

    /** Reverse released: step back only if reverse brought us up. */
    public static void requestHide() {
        sMain.post(() -> {
            MainActivity a = sResumed;
            if (a == null) return;
            if (a.reverseInitiated) { a.reverseInitiated = false; a.moveTaskToBack(true); }
        });
    }

    private FrameLayout feedBox;
    private SurfaceView surfaceMain, surfaceAux;
    private GuidelineOverlay guidelines;
    private ParkingSensorView sensors;
    private LinearLayout selector;

    private CameraController camMain, camAux;
    private ParkingSensorSource sensorSource = ParkingSensorSource.STUB;

    private CameraMode mode = CameraMode.REAR;
    private boolean mainSurfaceReady, auxSurfaceReady, permGranted;
    /** True when the current foregrounding was caused by shifting into reverse. */
    private boolean reverseInitiated;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable sensorTicker;

    /** The panel reports 160 dpi (live 2026-10-21) but the UI is designed at 2x;
     *  render this app at HwConfig.UI_DPI so dp/sp come out at the intended size. */
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration c = new Configuration();
        c.densityDpi = HwConfig.UI_DPI;
        applyOverrideConfiguration(c);
    }

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

    /** Compute the letterbox height for the feed's native aspect from the real
     *  panel width and pin both the main feed box and the aux surface to it.
     *  Device 14 is 720x504 (live), i.e. 10:7, not 16:9. */
    private void sizeFeedBoxes() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        android.util.Size n = HwConfig.nativeSize(HwConfig.REAR_CAM_ID);
        int boxH = Math.round(screenW * (float) n.getHeight() / n.getWidth());
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

    /** Idempotent: binds cameras + overlays to whatever the current mode is.
     *  CameraController.open() is itself a no-op for an unchanged id/surface. */
    private void applyMode() {
        if (!permGranted || !mainSurfaceReady) return;

        switch (mode) {
            case REAR:
                setFeedGravity(Gravity.CENTER);
                surfaceAux.setVisibility(View.GONE);
                guidelines.setVisibility(HwConfig.SHOW_GUIDELINES ? View.VISIBLE : View.GONE);
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
        sResumed = this;
        hideSystemBars();
        startSensorTicker();
        applyMode();
    }

    @Override protected void onPause() {
        super.onPause();
        if (sResumed == this) sResumed = null;
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
