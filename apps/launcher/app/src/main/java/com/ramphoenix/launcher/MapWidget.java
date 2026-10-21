package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMapOptions;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.MapsInitializer;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;

/**
 * The dashboard's live map card: Google Maps SDK (Play Services is present on the
 * unit; key comes from local.properties via the manifest placeholder, never from git).
 *
 * Night-styled, follows the truck's GPS fix heading-up once location is granted.
 * Tapping the nav button or search bar hands off to the real navigation app.
 *
 * Lifecycle: the host activity must forward onResume/onPause/onDestroy/onLowMemory.
 * The MapView is created with a null Bundle on purpose: the launcher is
 * stateNotNeeded and re-parents this view between FULL/SPLIT layouts, so we never
 * want the SDK restoring stale camera state for us.
 */
public class MapWidget {

    private static final String TAG = "MapWidget";
    private static final float FOLLOW_ZOOM = 16f;
    private static final float IDLE_ZOOM = 4f;
    private static final float FOLLOW_TILT = 30f;
    /** Below this speed GPS bearing is noise; keep the last heading. */
    private static final float MIN_BEARING_MPS = 1.5f;

    private final Context ctx;
    private final MapView view;
    private GoogleMap gmap;
    private boolean locationWanted;
    private boolean locationEnabled;
    private boolean resumed;
    private float lastBearing;
    private Location pending;

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { follow(l); }
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
    };

    public MapWidget(Activity act, ViewGroup container) {
        ctx = act.getApplicationContext();
        try {
            MapsInitializer.initialize(ctx, MapsInitializer.Renderer.LATEST, r -> Log.i(TAG, "maps renderer=" + r));
        } catch (Exception e) {
            Log.w(TAG, "MapsInitializer failed: " + e);
        }
        GoogleMapOptions opts = new GoogleMapOptions()
                .camera(new CameraPosition(new LatLng(39.5, -98.35), IDLE_ZOOM, 0f, 0f)) // until first fix
                .zoomControlsEnabled(false)
                .compassEnabled(false)
                .mapToolbarEnabled(false)
                .rotateGesturesEnabled(true)
                .tiltGesturesEnabled(false)
                .liteMode(false);
        view = new MapView(act, opts);
        view.onCreate(null);
        container.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        view.getMapAsync(m -> {
            gmap = m;
            try {
                boolean ok = m.setMapStyle(MapStyleOptions.loadRawResourceStyle(ctx, R.raw.map_style_night));
                if (!ok) Log.w(TAG, "map style parse failed");
            } catch (Exception e) {
                Log.w(TAG, "map style: " + e);
            }
            m.setBuildingsEnabled(false);
            m.setIndoorEnabled(false);
            m.setTrafficEnabled(true);
            m.getUiSettings().setMyLocationButtonEnabled(false);
            if (locationWanted) applyLocation();
            if (pending != null) follow(pending);
        });
    }

    /** Call once ACCESS_FINE_LOCATION is granted. Safe to call repeatedly. */
    public void enableLocation() {
        locationWanted = true;
        if (gmap != null) applyLocation();
        if (resumed) startUpdates();
        Location last = lastKnown();
        if (last != null) follow(last);
    }

    private void applyLocation() {
        if (locationEnabled) return;
        try {
            gmap.setMyLocationEnabled(true);
            locationEnabled = true;
        } catch (SecurityException e) {
            Log.w(TAG, "setMyLocationEnabled without permission");
        }
    }

    private void follow(Location l) {
        if (gmap == null) { pending = l; return; }
        pending = null;
        if (l.hasBearing() && l.getSpeed() >= MIN_BEARING_MPS) lastBearing = l.getBearing();
        CameraPosition cp = new CameraPosition.Builder()
                .target(new LatLng(l.getLatitude(), l.getLongitude()))
                .zoom(gmap.getCameraPosition().zoom < 10f ? FOLLOW_ZOOM : gmap.getCameraPosition().zoom)
                .bearing(lastBearing)
                .tilt(FOLLOW_TILT)
                .build();
        gmap.animateCamera(CameraUpdateFactory.newCameraPosition(cp), 700, null);
    }

    private Location lastKnown() {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            return l;
        } catch (SecurityException e) {
            return null;
        }
    }

    private void startUpdates() {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            lm.removeUpdates(listener);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, listener);
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "location updates: " + e);
        }
    }

    private void stopUpdates() {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) lm.removeUpdates(listener);
        } catch (SecurityException ignored) {}
    }

    public void zoomIn()  { if (gmap != null) gmap.animateCamera(CameraUpdateFactory.zoomIn()); }
    public void zoomOut() { if (gmap != null) gmap.animateCamera(CameraUpdateFactory.zoomOut()); }

    public void onResume() {
        resumed = true;
        view.onResume();
        if (locationWanted) startUpdates();
    }

    public void onPause() {
        resumed = false;
        stopUpdates();
        view.onPause();
    }

    public void onLowMemory() { view.onLowMemory(); }

    public void onDestroy() {
        stopUpdates();
        view.onDestroy();
    }
}
