package com.ramphoenix.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Vehicle data from the SYU ToolkitService and the truck's verified OBD profiles.
 *
 * What this unit's MCU/canbus layer actually decodes for CAR_455_LZ_Dodge_RAM1500
 * (decompile of com.syu.ms k0.vr.U2 + com.syu.canbus Callback_0455, 2026-10-25):
 *   MAIN 40    outside temperature: (v & 0xFFFF) is tenths of a degree + 1000,
 *              bit 28 set = Fahrenheit (live: 268437096 -> 1640 -> 64 F, matched the cluster)
 *   CANBUS 1031 speed, raw byte from frame 0x35 (units unverified, probably km/h)
 *   CANBUS 0..5 doors: hood, FL, FR, RL, RR, tailgate (1 = open)
 *   CANBUS 1052 every raw frame from the canbus box (logged at debug for discovery)
 * Fuel level, coolant, battery voltage, TPMS, odometer and RPM are not decoded by
 * this vendor profile. Available engine/pressure values are delegated to ObdClient;
 * unsupported values remain null.
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
    private final boolean[] doors = new boolean[6];

    public CanbusVehicleData(Context ctx, Runnable onChange) {
        this.onChange = onChange;
        ipc = new SyuIpc(ctx);
        ipc.setConnectionListener(c -> {
            connected = c; changed();
            if (c) ui.postDelayed(() -> {
                int[] id = ipc.getInts(SyuIpc.MODULE_CANBUS, 1000);
                Log.i(TAG, "canbus profile id (DATA[1000]) = " + (id != null && id.length > 0 ? id[0] : -1));
            }, 500);
        });
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

    public void connect() { ipc.connect(); }
    public void disconnect() { ipc.disconnect(); connected = false; }

    public boolean doorOpen(int idx) { return idx >= 0 && idx < 6 && doors[idx]; }
    public boolean anyDoorOpen() { for (boolean d : doors) if (d) return true; return false; }

    @Override public Float tirePsi(int corner) { return obd != null ? obd.tirePsi(corner) : null; }
    @Override public Float fuelPercent() { return null; }
    @Override public Float coolantC() { return obd != null ? obd.coolantC() : null; }
    @Override public Float batteryVolts() { return obd != null ? obd.volts() : null; }
    @Override public Float transC() { return obd != null ? obd.transC() : null; }
    @Override public Float oilPsi() { return obd != null ? obd.oilPsi() : null; }
    @Override public Float oilTempC() { return obd != null ? obd.oilTempC() : null; }
    @Override public int doorsOpen() { if (!connected) return -1; int n = 0; for (boolean d : doors) if (d) n++; return n; }
    @Override public Float outsideC() { return outsideC; }
    @Override public Float speedKph() { return speedKph; }
    @Override public Float rpm() { return null; }
    @Override public boolean available() { return (connected && outsideC != null) || (obd != null && obd.connected()); }
}
