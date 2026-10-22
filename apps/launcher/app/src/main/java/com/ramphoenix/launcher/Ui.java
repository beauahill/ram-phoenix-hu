package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.res.Configuration;

/** UI-wide constants. */
final class Ui {
    private Ui() {}
    /** The panel reports 160 dpi (live 2026-10-21) but every layout here is
     *  designed at 2x (1080 px = 540 dp). Render at this density instead. */
    static final int DPI = 320;

    /** Call from Activity.attachBaseContext(), after super. */
    static void applyDensity(Activity a) {
        Configuration c = new Configuration();
        c.densityDpi = DPI;
        a.applyOverrideConfiguration(c);
    }
}
