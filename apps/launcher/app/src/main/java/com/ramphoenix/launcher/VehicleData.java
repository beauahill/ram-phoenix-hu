package com.ramphoenix.launcher;

/**
 * Vehicle sensor snapshot for the dashboard. {@code null} = not available.
 *
 * CanbusVehicleData combines verified SYU ToolkitService fields with ObdClient
 * readings. Engine gauges prefer the mapped PA factory IPC fields and fall back
 * individually to OBD. Voltage and TPMS still use OBD; outside temperature uses IPC.
 * See docs/TPMS-2019-RAM3500.md and docs/OIL-DATA-2019-RAM3500.md. Unsupported
 * fields remain null. STUB is an unavailable placeholder, not the live source.
 */
public interface VehicleData {

    enum Gauge {
        COOLANT(1), TRANSMISSION(2), OIL_PRESSURE(4), OIL_TEMPERATURE(8);
        final int mask;
        Gauge(int mask) { this.mask = mask; }
    }
    /** Value/source pair for engine gauges, so source labels cannot be guessed from a non-null value. */
    default GaugeReading reading(Gauge gauge) { return GaugeReading.NONE; }

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
    /** Engine oil temperature in Celsius, or null. */
    Float oilTempC();
    /** Number of open doors / hood / tailgate, or -1 if unknown. */
    int doorsOpen();
    /** Outside temperature in Celsius, or null. */
    Float outsideC();
    /** Vehicle speed from the bus in km/h, or null (dashboard falls back to GPS). */
    Float speedKph();
    /** Engine speed in thousands of rpm (e.g. 2.1), or null. */
    Float rpm();
    /** True when the source is live at all. */
    boolean available();
    default String status(){return available()?"Vehicle readings are available.":"Vehicle data is unavailable.";}
    default boolean hasWarnings(){return !available();}

    VehicleData STUB = new VehicleData() {
        @Override public Float tirePsi(int corner) { return null; }
        @Override public Float fuelPercent() { return null; }
        @Override public Float coolantC() { return null; }
        @Override public Float batteryVolts() { return null; }
        @Override public Float transC() { return null; }
        @Override public Float oilPsi() { return null; }
        @Override public Float oilTempC() { return null; }
        @Override public int doorsOpen() { return -1; }
        @Override public Float outsideC() { return null; }
        @Override public Float speedKph() { return null; }
        @Override public Float rpm() { return null; }
        @Override public boolean available() { return false; }
    };
}
