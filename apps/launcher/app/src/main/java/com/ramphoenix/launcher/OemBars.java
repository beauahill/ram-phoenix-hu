package com.ramphoenix.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Verified FYT SystemUI controls; does not disable SystemUI or any CAN/MCU service. */
final class OemBars {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static long generation;
    private OemBars() {}

    static void keepHidden(Context context) {
        Context app = context.getApplicationContext();
        long current = ++generation;
        hide(app);
        for (long delay : new long[]{300, 1000}) UI.postDelayed(() -> {
            if (current == generation) hide(app);
        }, delay);
    }

    private static void hide(Context app) {
        try {
            // Vendor spelling is intentionally "visibe". Receiver changes only bar UI visibility.
            app.sendBroadcast(new Intent("com.fyt.action.hide_statusbar").setPackage("com.android.systemui")
                    .putExtra("visibe", false));
        } catch (RuntimeException unavailable) {
            Log.w("PhoenixLauncher", "OEM bar control unavailable", unavailable);
        }
    }
}
