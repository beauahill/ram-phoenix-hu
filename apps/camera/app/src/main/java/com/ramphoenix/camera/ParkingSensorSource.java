package com.ramphoenix.camera;

/**
 * Supplies parking-sensor (ultrasonic radar) proximity per zone, 0.0 (clear) ..
 * 1.0 (object touching). On this truck those distances arrive over the SYU
 * canbus LSEC IPC via com.syu.us (setVisibility_CarRadar). The exact Main.DATA[]
 * index is not yet pinned (open item), so the default implementation is a stub
 * that reports "clear"; CanbusRadarSource will replace it once the index is
 * captured live (watch IPC while approaching an object).
 */
public interface ParkingSensorSource {

    // 8 zones, matching typical front/rear 4+4 ultrasonic layouts.
    int FL = 0, FCL = 1, FCR = 2, FR = 3;   // front: left, center-left, center-right, right
    int RL = 4, RCL = 5, RCR = 6, RR = 7;   // rear:  left, center-left, center-right, right
    int ZONE_COUNT = 8;

    /** @return proximity 0..1 for each of ZONE_COUNT zones (1 = closest). */
    float[] proximity();

    /** @return true if the sensor system is reporting at all (vs. no data). */
    boolean available();

    /** Null-object: no data, everything clear. */
    ParkingSensorSource STUB = new ParkingSensorSource() {
        private final float[] zero = new float[ZONE_COUNT];
        @Override public float[] proximity() { return zero; }
        @Override public boolean available() { return false; }
    };
}
