package com.ramphoenix.launcher;

/** Pressure profile verified on Beau's 2019 RAM 3500 SRW, 2026-10-26. */
final class RamHdTpms {
    static final String ROUTE = "740/4C0";
    static final String[] REQUESTS = {"22A0F1", "22A0F2", "22A0F3", "22A0F4"};
    static final long POLL_MS = 15_000, MAX_AGE_MS = 45_000;
    private static final float PSI_PER_KPA = 0.145037738f;

    private RamHdTpms() {}

    /** Request order is FL, FR, RL, RR on this truck (not the older 2017 example). */
    static Snapshot decode(String validity, String[] replies, long nowMs) {
        byte[] flags = ObdPayload.data(validity, "62A0A5");
        // All four are verified valid (0x0F). Per-corner invalid-bit mapping has
        // not been tested, so do not publish a partial validity assumption.
        if (flags == null || flags.length < 1 || (flags[0] & 0x0F) != 0x0F
                || replies == null || replies.length != 4) return null;
        Float[] psi = new Float[4];
        for (int corner = 0; corner < 4; corner++) {
            byte[] data = ObdPayload.data(replies[corner], "62" + REQUESTS[corner].substring(2));
            if (data == null || data.length < 2) continue;
            int raw = (data[0] & 255) * 256 + (data[1] & 255);
            if (raw != 0xFFFF) psi[corner] = raw / 10f * PSI_PER_KPA;
        }
        return new Snapshot(psi, nowMs);
    }

    static final class Snapshot {
        private final Float[] psi;
        private final long capturedAt;

        private Snapshot(Float[] psi, long capturedAt) { this.psi = psi.clone(); this.capturedAt = capturedAt; }

        Float tirePsi(int corner, long nowMs) {
            if (corner < 0 || corner >= 4 || nowMs < capturedAt || nowMs - capturedAt >= MAX_AGE_MS) return null;
            return psi[corner];
        }
    }
}
