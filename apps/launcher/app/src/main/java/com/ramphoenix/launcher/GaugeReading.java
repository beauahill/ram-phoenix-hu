package com.ramphoenix.launcher;

/** A gauge value and the source actually selected for that value. */
public final class GaugeReading {
    public enum Source { CAN, OBD, UNAVAILABLE }
    static final GaugeReading NONE = new GaugeReading(null, Source.UNAVAILABLE);
    public final Float value;
    public final Source source;
    private GaugeReading(Float value, Source source) { this.value = value; this.source = source; }
    static GaugeReading choose(Float can, Float obd) {
        if (can != null && Float.isFinite(can)) return new GaugeReading(can, Source.CAN);
        if (obd != null && Float.isFinite(obd)) return new GaugeReading(obd, Source.OBD);
        return NONE;
    }
    String label() { return source == Source.UNAVAILABLE ? "—" : source.name(); }
    static String summary(GaugeReading... readings) {
        boolean can = false, obd = false;
        for (GaugeReading reading : readings) {
            can |= reading.source == Source.CAN; obd |= reading.source == Source.OBD;
        }
        return can ? (obd ? "GAUGES: CAN + OBD" : "GAUGES: CAN") : obd ? "GAUGES: OBD" : "GAUGES UNAVAILABLE";
    }
}
