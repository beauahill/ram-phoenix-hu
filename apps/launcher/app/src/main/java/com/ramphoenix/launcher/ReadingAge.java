package com.ramphoenix.launcher;

/** Monotonic leases: a connected socket or a repeated cache value is not new data. */
final class ReadingAge {
    static final long NONE=Long.MIN_VALUE,OBD_MS=30000,CAN_MS=120000;
    private ReadingAge(){}
    static boolean fresh(long at,long now,long limit){return at>=0 && now>=at && now-at<=limit;}
    static String elapsed(long at,long now){return at<0||now<at?"not confirmed":((now-at)/1000)+" s ago";}
}
