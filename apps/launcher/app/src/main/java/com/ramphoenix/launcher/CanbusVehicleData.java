package com.ramphoenix.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Vehicle data from the SYU ToolkitService and the truck's verified OBD profiles.
 *
 * This HU reports PA_RAM_22_Low (2163062) through CANBUS subscription 1000.
 * The factory PA accessory-gauge decoder supplies oil temperature on CANBUS 235;
 * see docs/OIL-DATA-2019-RAM3500.md for the packet layout and live verification.
 *   MAIN 40    outside temperature: (v & 0xFFFF) is tenths of a degree + 1000,
 *              bit 28 set = Fahrenheit (live: 268437096 -> 1640 -> 64 F, matched the cluster)
 * Door/speed subscriptions and optional raw-frame logging are retained from the
 * older LZ investigation; speed units and PA raw-frame delivery remain unverified.
 * Other verified engine and tire-pressure values are delegated to ObdClient.
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
    private final RamPaOil paOil = new RamPaOil();
    private final boolean[] doors = new boolean[6];

    public CanbusVehicleData(Context ctx, Runnable onChange) {
        this.onChange = onChange;
        ipc = new SyuIpc(ctx);
        ipc.setConnectionListener(c -> {
            connected = c; changed();
            if (!c) paOil.clear();
            if (c) ui.postDelayed(() -> {
                if (connected && (paOil.supported() || oilProbeEnabled())) {
                    // Same read/page request as the factory accessory-gauge activity.
                    Log.i(TAG, "PA RAM gauge read request page 82: "
                            + ipc.cmd(SyuIpc.MODULE_CANBUS, 4, new int[]{82}));
                }
            }, 700);
        });
        for (int code : new int[]{234, 235, 236, 237, 238, 301, 372, 373, 374, 395, 1000}) {
            ipc.subscribe(SyuIpc.MODULE_CANBUS, code, (c, ints, f, s) -> {
                if (ints != null && ints.length > 0) {
                    if (c == RamPaOil.TEMPERATURE_CODE) {
                        paOil.temperature(ints[0]);
                        Log.i(TAG, "PA RAM oil temperature raw=" + ints[0]
                                + " Celsius=" + RamPaOil.decodeTemperature(ints[0]));
                        changed();
                    } else if (c == 1000) {
                        paOil.profile(ints[0]);
                        Log.i(TAG, "CAN profile from subscription=" + ints[0]);
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

    private static boolean oilProbeEnabled() {
        try {
            return "1".equals(Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "persist.phoenix.can.oilprobe", ""));
        } catch (Exception e) { return false; }
    }

    public void connect() { ipc.connect(); }
    public void disconnect() { connected = false; paOil.clear(); ipc.disconnect(); }

    public boolean doorOpen(int idx) { return idx >= 0 && idx < 6 && doors[idx]; }
    public boolean anyDoorOpen() { for (boolean d : doors) if (d) return true; return false; }

    @Override public Float tirePsi(int corner) { return obd != null ? obd.tirePsi(corner) : null; }
    @Override public Float fuelPercent() { return null; }
    @Override public Float coolantC() { return obd != null ? obd.coolantC() : null; }
    @Override public Float batteryVolts() { return obd != null ? obd.volts() : null; }
    @Override public Float transC() { return obd != null ? obd.transC() : null; }
    @Override public Float oilPsi() { return obd != null ? obd.oilPsi() : null; }
    @Override public Float oilTempC() {
        Float factory = paOil.temperatureC(connected);
        return factory != null ? factory : obd != null ? obd.oilTempC() : null;
    }
    @Override public int doorsOpen() { if (!connected) return -1; int n = 0; for (boolean d : doors) if (d) n++; return n; }
    @Override public Float outsideC() { return outsideC; }
    @Override public Float speedKph() { return speedKph; }
    @Override public Float rpm() { return null; }
    @Override public boolean available() { return (connected && outsideC != null) || (obd != null && obd.connected()); }
}
