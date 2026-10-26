package com.ramphoenix.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.GoogleMapOptions;
import com.google.android.gms.maps.MapView;
import com.google.android.gms.maps.MapsInitializer;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import java.util.List;
import java.util.Locale;

/**
 * The dashboard's live map card: Google Maps SDK (Play Services is present on the
 * unit; key comes from local.properties via the manifest placeholder, never from git).
 *
 * Behaviour (Beau, 2026-10-25: "make it a mostly functional Google Maps"):
 *  - follows the truck's GPS fix heading-up until the user pans; the recenter
 *    button resumes following (it no longer hands off to the Maps app);
 *  - layers button cycles night / satellite / hybrid; 3D toggles a tilted view
 *    with buildings; traffic toggles the live traffic layer;
 *  - "Where to?" opens a search box; results come from the platform Geocoder
 *    (backed by Play Services on this unit, no extra API key) and drop a pin;
 *    the GO button then starts turn-by-turn in Google Maps for that pin.
 *    Turn-by-turn inside the card would need the paid Navigation SDK.
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
    private static final float TILT_2D = 30f;
    private static final float TILT_3D = 62f;
    /** Below this speed GPS bearing is noise; keep the last heading. */
    private static final float MIN_BEARING_MPS = 1.5f;

    /** Host hooks so the card can reflect state (recenter highlight, destination bar). */
    public interface Host {
        void onFollowingChanged(boolean following);
        void onDestinationChanged(String name);   // null = cleared
        void onToggles(int mapType, boolean threeD, boolean traffic);
    }

    private final Context ctx;
    private final MapView view;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private GoogleMap gmap;
    private Host host;
    private boolean locationWanted;
    private boolean locationEnabled;
    private boolean resumed;
    private boolean following = true;
    private boolean threeD;
    private boolean traffic = true;
    private int mapType = GoogleMap.MAP_TYPE_NORMAL;
    private float lastBearing;
    private Location pending;
    private Location last;
    private Marker destMarker;
    private LatLng dest;
    private String destName;

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location l) { last = l; if (following) follow(l); }
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
                .tiltGesturesEnabled(true)
                .liteMode(false);
        view = new MapView(act, opts);
        view.onCreate(null);
        container.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        view.getMapAsync(m -> {
            gmap = m;
            applyStyle();
            m.setBuildingsEnabled(threeD);
            m.setIndoorEnabled(false);
            m.setTrafficEnabled(traffic);
            m.getUiSettings().setMyLocationButtonEnabled(false);
            m.setOnCameraMoveStartedListener(reason -> {
                if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) setFollowing(false);
            });
            m.setOnMapLongClickListener(this::setDestination);
            if (locationWanted) applyLocation();
            if (pending != null) follow(pending);
            if (host != null) host.onToggles(mapType, threeD, traffic);
        });
    }

    public void setHost(Host h) { host = h; }

    /** Call once ACCESS_FINE_LOCATION is granted. Safe to call repeatedly. */
    public void enableLocation() {
        locationWanted = true;
        if (gmap != null) applyLocation();
        if (resumed) startUpdates();
        Location l = lastKnown();
        if (l != null) { last = l; if (following) follow(l); }
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

    private void applyStyle() {
        if (gmap == null) return;
        gmap.setMapType(mapType);
        if (mapType == GoogleMap.MAP_TYPE_NORMAL) {
            try {
                boolean ok = gmap.setMapStyle(MapStyleOptions.loadRawResourceStyle(ctx, R.raw.map_style_night));
                if (!ok) Log.w(TAG, "map style parse failed");
            } catch (Exception e) {
                Log.w(TAG, "map style: " + e);
            }
        } else {
            gmap.setMapStyle(null);
        }
    }

    private void follow(Location l) {
        if (gmap == null) { pending = l; return; }
        pending = null;
        if (l.hasBearing() && l.getSpeed() >= MIN_BEARING_MPS) lastBearing = l.getBearing();
        float zoom = gmap.getCameraPosition().zoom < 10f ? FOLLOW_ZOOM : gmap.getCameraPosition().zoom;
        CameraPosition cp = new CameraPosition.Builder()
                .target(new LatLng(l.getLatitude(), l.getLongitude()))
                .zoom(zoom)
                .bearing(lastBearing)
                .tilt(threeD ? TILT_3D : TILT_2D)
                .build();
        gmap.animateCamera(CameraUpdateFactory.newCameraPosition(cp), 700, null);
    }

    private void setFollowing(boolean f) {
        if (following == f) return;
        following = f;
        if (host != null) host.onFollowingChanged(f);
    }

    /** Recenter on the truck and resume heading-up following. */
    public void recenter() {
        setFollowing(true);
        Location l = last != null ? last : lastKnown();
        if (l != null) { last = l; follow(l); }
        else Toast.makeText(ctx, R.string.gps_searching, Toast.LENGTH_SHORT).show();
    }

    public boolean isFollowing() { return following; }

    /** Night map -> satellite -> hybrid -> night. Returns the label of the new layer. */
    public int cycleMapType() {
        switch (mapType) {
            case GoogleMap.MAP_TYPE_NORMAL:    mapType = GoogleMap.MAP_TYPE_SATELLITE; break;
            case GoogleMap.MAP_TYPE_SATELLITE: mapType = GoogleMap.MAP_TYPE_HYBRID; break;
            default:                           mapType = GoogleMap.MAP_TYPE_NORMAL; break;
        }
        applyStyle();
        if (host != null) host.onToggles(mapType, threeD, traffic);
        return mapType == GoogleMap.MAP_TYPE_NORMAL ? R.string.layer_map
                : mapType == GoogleMap.MAP_TYPE_SATELLITE ? R.string.layer_satellite : R.string.layer_hybrid;
    }

    public boolean toggle3D() {
        threeD = !threeD;
        if (gmap != null) {
            gmap.setBuildingsEnabled(threeD);
            CameraPosition cur = gmap.getCameraPosition();
            CameraPosition cp = new CameraPosition.Builder(cur)
                    .tilt(threeD ? TILT_3D : TILT_2D)
                    .zoom(threeD ? Math.max(cur.zoom, 17f) : cur.zoom)
                    .build();
            gmap.animateCamera(CameraUpdateFactory.newCameraPosition(cp), 500, null);
        }
        if (host != null) host.onToggles(mapType, threeD, traffic);
        return threeD;
    }

    public boolean toggleTraffic() {
        traffic = !traffic;
        if (gmap != null) gmap.setTrafficEnabled(traffic);
        if (host != null) host.onToggles(mapType, threeD, traffic);
        return traffic;
    }

    // ---- destination / search ----

    /** "Where to?": a search box; the top Geocoder hit becomes the destination pin. */
    public void promptSearch(Activity act) {
        final EditText input = new EditText(act);
        input.setHint(R.string.search_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setSingleLine(true);
        AlertDialog dlg = new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.where_to)
                .setView(input)
                .setPositiveButton(R.string.search, (d, w) -> search(act, input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        input.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == android.view.KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_SEARCH || enter) {
                search(act, input.getText().toString()); dlg.dismiss(); return true;
            }
            return false;
        });
        dlg.show();
        input.requestFocus();
    }

    public void search(Activity act, String query) {
        final String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return;
        final Location near = last != null ? last : lastKnown();
        Toast.makeText(ctx, ctx.getString(R.string.searching, q), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<Address> hits = null;
            try {
                Geocoder g = new Geocoder(ctx, Locale.getDefault());
                if (near != null) {
                    // bias to ~150 km around the truck first, then anywhere
                    double d = 1.4;
                    hits = g.getFromLocationName(q, 5,
                            near.getLatitude() - d, near.getLongitude() - d,
                            near.getLatitude() + d, near.getLongitude() + d);
                }
                if (hits == null || hits.isEmpty()) hits = g.getFromLocationName(q, 5);
            } catch (Exception e) {
                Log.w(TAG, "geocode failed: " + e);
            }
            final Address a = (hits == null || hits.isEmpty()) ? null : hits.get(0);
            // The platform geocoder resolves addresses and towns but not business
            // names ("Walmart" returned nothing live, 2026-10-25). Fall back to
            // OpenStreetMap's Nominatim, biased to the truck, for place names.
            final double[] poi = a == null ? nominatim(q, near) : null;
            ui.post(() -> {
                if (a != null) {
                    String name = a.getFeatureName();
                    if (name == null || name.isEmpty() || name.matches("[0-9]+")) name = a.getAddressLine(0);
                    if (name == null) name = q;
                    setDestination(new LatLng(a.getLatitude(), a.getLongitude()), name);
                } else if (poi != null) {
                    setDestination(new LatLng(poi[0], poi[1]), poiName != null ? poiName : q);
                } else {
                    Toast.makeText(ctx, R.string.no_results, Toast.LENGTH_SHORT).show();
                }
            });
        }, "geocode").start();
    }

    private volatile String poiName;

    /** Nominatim place search, nearest-first within ~150 km of the truck. Returns {lat, lon} or null. */
    private double[] nominatim(String q, Location near) {
        poiName = null;
        java.net.HttpURLConnection c = null;
        try {
            StringBuilder u = new StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=")
                    .append(Uri.encode(q));
            if (near != null) {
                double d = 1.4;
                u.append("&viewbox=").append(near.getLongitude() - d).append(',').append(near.getLatitude() + d)
                 .append(',').append(near.getLongitude() + d).append(',').append(near.getLatitude() - d)
                 .append("&bounded=1");
            }
            c = (java.net.HttpURLConnection) new java.net.URL(u.toString()).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "ram-phoenix-hu/0.4 (personal head unit)");
            java.io.InputStream in = c.getInputStream();
            byte[] buf = new byte[16384]; int n; StringBuilder sb = new StringBuilder();
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
            if (arr.length() == 0) {
                if (near == null) return null;
                return nominatim(q, null);           // nothing nearby: try unbounded once
            }
            org.json.JSONObject o = arr.getJSONObject(0);
            String name = o.optString("name", null);
            if (name == null || name.isEmpty()) name = o.optString("display_name", q);
            poiName = name;
            return new double[]{o.getDouble("lat"), o.getDouble("lon")};
        } catch (Exception e) {
            Log.w(TAG, "nominatim failed: " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void setDestination(LatLng p) { setDestination(p, ctx.getString(R.string.dropped_pin)); }

    public void setDestination(LatLng p, String name) {
        if (gmap == null) return;
        dest = p; destName = name;
        if (destMarker != null) destMarker.remove();
        destMarker = gmap.addMarker(new MarkerOptions().position(p).title(name)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
        setFollowing(false);
        Location l = last != null ? last : lastKnown();
        if (l != null) {
            LatLngBounds b = new LatLngBounds.Builder()
                    .include(p).include(new LatLng(l.getLatitude(), l.getLongitude())).build();
            try { gmap.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 140), 700, null); }
            catch (Exception e) { gmap.animateCamera(CameraUpdateFactory.newLatLngZoom(p, 14f)); }
        } else {
            gmap.animateCamera(CameraUpdateFactory.newLatLngZoom(p, 14f));
        }
        if (host != null) host.onDestinationChanged(name);
    }

    public void clearDestination() {
        dest = null; destName = null;
        if (destMarker != null) { destMarker.remove(); destMarker = null; }
        if (host != null) host.onDestinationChanged(null);
    }

    public boolean hasDestination() { return dest != null; }

    /** Start turn-by-turn to the pin in Google Maps (or any geo handler). */
    public void navigate(Activity act) {
        if (dest == null) return;
        String ll = dest.latitude + "," + dest.longitude;
        Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + ll + "&mode=d"));
        nav.setPackage("com.google.android.apps.maps");
        nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { act.startActivity(nav); return; } catch (ActivityNotFoundException ignored) {}
        Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + ll + "?q=" + ll + "(" + Uri.encode(destName) + ")"));
        geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { act.startActivity(geo); }
        catch (ActivityNotFoundException e) { Toast.makeText(ctx, R.string.not_installed, Toast.LENGTH_SHORT).show(); }
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
