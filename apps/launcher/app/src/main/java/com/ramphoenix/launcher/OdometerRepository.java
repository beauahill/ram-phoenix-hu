package com.ramphoenix.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Mileage is kept separately so telemetry cannot overwrite edited service records. */
final class OdometerRepository {
    private static final Object LOCK=new Object();
    private final SharedPreferences prefs;
    OdometerRepository(Context context) {this(context.getSharedPreferences("maintenance_odometer",Context.MODE_PRIVATE));}
    OdometerRepository(SharedPreferences prefs) {this.prefs=prefs;}
    MileageState state() {
        synchronized(LOCK) {
        MileageState s=new MileageState();s.automatic=prefs.getInt("auto",-1);s.manual=prefs.getInt("manual",-1);
        s.candidate=prefs.getInt("candidate",-1);s.automaticAt=prefs.getLong("auto_at",0);s.manualAt=prefs.getLong("manual_at",0);
        s.candidateAt=prefs.getLong("candidate_at",0);s.source=prefs.getString("source","");
        s.candidateSource=prefs.getString("candidate_source","");s.confirmedSource=prefs.getString("confirmed_source","");
        s.manualOnly=prefs.getBoolean("manual_only",false);return s;
        }
    }
    private SharedPreferences.Editor values(MileageState s) {
        return prefs.edit().putInt("auto",s.automatic).putInt("manual",s.manual).putInt("candidate",s.candidate)
                .putLong("auto_at",s.automaticAt).putLong("manual_at",s.manualAt).putLong("candidate_at",s.candidateAt)
                .putString("source",s.source).putString("candidate_source",s.candidateSource)
                .putString("confirmed_source",s.confirmedSource).putBoolean("manual_only",s.manualOnly);
    }
    void observe(ObdMileage reading) {synchronized(LOCK) {MileageState s=state();s.observe(reading,System.currentTimeMillis());values(s).apply();}}
    void migrateLegacy(int miles) {
        synchronized(LOCK) {
        if(miles>=0 && miles<=MaintenanceInput.MAX_MILES && !prefs.contains("manual"))
            prefs.edit().putInt("manual",miles).putLong("manual_at",0).apply();
        }
    }
    void configure(boolean manualOnly,int fallback,boolean confirmCandidate) {
        synchronized(LOCK) {
        MileageState s=state();
        if(confirmCandidate) s.confirm(System.currentTimeMillis());
        s.manualOnly=manualOnly;
        if(s.manual!=fallback) {s.manual=fallback;s.manualAt=fallback>=0?System.currentTimeMillis():0;}
        if(!values(s).commit()) throw new IllegalStateException("Mileage settings could not be saved");
        }
    }
    int miles() {return state().value();}
    String label(boolean live) {
        MileageState s=state();
        if(s.value()<0) return s.candidate>=0?"Vehicle reading needs review":"Waiting for vehicle mileage";
        if(s.usingAutomatic()) return live?"Automatic · OBD":"Last vehicle reading";
        return s.manualOnly?"Manual mileage":"Manual fallback";
    }
    JSONObject json() throws Exception {
        MileageState s=state();return new JSONObject().put("value",s.value()).put("automatic",s.automatic).put("automaticAt",s.automaticAt)
                .put("source",s.source).put("manual",s.manual).put("manualAt",s.manualAt).put("manualOnly",s.manualOnly);
    }
}
