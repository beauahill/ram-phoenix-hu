package com.ramphoenix.camera;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

/**
 * Reverse-gear trigger. Fires the listener (on the main thread) the instant
 * reverse engages / disengages.
 *
 * Primary source: the SYU ToolkitService IPC, MODULE_MAIN update 12 (U_BACKCAR,
 * ints[0]==1). This is exactly what the stock com.syu.doublecamera subscribes to,
 * and it is emitted by the canbus layer ~0.8s after the GPIO reverse line goes
 * high regardless of which camera apps are installed or disabled.
 *
 * Secondary source: /sys/lsecdcamctl/fyt_car_reverse, polled. PROVEN DEAD on the
 * truck with the stock camera apps disabled (2026-10-21: ~7000 reads, never
 * left 0 while the GPIO + backcar path fired cleanly twice). It is written by a
 * stock app, not the kernel. Kept only as an OR so nothing regresses if a
 * future firmware does drive it; it costs one tiny read every 80ms.
 */
public class ReverseMonitor {

    private static final String TAG = "PhoenixCam";

    public interface Listener {
        void onReverseChanged(boolean engaged);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private SyuIpc ipc;
    private volatile boolean running;
    private volatile boolean ipcState;
    private volatile boolean sysfsState;
    private volatile boolean lastState;
    private volatile boolean ipcSeeded;

    public ReverseMonitor(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        sysfsState = Sysfs.isReverseEngaged();
        lastState = sysfsState;

        ipc = new SyuIpc(ctx);
        ipc.setConnectionListener(connected -> {
            Log.i(TAG, "reverse ipc " + (connected ? "up" : "down"));
            if (connected && !ipcSeeded) {
                // register(update=1) should push the current value; if the
                // service does not, read it once so boot-in-reverse still works.
                main.postDelayed(() -> {
                    if (ipcSeeded || !running) return;
                    int[] v = ipc.getInts(SyuIpc.MODULE_MAIN, SyuIpc.U_BACKCAR);
                    if (v != null && v.length > 0) {
                        Log.i(TAG, "reverse ipc seeded via get -> " + v[0]);
                        onIpc(v[0] == 1);
                    }
                }, 1000);
            }
        });
        ipc.subscribe(SyuIpc.MODULE_MAIN, SyuIpc.U_BACKCAR, (code, ints, flts, strs) -> {
            if (ints != null && ints.length > 0) onIpc(ints[0] == 1);
        });
        // Diagnostic (live 2026-10-25): the parking-sensor strip is still a stub
        // because the radar slot is unpinned. Log the raw radar / steer pushes so
        // the slot and encoding can be read straight out of logcat while reversing
        // toward an obstacle. Harmless to leave on; one line per push.
        // Parking sensors (pinned live 2026-10-25 by sweeping MAIN/CANBUS 0..199):
        // MAIN 14..17 front, 18..21 rear (live-corrected). See IpcRadarSource.
        SyuIpc.UpdateListener radar = (code, ints, flts, strs) -> {
            IpcRadarSource.onMainUpdate(code, ints);
            Log.d(TAG, "radar " + code + " -> " + java.util.Arrays.toString(ints));
        };
        for (int c = 14; c < 22; c++) {
            ipc.subscribe(SyuIpc.MODULE_MAIN, c, radar);
        }
        // Steering (live 2026-10-25): ints = {position, 168, 84}; 84 is centre, swept
        // 23..119 lock to lock. Logged at debug until the guidelines consume it.
        ipc.subscribe(SyuIpc.MODULE_MAIN, SyuIpc.U_STEER_ANGLE_INFO, (code, ints, flts, strs) ->
                Log.d(TAG, "steer " + java.util.Arrays.toString(ints)));
        // MAIN 77 pushes {down/up, x, y} for every touch on the panel, whichever app is
        // in front (seen 2026-10-25 on the launcher too). The vendor layer therefore
        // sees taps on the factory OSD buttons; what it sends the camera module for
        // them is still the open question. Logged for correlation, not consumed.
        ipc.subscribe(SyuIpc.MODULE_MAIN, 77, (code, ints, flts, strs) ->
                Log.i(TAG, "MAIN 77 touch " + java.util.Arrays.toString(ints)));
        ipc.connect();

        thread = new HandlerThread("reverse-monitor");
        thread.start();
        bg = new Handler(thread.getLooper());
        bg.post(poll);
    }

    public void stop() {
        running = false;
        if (bg != null) bg.removeCallbacks(poll);
        if (thread != null) thread.quitSafely();
        bg = null;
        thread = null;
        if (ipc != null) { ipc.disconnect(); ipc = null; }
    }

    public boolean isEngaged() {
        return lastState;
    }

    /** Raw command on the shared IPC (touch forwarding, probes). False if not connected. */
    public boolean cmd(int module, int code, int[] ints) {
        return ipc != null && ipc.isConnected() && ipc.cmd(module, code, ints);
    }

    public boolean isIpcConnected() {
        return ipc != null && ipc.isConnected();
    }

    private void onIpc(boolean engaged) {
        ipcSeeded = true;
        ipcState = engaged;
        Log.i(TAG, "U_BACKCAR -> " + (engaged ? 1 : 0));
        publish();
    }

    private synchronized void publish() {
        final boolean now = ipcState || sysfsState;
        if (now != lastState) {
            lastState = now;
            main.post(() -> listener.onReverseChanged(now));
        }
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            final boolean now = Sysfs.isReverseEngaged();
            if (now != sysfsState) {
                sysfsState = now;
                Log.i(TAG, "sysfs fyt_car_reverse -> " + (now ? 1 : 0));
                publish();
            }
            if (running && bg != null) bg.postDelayed(this, HwConfig.REVERSE_POLL_MS);
        }
    };
}
