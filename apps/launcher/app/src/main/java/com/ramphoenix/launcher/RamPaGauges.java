package com.ramphoenix.launcher;

/** Factory PA packet 82 gauge fields. Oil temperature is cluster-verified;
 * coolant/transmission/pressure mappings are traced from the installed vendor APKs.
 * IPC registration can deliver cached values; callbacks are not a CAN heartbeat. */
final class RamPaGauges {
    static final int VERIFIED_PROFILE = 2163062;
    static final int COOLANT = 234, OIL_TEMPERATURE = 235, TRANSMISSION = 236, OIL_PRESSURE = 237;
    private int profile = -1;
    private final Integer[] raw = new Integer[4];
    private boolean pressureInitialized;

    synchronized void profile(int value) {
        // Preserve initial registration callbacks received before the profile, but
        // never carry readings across an actual profile change in this connection.
        if (profile != -1 && value != profile) clearValues();
        profile = value;
    }
    synchronized void update(int code, int value) {
        if (code < COOLANT || code > OIL_PRESSURE) return;
        raw[code - COOLANT] = value;
        if (code == OIL_PRESSURE) {
            Float pressure = decodePressure(value);
            if (pressure == null) pressureInitialized = false;
            else if (value != 0) pressureInitialized = true;
        }
    }
    private void clearValues() { java.util.Arrays.fill(raw, null); pressureInitialized = false; }
    synchronized void clear() { profile = -1; clearValues(); }
    synchronized boolean supported() { return profile == VERIFIED_PROFILE; }
    static int code(VehicleData.Gauge gauge) {
        switch (gauge) {
            case COOLANT: return COOLANT;
            case TRANSMISSION: return TRANSMISSION;
            case OIL_PRESSURE: return OIL_PRESSURE;
            case OIL_TEMPERATURE: return OIL_TEMPERATURE;
            default: throw new IllegalArgumentException("Unknown gauge");
        }
    }
    synchronized Float value(VehicleData.Gauge gauge, boolean connected) {
        if (!connected || !supported()) return null;
        Integer word = raw[code(gauge) - COOLANT];
        if (word == null) return null;
        if (gauge != VehicleData.Gauge.OIL_PRESSURE) return decodeTemperature(word);
        // Untagged zero is also the vendor's uninitialized cache. After a valid
        // nonzero word (including a tagged zero), a later zero is meaningful.
        if (word == 0 && !pressureInitialized) return null;
        return decodePressure(word);
    }
    synchronized int availableMask(boolean connected) {
        int mask = 0;
        for (VehicleData.Gauge gauge : VehicleData.Gauge.values()) if (value(gauge, connected) != null) mask |= gauge.mask;
        return mask;
    }
    static Float decodeTemperature(int raw) {
        if (raw <= 0 || raw > 0xffff || raw == 0xffff || raw == 0x7fff) return null;
        float value = (raw & 0x7fff) - 40f;
        float c = (raw & 0x8000) != 0 ? (value - 32f) * 5f / 9f : value;
        return c < -40f || c > 250f ? null : c;
    }
    static Float decodePressure(int raw) {
        if (raw < 0 || raw > 0xffff || (raw & 0x3fff) == 0x3fff) return null;
        float value = (raw & 0x3fff) / 10f;
        int units = (raw >>> 14) & 3;
        float psi;
        if (units == 0) psi = value;
        else if (units == 1) psi = value / 6.8947573f;
        else if (units == 2) psi = value * 14.503774f;
        else return null; // vendor UI falls back to psi for 3; no confirmed meaning
        return psi > 250f ? null : psi;
    }
}
