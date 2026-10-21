package com.ramphoenix.camera;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/**
 * Polls /sys/lsecdcamctl/fyt_car_reverse on a background thread and fires a
 * callback (on the main thread) the instant reverse engages / disengages.
 *
 * Polling a world-readable sysfs int at ~80ms is cheap and far more reliable
 * than binding into the SYU canbus IPC, and it survives SYU service restarts.
 * (The canbus IPC U_BACKCAR path remains available as a future secondary trigger.)
 */
public class ReverseMonitor {

    public interface Listener {
        void onReverseChanged(boolean engaged);
    }

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private volatile boolean running;
    private boolean lastState;

    public ReverseMonitor(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        lastState = Sysfs.isReverseEngaged();
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
    }

    public boolean isEngaged() {
        return lastState;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            final boolean now = Sysfs.isReverseEngaged();
            if (now != lastState) {
                lastState = now;
                main.post(() -> listener.onReverseChanged(now));
            }
            if (running && bg != null) bg.postDelayed(this, HwConfig.REVERSE_POLL_MS);
        }
    };
}
