package com.ramphoenix.launcher;

/**
 * Vehicle sensor snapshot for the dashboard. {@code null} = not available.
 *
 * On this unit these values ride the SYU canbus LSEC IPC (the shared
 * {@code Main.DATA[]} array published by com.syu.canbus; see
 * docs/CAMERA-MILESTONE-1.md). The exact indexes for tire pressure, fuel,
 * coolant, battery, outside temp and speed are not pinned yet, so the
 * dashboard ships with {@link #STUB} and renders dashes. A CanbusVehicleData
 * implementation replaces it once the indexes are captured live.
 */
public interface VehicleData {

    int FL = 0, FR = 1, RL = 2, RR = 3;

    /** Tire pressure in psi for a corner, or null. */
    Float tirePsi(int corner);
    /** Fuel level 0..100, or null. */
    Float fuelPercent();
    /** Coolant temperature in Celsius, or null. */
    Float coolantC();
    /** Battery voltage, or null. */
    Float batteryVolts();
    /** Transmission fluid temperature in Celsius, or null. */
    Float transC();
    /** Engine oil pressure in psi, or null. */
    Float oilPsi();
    /** Outside temperature in Celsius, or null. */
    Float outsideC();
    /** Vehicle speed from the bus in km/h, or null (dashboard falls back to GPS). */
    Float speedKph();
    /** Engine speed in thousands of rpm (e.g. 2.1), or null. */
    Float rpm();
    /** True when the source is live at all. */
    boolean available();

    VehicleData STUB = new VehicleData() {
        @Override public Float tirePsi(int corner) { return null; }
        @Override public Float fuelPercent() { return null; }
        @Override public Float coolantC() { return null; }
        @Override public Float batteryVolts() { return null; }
        @Override public Float transC() { return null; }
        @Override public Float oilPsi() { return null; }
        @Override public Float outsideC() { return null; }
        @Override public Float speedKph() { return null; }
        @Override public Float rpm() { return null; }
        @Override public boolean available() { return false; }
    };
}
