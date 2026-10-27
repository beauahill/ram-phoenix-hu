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
    public static final String EXTRA_REVERSE_SESSION = "reverse_session";

    /** The resumed instance, if any. ReverseService drives a visible activity
     *  through this instead of intents (see class doc). */
    private static volatile MainActivity sResumed;
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    public static boolean isVisible() { return sResumed != null; }

    /** A manually opened camera remains manual when the truck enters reverse. */
    public static void requestShowRear(long session) {
        runOnMain(() -> {
            MainActivity a = sResumed;
            if (a == null || !ReverseService.isReverseSessionCurrent(session)) return;
            if (a.reverseInitiated) a.reverseSession = session;
            a.setMode(CameraMode.REAR);
        });
    }

    /** Reverse released: step back only if reverse brought us up. */
    public static void requestHide() {
        runOnMain(() -> {
            MainActivity a = sResumed;
            if (a == null) return;
            if (a.reverseInitiated && !ReverseService.isReverseSessionCurrent(a.reverseSession)) a.hideReverseSession();
        });
    }

    private static void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else sMain.post(action);
    }

    private FrameLayout feedBox, auxBox;
    private View feedMask;
    private android.widget.TextView previewStatus;
    private SurfaceView surfaceMain, surfaceAux;
    private GuidelineOverlay guidelines;
    private ParkingSensorView sensors;
    private LinearLayout selector;

    private CameraController camMain, camAux;
    /** Fed by ReverseService's IPC subscription (same process); see IpcRadarSource. */
    private ParkingSensorSource sensorSource = new IpcRadarSource();

    private CameraMode mode = CameraMode.REAR;
    /** Letterbox height of one feed at the panel width; set by sizeFeedBoxes(). */
    private int feedH;
    private VideoViewport viewport;
    private VideoViewport.Layout videoLayout;
    private boolean mainSurfaceReady, auxSurfaceReady, permGranted;
    /** True when the current foregrounding was caused by shifting into reverse. */
    private boolean reverseInitiated;
    private long reverseSession;
    private boolean resumed;

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
        auxBox      = findViewById(R.id.aux_box);
        feedMask    = findViewById(R.id.feed_mask);
        previewStatus = findViewById(R.id.preview_status);
        surfaceMain = findViewById(R.id.surface_main);
        surfaceAux  = findViewById(R.id.surface_aux);
        guidelines  = findViewById(R.id.guidelines);
        sensors     = findViewById(R.id.sensors);
        selector    = findViewById(R.id.selector);

        camMain = new CameraController(this);
        camMain.setPreviewListener(message -> {
            previewStatus.setText(message);
            previewStatus.setVisibility(message == null ? View.GONE : View.VISIBLE);
        });
        camAux  = new CameraController(this);
        sensors.setSource(sensorSource);

        buildSelector();
        guidelines.setMirror(Sysfs.readInt(HwConfig.NODE_MIRROR, 0) == 1);
        viewport = VideoViewport.parse(propString("persist.phoenix.cam.insets", VideoViewport.DEFAULT_INSETS));
        sizeFeedBoxes(getResources().getDisplayMetrics().widthPixels);
        findViewById(R.id.camera_root).addOnLayoutChangeListener((v, l, t, r, bottom, ol, ot, or, ob) -> {
            int width = r - l;
            if (width > 0 && width != videoLayout.width) sizeFeedBoxes(width);
        });

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
        feedBox.setOnTouchListener(this::forwardTouch);

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
            if (reverseInitiated) hideReverseSession();
            return;
        }
        if (intent.getBooleanExtra(EXTRA_REVERSE, false)) {
            long session = intent.getLongExtra(EXTRA_REVERSE_SESSION, -1);
            if (sResumed != this) {
                reverseInitiated = true;
                reverseSession = session;
            } else if (reverseInitiated && ReverseService.isReverseSessionCurrent(session)) {
                reverseSession = session;
            }
            if (ReverseService.isReverseSessionCurrent(session)) setMode(CameraMode.REAR);
        } else {
            reverseInitiated = false;
        }
    }

    private void hideReverseSession() {
        reverseInitiated = false;
        resumed = false;
        if (sResumed == this) sResumed = null;
        stopSensorTicker();
        camMain.close();
        camAux.close();
        moveTaskToBack(true);
    }

    /** Keep the HAL's full native buffer, but show only the calibrated picture.
     *  The grey top/left/bottom edge is inside that buffer, not another window.
     *  Opaque letterboxing also covers SurfaceView's lower overhang on this HAL;
     *  the parent clip alone does not clip its compositor surface. */
    private void sizeFeedBoxes(int width) {
        videoLayout = viewport.measure(width);
        feedH = videoLayout.height;
        for (FrameLayout box : new FrameLayout[]{feedBox, auxBox}) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams();
            lp.height = feedH;
            box.setLayoutParams(lp);
        }
        for (View picture : new View[]{surfaceMain, surfaceAux, guidelines}) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    videoLayout.surfaceWidth, videoLayout.surfaceHeight, Gravity.TOP | Gravity.LEFT);
            lp.leftMargin = videoLayout.surfaceLeft;
            lp.topMargin = videoLayout.surfaceTop;
            picture.setLayoutParams(lp);
        }
        placeFeeds(mode == CameraMode.SURROUND_360);
        Log.i(TAG, "video viewport " + width + "x" + feedH + " buffer insets="
                + viewport.left + "," + viewport.top + "," + viewport.right + "," + viewport.bottom);
    }

    private void buildSelector() {
        selector.removeAllViews();
        // Live 2026-10-25: devices 10, 12 and 14 all carry the same factory
        // composite, so Front / 360 only ever showed the rear picture again.
        // View switching is done by tapping the module's own on-screen buttons
        // (forwarded as MAIN cmd 56). The selector stays in the code behind
        // persist.phoenix.cam.selector=1 for experiments and is hidden otherwise.
        if (propInt("persist.phoenix.cam.selector", 0) == 0) { selector.setVisibility(View.GONE); return; }
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

    // ---- factory OSD touch forwarding ----
    // The picture is the factory camera module's composite with its own buttons
    // burned in. This truck's canbus profile is a "PA" RAM box (live 2026-10-25),
    // and for CAR_PA_RAM_22_H the stock app forwards taps on the picture as
    //   CANBUS cmd 1029 {touch, x, y}   touch 0 = down, 1 = up   (com.syu.us
    //   PageBack.handleCanbusSendTouch -> FuncSet.send360Touch)
    // with x, y as raw pixels inside its video view. Our feed is letterboxed, so
    // the coordinate space is selectable live until one is proven:
    //   setprop persist.phoenix.cam.touch N     (default 4: proven live 2026-10-25, all OSD buttons respond)
    //     0 off
    //     1 CANBUS 1029, px inside the feed box            (feed 1080 x 756)
    //     2 CANBUS 1029, raw panel px                       (1080 x 1920 portrait)
    //     3 CANBUS 1029, feed scaled to 1920 x 1080
    //     4 CANBUS 1029, feed scaled to 1024 x 600
    //     5 MAIN 56 {action, x, y}, feed scaled to 800 x 480 (the MCU/360 path)
    private static final int CMD_TOUCH_MAIN = 56, CMD_TOUCH_CANBUS = 1029;
    private long lastMoveMs;

    private boolean forwardTouch(View v, android.view.MotionEvent e) {
        if (previewStatus.getVisibility() == View.VISIBLE) return true;
        int mode = propInt("persist.phoenix.cam.touch", 4);
        if (mode == 0) return false;
        int action = e.getActionMasked();
        boolean move = action == android.view.MotionEvent.ACTION_MOVE;
        if (action != android.view.MotionEvent.ACTION_DOWN && action != android.view.MotionEvent.ACTION_UP && !move) return false;
        if (move && mode != 5) return true;                 // the canbus path sends down/up only
        if (move) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastMoveMs < 50) return true;
            lastMoveMs = now;
        }
        float fx = e.getX(), fy = e.getY();
        int w = Math.max(1, v.getWidth()), h = Math.max(1, v.getHeight());
        // OSD coordinates refer to the FULL encoded frame. Undo the display crop
        // before converting to the proven 1024x600 factory touch coordinate space.
        float nx = videoLayout.bufferX(fx), ny = videoLayout.bufferY(fy);
        int x, y;
        switch (mode) {
            case 2:  x = Math.round(e.getRawX());    y = Math.round(e.getRawY());    break;
            case 3:  x = Math.round(nx * 1920f); y = Math.round(ny * 1080f); break;
            case 4:  x = Math.round(nx * 1024f); y = Math.round(ny * 600f);  break;
            case 5:  x = Math.round(nx * 800f);  y = Math.round(ny * 480f);  break;
            default: x = Math.round(nx * w);
                     y = Math.round(ny * w * VideoViewport.BUFFER_HEIGHT / VideoViewport.BUFFER_WIDTH); break;
        }
        int a = action == android.view.MotionEvent.ACTION_DOWN ? 0 : action == android.view.MotionEvent.ACTION_UP ? 1 : 2;
        boolean ok = mode == 5
                ? ReverseService.ipcCmd(SyuIpc.MODULE_MAIN, CMD_TOUCH_MAIN, new int[]{a, x, y})
                : ReverseService.ipcCmd(SyuIpc.MODULE_CANBUS, CMD_TOUCH_CANBUS, new int[]{a, x, y});
        if (!move) {
            Log.i(TAG, "osd touch mode=" + mode + " a=" + a + " (" + x + "," + y + ") from (" + Math.round(fx) + "," + Math.round(fy)
                    + ") of " + w + "x" + h + (ok ? "" : " NOT SENT"));
        }
        return true;
    }

    private static int propInt(String key, int def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String s = (String) sp.getMethod("get", String.class, String.class).invoke(null, key, "");
            return (s == null || s.isEmpty()) ? def : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String propString(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String s = (String) sp.getMethod("get", String.class, String.class).invoke(null, key, "");
            return s == null || s.trim().isEmpty() ? def : s;
        } catch (Exception e) {
            return def;
        }
    }

    /** Idempotent: binds cameras + overlays to whatever the current mode is.
     *  CameraController.open() is itself a no-op for an unchanged id/surface. */
    private void applyMode() {
        if (!resumed || !permGranted || !mainSurfaceReady) return;

        switch (mode) {
            case REAR:
                placeFeeds(false);
                auxBox.setVisibility(View.GONE);
                guidelines.setVisibility(HwConfig.SHOW_GUIDELINES ? View.VISIBLE : View.GONE);
                camAux.close();
                camMain.open(HwConfig.REAR_CAM_ID, surfaceMain.getHolder());
                break;
            case FRONT:
                placeFeeds(false);
                auxBox.setVisibility(View.GONE);
                guidelines.setVisibility(View.GONE);
                camAux.close();
                camMain.open(HwConfig.FRONT_CAM_ID, surfaceMain.getHolder());
                break;
            case SURROUND_360:
                placeFeeds(true);
                guidelines.setVisibility(View.GONE);
                auxBox.setVisibility(View.VISIBLE);
                camMain.open(HwConfig.REAR_CAM_ID, surfaceMain.getHolder());
                if (auxSurfaceReady) {
                    camAux.open(HwConfig.SURROUND_AUX_ID, surfaceAux.getHolder());
                }
                break;
        }
        sensors.setVisibility(View.VISIBLE);
        highlightSelector();
    }

    /**
     * Feeds hug the top of the panel (Beau, live 2026-10-25: a centred feed sat too
     * low for a quick glance). Single-feed modes put the main box at the top; 360
     * keeps the aux feed on top with the main feed directly under it. The selector
     * drops to just below whatever is showing so it never covers the picture.
     */
    private void placeFeeds(boolean stacked) {
        FrameLayout.LayoutParams maskLp = (FrameLayout.LayoutParams) feedMask.getLayoutParams();
        maskLp.topMargin = feedH * (stacked ? 2 : 1);
        feedMask.setLayoutParams(maskLp);
        if (feedBox != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) feedBox.getLayoutParams();
            lp.gravity = Gravity.TOP;
            lp.topMargin = stacked ? feedH : 0;
            feedBox.setLayoutParams(lp);
        }
        if (selector != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) selector.getLayoutParams();
            // Two stacked feeds leave no room underneath for a
            // vertical selector, so park it bottom-right there (live 2026-10-25: the
            // 360 button clipped off the panel when it was placed below both feeds).
            lp.gravity = stacked ? (Gravity.BOTTOM | Gravity.END) : (Gravity.TOP | Gravity.END);
            lp.topMargin = stacked ? 0 : feedH;
            selector.setLayoutParams(lp);
        }
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
        // A quick R -> D may happen before this activity reaches onResume().
        // requestHide() cannot see us then, so reconcile before opening a camera.
        if (reverseInitiated && !ReverseService.isReverseSessionCurrent(reverseSession)) {
            hideReverseSession();
            return;
        }
        resumed = true;
        sResumed = this;
        hideSystemBars();
        startSensorTicker();
        applyMode();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
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
