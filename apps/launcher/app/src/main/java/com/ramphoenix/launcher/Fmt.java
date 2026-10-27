package com.ramphoenix.launcher;

import java.util.Locale;

/** Display formatting for vehicle values; null means "no data" and renders as an em dash. */
final class Fmt {
    private Fmt() {}
    static final String NONE = "—";

    static String percent(Float v) {
        return v == null ? NONE : String.format(Locale.US, "%d%%", Math.round(v));
    }
    static String tempF(Float c) {
        return c == null ? NONE : String.format(Locale.US, "%d°", Math.round(c * 9f / 5f + 32f));
    }
    static String volts(Float v) {
        return v == null ? NONE : String.format(Locale.US, "%.1fV", v);
    }
    static String psi(Float v) {
        // The 2019 RAM cluster displays whole psi by truncation. Keep the raw
        // decimal pressure in VehicleData; quantize only the visible label.
        return v == null || !Float.isFinite(v) ? NONE : String.valueOf((int) Math.floor(v));
    }
}
