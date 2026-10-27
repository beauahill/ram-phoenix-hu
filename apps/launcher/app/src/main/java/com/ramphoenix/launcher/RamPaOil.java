package com.ramphoenix.launcher;

/** Factory PA RAM oil-temperature feed, verified on the 2019 3500 SRW. */
final class RamPaOil {
    static final int VERIFIED_PROFILE = 2163062; // PA_RAM_22_Low, actual HU setting.
    static final int TEMPERATURE_CODE = 235;
    private int profile = -1;
    private Integer raw;

    synchronized void profile(int value) { profile = value; }
    synchronized void temperature(int value) { raw = value; }
    synchronized void clear() { profile = -1; raw = null; }
    synchronized boolean supported() { return profile == VERIFIED_PROFILE; }

    synchronized Float temperatureC(boolean connected) {
        if (!connected || !supported() || raw == null) return null;
        return decodeTemperature(raw);
    }

    static Float decodeTemperature(int raw) {
        // Zero is also the vendor's uninitialized cache default; do not show -40.
        if (raw <= 0 || raw > 0xFFFF || raw == 0xFFFF || raw == 0x7FFF) return null;
        float value = (raw & 0x7FFF) - 40f;
        float c = (raw & 0x8000) != 0 ? (value - 32f) * 5f / 9f : value;
        return c < -40f || c > 250f ? null : c;
    }
}
