package com.ramphoenix.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

/**
 * The whole camera UI. Portrait, fullscreen, immersive.
 *
 * Triggers:
 *   - reverse gear  -> ReverseMonitor fires -> REAR view, instant.
 *   - manual (launcher / com.ramphoenix.camera.SHOW intent / view buttons) -> last/selected view.
 *
 * View modes (per Beau's photo): Rear, Front, 360 (rear + overhead aux).
 * The 360 "overhead" panel currently shows the second ISP input (dev 10); true
 * stitched bird's-eye is a later milestone.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PhoenixCam";
    private static final int REQ_CAM = 1;

    private SurfaceView surfaceMain, surfaceAux;
    private GuidelineOverlay guidelines;
    private ParkingSensorView sensors;
    private LinearLayout selector;

    private CameraController camMain, camAux;
    private ReverseMonitor reverse;
    private ParkingSensorSource sensorSource = ParkingSensorSource.STUB;

    private CameraMode mode = CameraMode.REAR;
    private boolean mainSurfaceReady, auxSurfaceReady, permGranted;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.activity_camera);

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
            if (engaged) { setMode(CameraMode.REAR); bringToFront(); }
        });

        permGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!permGranted) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
        }

        startSensorTicker();
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
                surfaceAux.setVisibility(View.GONE);
                guidelines.setVisibility(View.VISIBLE);
                camAux.close();
                camMain.open(HwConfig.REAR_CAM_ID, surfaceMain.getHolder().getSurface());
                break;
            case FRONT:
                surfaceAux.setVisibility(View.GONE);
                guidelines.setVisibility(View.GONE);
                camAux.close();
                camMain.open(HwConfig.FRONT_CAM_ID, surfaceMain.getHolder().getSurface());
                break;
            case SURROUND_360:
                guidelines.setVisibility(View.GONE);
                surfaceAux.setVisibility(View.VISIBLE);
                camMain.open(HwConfig.REAR_CAM_ID, surfaceMain.getHolder().getSurface());
                if (auxSurfaceReady) {
                    camAux.open(HwConfig.SURROUND_AUX_ID, surfaceAux.getHolder().getSurface());
                }
                break;
        }
        sensors.setVisibility(View.VISIBLE);
        highlightSelector();
    }

    private void highlightSelector() {
        for (int i = 0; i < selector.getChildCount(); i++) {
            Button b = (Button) selector.getChildAt(i);
            b.setSelected(CameraMode.values()[i] == mode);
            b.setAlpha(CameraMode.values()[i] == mode ? 1f : 0.6f);
        }
    }

    private void bringToFront() {
        ui.post(() -> {
            getWindow().getDecorView().setVisibility(View.VISIBLE);
            // singleInstance + already running = just ensure we're foreground
        });
    }

    private void startSensorTicker() {
        ui.post(new Runnable() {
            @Override public void run() {
                sensors.tick();
                ui.postDelayed(this, 66); // ~15fps for flashing
            }
        });
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
        applyMode();
    }

    @Override protected void onPause() {
        super.onPause();
        if (reverse != null) reverse.stop();
        camMain.close();
        camAux.close();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
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
