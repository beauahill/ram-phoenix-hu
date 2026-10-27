package com.ramphoenix.launcher;

/** Strict, bounded odometer decoders. Manufacturer candidates require cluster confirmation. */
final class ObdMileage {
    static final long POLL_MS=30_000, FRESH_MS=65_000;
    static final String STANDARD="7E0:01A6", RAM="7E0:2203BC";
    final double miles;
    final String source;
    final long elapsed;
    private ObdMileage(double miles,String source,long elapsed) {this.miles=miles;this.source=source;this.elapsed=elapsed;}
    static ObdMileage decode(String source,String response,long elapsed) {
        if(response==null)return null;
        String prefix=STANDARD.equals(source)?"41A6":RAM.equals(source)?"6203BC":null;
        if(prefix==null)return null;
        String hex=response.replaceAll("\\s","").toUpperCase(java.util.Locale.US);
        // Physical response route, exactly four data bytes; no substring match across frames.
        if(!hex.matches(prefix+"[0-9A-F]{8}"))return null;
        long raw=Long.parseLong(hex.substring(prefix.length()),16);
        // This app targets a used 2019 RAM; a zero-filled reply is not its odometer.
        if(raw==0xFFFFFFFFL || raw==0)return null;
        double miles=(raw/(STANDARD.equals(source)?10d:1000d))/1.609344d;
        if(!Double.isFinite(miles) || miles<0 || miles>MaintenanceInput.MAX_MILES)return null;
        return new ObdMileage(miles,source,elapsed);
    }
    boolean fresh(long now) {return now>=elapsed && now-elapsed<=FRESH_MS;}
}
