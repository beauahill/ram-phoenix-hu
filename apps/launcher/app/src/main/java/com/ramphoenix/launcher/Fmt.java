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
        return v == null ? NONE : String.valueOf(Math.round(v));
    }
}
