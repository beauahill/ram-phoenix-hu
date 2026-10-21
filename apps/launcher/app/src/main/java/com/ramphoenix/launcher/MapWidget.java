package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.view.ViewGroup;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.File;

/**
 * The dashboard's live map tile: osmdroid (OpenStreetMap, no API key) with a
 * dark CARTO basemap, following the truck's GPS position. Tapping the nav
 * button or search bar hands off to the real navigation app.
 *
 * Tiles are cached in the app cache dir (no external storage permission).
 */
public class MapWidget {

    private static final double FOLLOW_ZOOM = 16.0;
    private static final double IDLE_ZOOM = 4.0;

    /** CARTO "dark matter" basemap. Free for light use; attribution kept in the source name. */
    private static final XYTileSource DARK = new XYTileSource(
            "CartoDarkMatter", 0, 19, 256, ".png",
            new String[]{
                    "https://a.basemaps.cartocdn.com/dark_all/",
                    "https://b.basemaps.cartocdn.com/dark_all/",
                    "https://c.basemaps.cartocdn.com/dark_all/"},
            "© OpenStreetMap contributors © CARTO");

    private final Context ctx;
    private final MapView map;
    private MyLocationNewOverlay me;

    public MapWidget(Activity act, ViewGroup container) {
        ctx = act.getApplicationContext();

        IConfigurationProvider cfg = Configuration.getInstance();
        File base = new File(ctx.getCacheDir(), "osmdroid");
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(new File(base, "tiles"));
        cfg.setUserAgentValue(ctx.getPackageName());

        map = new MapView(act);
        map.setTileSource(DARK);
        map.setMultiTouchControls(true);
        map.setTilesScaledToDpi(true);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.setDestroyMode(false); // we re-parent the view between layouts; tear down only in onDestroy()
        map.getController().setZoom(IDLE_ZOOM);
        map.getController().setCenter(new GeoPoint(39.5, -98.35)); // until the first fix
        container.addView(map, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** Call once ACCESS_FINE_LOCATION is granted. Safe to call repeatedly. */
    public void enableLocation() {
        if (me != null) return;
        me = new MyLocationNewOverlay(new GpsMyLocationProvider(ctx), map);
        me.enableMyLocation();
        me.enableFollowLocation();
        map.getOverlays().add(me);
        map.getController().setZoom(FOLLOW_ZOOM);
        Location last = lastKnown();
        if (last != null) {
            map.getController().setCenter(new GeoPoint(last.getLatitude(), last.getLongitude()));
        }
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

    public void zoomIn()  { map.getController().zoomIn(); }
    public void zoomOut() { map.getController().zoomOut(); }

    public void onResume() {
        map.onResume();
        if (me != null) {
            me.enableMyLocation();
            me.enableFollowLocation();
        }
    }

    public void onPause() {
        if (me != null) me.disableMyLocation();
        map.onPause();
    }

    public void onDestroy() {
        map.onDetach();
    }
}
