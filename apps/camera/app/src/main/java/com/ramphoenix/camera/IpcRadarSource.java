package com.ramphoenix.camera;

import android.os.SystemClock;

/**
 * Parking-sensor source fed by the SYU ToolkitService (MODULE_MAIN pushes).
 *
 * Pinned live on the truck, 2026-10-25, with the 400-code sweep in ReverseMonitor:
 *   MAIN 14..17  FRONT sensors (corrected live, see constants), one int each, 0..10 where 10 = clear. With a wall
 *                close behind the tailgate all four flipped 2 <-> 10 at ~2 Hz, the
 *                same cadence as the truck's own chime (the module blinks the value,
 *                it is not a glitch). Order assumed left -> right; VERIFY by walking
 *                one corner.
 *   MAIN 18..21  REAR sensors, same scale. Parked nose-in to a wall the centre
 *                pair (19, 20) read 2 steady in R and 10 out of R while 18 and 21
 *                stayed 10, matching the cluster (centre front arcs lit). Not
 *                blinked by the module the way the rear set is.
 *   MAIN 90..97  sat at 10 the whole session; unknown, not mapped.
 *   MAIN 22      is a single on/off flag, not the distances (U_BACKCAR_RADAR).
 *
 * ReverseMonitor subscribes and calls {@link #onMainUpdate} from a binder thread;
 * the activity's ParkingSensorView reads {@link #proximity} on the UI thread.
 * Because the module blinks a close value, each zone holds its last non-clear
 * reading for HOLD_MS so the strip shows a steady red instead of strobing at the
 * module's rate (the view adds its own flash for red).
 */
public final class IpcRadarSource implements ParkingSensorSource {

    // Live 2026-10-25, cluster side by side with ours: 14..17 light with the
    // FRONT arcs on the dash and 18..21 with the REAR arcs (the first guess had
    // them the other way round). Order within an end is left -> right.
    public static final int FRONT_FIRST = 14;   // .. 17
    public static final int REAR_FIRST  = 18;   // .. 21
    public static final int RAW_CLEAR   = 10;
    private static final long HOLD_MS   = 700;

    private static final Object lock = new Object();
    private static final float[] cur    = new float[ZONE_COUNT];
    private static final float[] held   = new float[ZONE_COUNT];
    private static final long[]  heldAt = new long[ZONE_COUNT];
    private static volatile boolean seen;
    private static int seenMask;

    /** No last-known reading may look live after the vendor transport is lost. */
    public static void reset() {
        synchronized (lock) {
            java.util.Arrays.fill(cur, 0f);
            java.util.Arrays.fill(held, 0f);
            java.util.Arrays.fill(heldAt, 0L);
            seen = false;
            seenMask = 0;
        }
    }

    /** Zone index for a MAIN update code, or -1 if the code is not a sensor. */
    public static int zoneFor(int code) {
        if (code >= REAR_FIRST && code < REAR_FIRST + 4)   return RL + (code - REAR_FIRST);
        if (code >= FRONT_FIRST && code < FRONT_FIRST + 4) return FL + (code - FRONT_FIRST);
        return -1;
    }

    /** Feed a MAIN push. Ignores codes that are not sensors. Any thread. */
    public static void onMainUpdate(int code, int[] ints) {
        int z = zoneFor(code);
        if (z < 0 || ints == null || ints.length == 0) return;
        int raw = Math.max(0, Math.min(RAW_CLEAR, ints[0]));
        float p = (RAW_CLEAR - raw) / (float) RAW_CLEAR;   // 10 -> 0 clear, 0 -> 1 touching
        synchronized (lock) {
            cur[z] = p;
            if (p > 0f) { held[z] = p; heldAt[z] = SystemClock.uptimeMillis(); }
            seenMask |= 1 << z;
            seen = seenMask == (1 << ZONE_COUNT) - 1;
        }
    }

    @Override public float[] proximity() {
        float[] out = new float[ZONE_COUNT];
        long now = SystemClock.uptimeMillis();
        synchronized (lock) {
            for (int z = 0; z < ZONE_COUNT; z++) {
                boolean hold = now - heldAt[z] < HOLD_MS;
                out[z] = hold ? Math.max(cur[z], held[z]) : cur[z];
            }
        }
        return out;
    }

    @Override public boolean available() { return seen; }
}
