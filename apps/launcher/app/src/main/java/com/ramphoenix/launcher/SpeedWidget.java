package com.ramphoenix.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.widget.TextView;

/**
 * Big speed readout. Source order: canbus speed from {@link VehicleData} when
 * it is live, otherwise GPS ground speed. Shows mph (US truck); flip
 * {@link #USE_MPH} for km/h.
 */
public class SpeedWidget implements LocationListener {

    static final boolean USE_MPH = true;
    private static final long GPS_INTERVAL_MS = 500;

    private final Context ctx;
    private final LocationManager lm;
    private final TextView value, unit, source;
    private VehicleData vehicle = VehicleData.STUB;
    private boolean listening;
    private boolean hadFix;

    public SpeedWidget(Context ctx, TextView value, TextView unit, TextView source) {
        this.ctx = ctx.getApplicationContext();
        this.lm = (LocationManager) this.ctx.getSystemService(Context.LOCATION_SERVICE);
        this.value = value;
        this.unit = unit;
        this.source = source;
        unit.setText(USE_MPH ? R.string.mph : R.string.kph);
    }

    public void setVehicle(VehicleData v) {
        vehicle = (v == null) ? VehicleData.STUB : v;
        Float kph = vehicle.available() ? vehicle.speedKph() : null;
        if (kph != null) {
            show(USE_MPH ? kph * 0.621371f : kph);
            source.setText("CAN");
        }
    }

    public void start() {
        if (listening || lm == null) return;
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            source.setText(R.string.gps_off);
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, this);
            listening = true;
            if (!hadFix) source.setText(R.string.gps_searching);
        } catch (SecurityException | IllegalArgumentException e) {
            source.setText(R.string.gps_off);
        }
    }

    public void stop() {
        if (!listening) return;
        try { lm.removeUpdates(this); } catch (Exception ignored) {}
        listening = false;
    }

    @Override public void onLocationChanged(Location l) {
        hadFix = true;
        // canbus wins when live
        if (vehicle.available() && vehicle.speedKph() != null) return;
        float ms = l.hasSpeed() ? l.getSpeed() : 0f;
        show(USE_MPH ? ms * 2.23694f : ms * 3.6f);
        source.setText(R.string.gps);
    }

    @Override public void onProviderDisabled(String provider) { source.setText(R.string.gps_off); }
    @Override public void onProviderEnabled(String provider) { source.setText(R.string.gps_searching); }

    private void show(float v) {
        int n = Math.round(v);
        if (n < 2) n = 0; // GPS jitter at standstill
        value.setText(String.valueOf(n));
    }
}
