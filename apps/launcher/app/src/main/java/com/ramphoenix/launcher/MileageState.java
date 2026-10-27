package com.ramphoenix.launcher;

/** Source selection and one-time confirmation of manufacturer-specific mileage. */
final class MileageState {
    int automatic=-1,manual=-1,candidate=-1;
    long automaticAt,manualAt,candidateAt;
    String source="",candidateSource="",confirmedSource="";
    boolean manualOnly;
    void observe(ObdMileage reading,long now) {
        int miles=(int)Math.floor(reading.miles+1e-8);
        boolean trusted=ObdMileage.STANDARD.equals(reading.source)||confirmedSource.equals(reading.source);
        double plausibleIncrease=3+Math.max(0,now-automaticAt)/3_600_000d*200;
        boolean plausible=automatic<0 || (miles>=automatic && (automaticAt<=0 || miles-automatic<=plausibleIncrease));
        if(trusted && plausible) {
            automatic=miles;automaticAt=now;source=reading.source;
            if(candidateSource.equals(reading.source)) {candidate=-1;candidateAt=0;candidateSource="";}
        } else {candidate=miles;candidateAt=now;candidateSource=reading.source;}
    }
    boolean candidateRecent(long now) {return candidate>=0 && now>=candidateAt && now-candidateAt<=300_000;}
    void confirm(long now) {
        if(!candidateRecent(now)) throw new IllegalArgumentException("Reconnect the vehicle to refresh this reading");
        confirmedSource=candidateSource;source=candidateSource;automatic=candidate;automaticAt=candidateAt;
        candidate=-1;candidateAt=0;candidateSource="";manualOnly=false;
    }
    int value() {return manualOnly?manual:automatic>=0?automatic:manual;}
    boolean usingAutomatic() {return !manualOnly && automatic>=0;}
}
