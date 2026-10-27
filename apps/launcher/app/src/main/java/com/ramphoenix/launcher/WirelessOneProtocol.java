package com.ramphoenix.launcher;

import java.util.UUID;

/** Wire facts observed in the signed WirelessOne 2.3.0 reference, not a public SDK.
 * Deliberately supports only ordinary pressure targets. No firmware, tank, safety,
 * diagnostics-clear or manifold-preset writes are implemented. */
final class WirelessOneProtocol {
    static final String PACKAGE = "com.airlift.wirelessone";
    static final String REFERENCE_SIGNER = "f312260b3a1fbf1daa0f9da3a471be9e111f15a4713fd5b19867277549f600cd";
    static final UUID CONTROL = uuid(0xfb00), SETTINGS = uuid(0xfc00), BOOT = uuid(0xfa00);
    static final UUID STATE = uuid(0xfb01), FAULTS = uuid(0xfb04), TARGET = uuid(0xfc10);
    static final UUID MODE = uuid(0xfa01), TANK = uuid(0xfc56);
    static final int MIN_KPA = 35, MAX_KPA = 690;
    static final double KPA_PER_PSI = 6.89476;

    static UUID uuid(int value) { return UUID.fromString(String.format(java.util.Locale.US,
            "0000%04x-0000-1000-8000-00805f9b34fb", value)); }

    static int kpa(int psi) {
        if (psi < 5 || psi > 100) throw new IllegalArgumentException("Outside WirelessOne pressure range");
        return Math.max(MIN_KPA, Math.min(MAX_KPA, (int)Math.rint(psi * KPA_PER_PSI)));
    }

    static double psi(int kpa) { return kpa / KPA_PER_PSI; }

    static byte[] target(int psi, int kitMinimum, int kitMaximum) {
        if (!validLimits(kitMinimum, kitMaximum) || psi < kitMinimum || psi > kitMaximum)
            throw new IllegalArgumentException("Outside the air-spring kit limits");
        int pressure = kpa(psi);
        // Little-endian uint16 kPa and preset 0 = manual target. Does not overwrite P1/P2/P3.
        return new byte[]{(byte)pressure, (byte)(pressure >>> 8), 0};
    }

    static boolean validLimits(int minimum, int maximum) {
        return minimum >= 5 && maximum <= 100 && minimum <= maximum;
    }

    static void length(byte[] value, int minimum) {
        if (value == null || value.length < minimum || value.length > 20)
            throw new IllegalArgumentException("Unsupported WirelessOne packet");
    }

    static int pressure(byte[] value, int offset) {
        length(value, offset + 2);
        int kpa = (value[offset] & 255) | ((value[offset + 1] & 255) << 8);
        if (kpa == 65535 || kpa > 1400) throw new IllegalArgumentException("Unavailable pressure");
        return kpa;
    }

    static int targetPressure(byte[] value) {
        length(value, 3);
        int pressure = pressure(value, 0);
        if (pressure < MIN_KPA || pressure > MAX_KPA || (value[2] & 255) > 3)
            throw new IllegalArgumentException("Unsupported target setting");
        return pressure;
    }

    static int presetPsi(int index, int minimum) {
        switch (index) {
            case 0: return 80;
            case 1: return 50;
            case 2: return 30;
            case 3: return Math.max(5, minimum);
            default: throw new IllegalArgumentException("Unknown profile");
        }
    }
    static final String[] NAMES = {"Full Load & Trailer", "Light Trailer", "Light Load", "Empty"};
}
