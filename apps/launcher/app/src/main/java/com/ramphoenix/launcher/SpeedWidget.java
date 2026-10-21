package com.ramphoenix.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;

/**
 * Feeds the speed dial. Source order: canbus speed from {@link VehicleData}
 * when it is live, otherwise GPS ground speed. mph for the US truck; flip
 * {@link #USE_MPH} for km/h.
 */
public class SpeedWidget implements LocationListener {

    static final boolean USE_MPH = true;
    private static final long GPS_INTERVAL_MS = 500;

    private final Context ctx;
    private final LocationManager lm;
    private final VehicleCardView card;
    private VehicleData vehicle = VehicleData.STUB;
    private boolean listening;

    public SpeedWidget(Context ctx, VehicleCardView card) {
        this.ctx = ctx.getApplicationContext();
        this.lm = (LocationManager) this.ctx.getSystemService(Context.LOCATION_SERVICE);
        this.card = card;
        card.setUseMph(USE_MPH);
    }

    public void setVehicle(VehicleData v) {
        vehicle = (v == null) ? VehicleData.STUB : v;
        Float kph = vehicle.available() ? vehicle.speedKph() : null;
        if (kph != null) {
            card.setSpeed(USE_MPH ? kph * 0.621371f : kph);
            card.setSpeedSource("CAN");
        }
    }

    public void start() {
        if (listening || lm == null) return;
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            card.setSpeedSource("GPS OFF");
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, this);
            listening = true;
            card.setSpeedSource("GPS SEARCHING");
        } catch (SecurityException | IllegalArgumentException e) {
            card.setSpeedSource("GPS OFF");
        }
    }

    public void stop() {
        if (!listening) return;
        try { lm.removeUpdates(this); } catch (Exception ignored) {}
        listening = false;
    }

    @Override public void onLocationChanged(Location l) {
        if (vehicle.available() && vehicle.speedKph() != null) return; // canbus wins
        float ms = l.hasSpeed() ? l.getSpeed() : 0f;
        float v = USE_MPH ? ms * 2.23694f : ms * 3.6f;
        if (v < 2f) v = 0f; // GPS jitter at standstill
        card.setSpeed(v);
        card.setSpeedSource("GPS");
    }

    @Override public void onProviderDisabled(String provider) { card.setSpeedSource("GPS OFF"); }
    @Override public void onProviderEnabled(String provider) { card.setSpeedSource("GPS SEARCHING"); }
}
