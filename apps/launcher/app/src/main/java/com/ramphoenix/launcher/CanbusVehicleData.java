package com.ramphoenix.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Vehicle data from the SYU ToolkitService and the truck's verified OBD profiles.
 *
 * This HU reports PA_RAM_22_Low (2163062) through CANBUS subscription 1000.
 * The factory PA accessory-gauge decoder supplies coolant/oil/transmission
 * temperatures on 234/235/236 and oil pressure on 237. Oil temperature has been
 * checked against the cluster; the additional mappings still need a truck test.
 *   MAIN 40    outside temperature: (v & 0xFFFF) is tenths of a degree + 1000,
 *              bit 28 set = Fahrenheit (live: 268437096 -> 1640 -> 64 F, matched the cluster)
 * Door/speed subscriptions and optional raw-frame logging are retained from the
 * older LZ investigation; speed units and PA raw-frame delivery remain unverified.
 * Missing/invalid engine fields, voltage and tire pressures use ObdClient.
 * Unsupported values remain null.
 */
public class CanbusVehicleData implements VehicleData {

    private ObdClient obd;
    public void setObd(ObdClient o) { obd = o; }

    private static final String TAG = "PhoenixLauncher";
    private static final int U_TEMP_OUT = 40, U_CUR_SPEED = 1031, U_RAW_FRAME = 1052;

    private final SyuIpc ipc;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable onChange;
    private volatile boolean connected;
    private volatile Float outsideC, speedKph;
    private final RamPaGauges paGauges = new RamPaGauges();
    private final Runnable gaugeRequest = this::requestGauges;
    private final boolean[] doors = new boolean[6];

    public CanbusVehicleData(Context ctx, Runnable onChange) {
        this.onChange = onChange;
        ipc = new SyuIpc(ctx);
        ipc.setConnectionListener(c -> {
            connected = c;
            if (!c) { paGauges.clear();outsideC=speedKph=null;java.util.Arrays.fill(doors,false); ui.removeCallbacks(gaugeRequest); }
            changed();
            if (c) ui.postDelayed(gaugeRequest, 700);
        });
        for (int code : new int[]{1000, 234, 235, 236, 237, 238, 301, 372, 373, 374, 395}) {
            ipc.subscribe(SyuIpc.MODULE_CANBUS, code, (c, ints, f, s) -> {
                if (ints != null && ints.length > 0) {
                    if (c >= RamPaGauges.COOLANT && c <= RamPaGauges.OIL_PRESSURE) {
                        paGauges.update(c, ints[0],android.os.SystemClock.elapsedRealtime());
                        Log.i("PhoenixGauges", "PA gauge field=" + c + " raw=" + ints[0]
                                + " decoded=" + (c == RamPaGauges.OIL_PRESSURE
                                ? RamPaGauges.decodePressure(ints[0]) : RamPaGauges.decodeTemperature(ints[0])));
                        changed();
                    } else if (c == 1000) {
                        paGauges.profile(ints[0]);
                        Log.i(TAG, "CAN profile from subscription=" + ints[0]);
                        ui.post(gaugeRequest);
                        changed();
                    }
                }
                if (oilProbeEnabled()) Log.i(TAG, "CAN oilprobe " + c + " -> "
                        + java.util.Arrays.toString(ints));
            });
        }
        ipc.subscribe(SyuIpc.MODULE_MAIN, U_TEMP_OUT, (code, ints, f, s) -> {
            if (ints == null || ints.length == 0) return;
            int v = ints[0];
            int raw = v & 0xFFFF;
            if (raw == 0) { outsideC = null; changed(); return; }
            float t = (raw - 1000) / 10f;
            boolean fahrenheit = (v & 0x10000000) != 0;
            outsideC = fahrenheit ? (t - 32f) * 5f / 9f : t;
            changed();
        });
        ipc.subscribe(SyuIpc.MODULE_CANBUS, U_CUR_SPEED, (code, ints, f, s) -> {
            if (ints == null || ints.length == 0) return;
            speedKph = ints[0] < 0 ? null : (float) ints[0];
            changed();
        });
        for (int d = 0; d < 6; d++) {
            final int idx = d;
            ipc.subscribe(SyuIpc.MODULE_CANBUS, d, (code, ints, f, s) -> {
                if (ints != null && ints.length > 0) { doors[idx] = ints[0] != 0; changed(); }
            });
        }
        ipc.subscribe(SyuIpc.MODULE_CANBUS, U_RAW_FRAME, (code, ints, f, s) -> {
            if (ints != null && Log.isLoggable("PhoenixRaw", Log.DEBUG)) {
                StringBuilder sb = new StringBuilder("canbus frame");
                for (int b : ints) sb.append(' ').append(String.format("%02X", b & 0xFF));
                Log.d("PhoenixRaw", sb.toString());
            }
        });
    }

    private void changed() { ui.post(onChange); }

    private void requestGauges() {
        ui.removeCallbacks(gaugeRequest);
        if (!connected || !(paGauges.supported() || oilProbeEnabled())) return;
        // Factory read/page request; does not change ECU settings or reset anything.
        boolean sent = ipc.cmd(SyuIpc.MODULE_CANBUS, 4, new int[]{82});
        Log.i("PhoenixGauges", "PA RAM gauge read request page 82: " + sent);
        // A request is not an acknowledgement and never renews the freshness lease.
        ui.postDelayed(gaugeRequest,15000);
    }

    private static boolean oilProbeEnabled() {
        return "1".equals(property("persist.phoenix.can.oilprobe", ""));
    }
    private static boolean preferCanGauges() { return !"0".equals(property("persist.phoenix.can.gauges", "1")); }
    private static String property(String name, String fallback) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, name, fallback);
        } catch (Exception e) { return fallback; }
    }

    public void connect() { ipc.connect(); }
    public void disconnect() {
        connected = false; paGauges.clear();outsideC=speedKph=null;java.util.Arrays.fill(doors,false);
        ui.removeCallbacks(gaugeRequest); ipc.disconnect();
    }

    int canGaugeMask() {
        int mask=0;if(preferCanGauges())for(Gauge gauge:Gauge.values())
            if(paGauges.liveValue(gauge,connected,android.os.SystemClock.elapsedRealtime())!=null)mask|=gauge.mask;
        return mask;
    }
    @Override public GaugeReading reading(Gauge gauge) {
        Float factory = preferCanGauges() ? paGauges.liveValue(gauge, connected,android.os.SystemClock.elapsedRealtime()) : null;
        if (factory != null) return GaugeReading.choose(factory, null);
        Float fallback = null;
        if (obd != null && obd.connected()) switch (gauge) {
            case COOLANT: fallback = obd.coolantC(); break;
            case TRANSMISSION: fallback = obd.transC(); break;
            case OIL_PRESSURE: fallback = obd.oilPsi(); break;
            case OIL_TEMPERATURE: fallback = obd.oilTempC(); break;
        }
        return GaugeReading.choose(null, fallback);
    }

    public boolean doorOpen(int idx) { return idx >= 0 && idx < 6 && doors[idx]; }
    public boolean anyDoorOpen() { for (boolean d : doors) if (d) return true; return false; }

    @Override public Float tirePsi(int corner) { return obd != null ? obd.tirePsi(corner) : null; }
    @Override public Float fuelPercent() { return null; }
    @Override public Float coolantC() { return reading(Gauge.COOLANT).value; }
    @Override public Float batteryVolts() { return obd != null ? obd.volts() : null; }
    @Override public Float transC() { return reading(Gauge.TRANSMISSION).value; }
    @Override public Float oilPsi() { return reading(Gauge.OIL_PRESSURE).value; }
    @Override public Float oilTempC() { return reading(Gauge.OIL_TEMPERATURE).value; }
    @Override public int doorsOpen() { if (!connected) return -1; int n = 0; for (boolean d : doors) if (d) n++; return n; }
    @Override public Float outsideC() { return outsideC; }
    @Override public Float speedKph() { return speedKph; }
    @Override public Float rpm() { return null; }
    @Override public boolean available() { return (connected && (outsideC != null || canGaugeMask() != 0)) || (obd != null && obd.hasFreshReadings()); }
    @Override public boolean hasWarnings(){
        for(Gauge gauge:Gauge.values())if(reading(gauge).value!=null)return false;
        return true;
    }
    @Override public String status(){
        return "Factory link: "+(connected?"connected":"disconnected")
                +"\nCAN update: "+paGauges.freshness(android.os.SystemClock.elapsedRealtime())
                +"\nUnchanged CAN values expire after 2 minutes without a confirmed update."
                +"\nOBD poll: "+(obd==null?"unavailable":obd.freshness())
                +"\nExpired readings fall back to a fresh source or show a dash.";
    }
}
