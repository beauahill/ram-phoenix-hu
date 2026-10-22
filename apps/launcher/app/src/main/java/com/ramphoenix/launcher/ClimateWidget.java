package com.ramphoenix.launcher;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

/**
 * Climate card: the stock climate panel minus the rear-zone controls, driven
 * over the SYU ToolkitService IPC (docs/CANBUS-IPC.md, RAM 1500 profile):
 *
 *   read  : register MODULE_CANBUS update codes, keep the latest ints[0] per code
 *   write : cmd(MODULE_CANBUS, 0, {24, BUTTON}) on press, cmd(.., {24}) on release
 *
 * Code 27 (driver temp) was confirmed live on 2026-10-21; the rest of the map is
 * from the decompile and is marked VERIFY in the doc. Until the IPC is connected
 * the card renders dimmed with "NO CAN BUS".
 */
public class ClimateWidget {

    private static final String TAG = "PhoenixLauncher";

    // ---- MODULE_CANBUS update codes (read) ----
    static final int C_POWER = 10, C_AC = 11, C_RECIRC = 12, C_AUTO = 13, C_REAR_DEF = 16,
            C_VENT_UP = 18, C_VENT_BODY = 19, C_VENT_FOOT = 20, C_FAN = 21,
            C_TEMP_L = 27, C_TEMP_R = 28, C_SEAT_HEAT_L = 29, C_SEAT_HEAT_R = 30,
            C_SEAT_VENT_L = 31, C_SEAT_VENT_R = 32, C_TEMP_UNIT = 37, C_AC_MAX = 53,
            C_SYNC = 62, C_FRONT_DEF = 65, C_HEAT_WHEEL = 66;
    private static final int[] CODES = {C_POWER, C_AC, C_RECIRC, C_AUTO, C_REAR_DEF, C_VENT_UP,
            C_VENT_BODY, C_VENT_FOOT, C_FAN, C_TEMP_L, C_TEMP_R, C_SEAT_HEAT_L, C_SEAT_HEAT_R,
            C_SEAT_VENT_L, C_SEAT_VENT_R, C_TEMP_UNIT, C_AC_MAX, C_SYNC, C_FRONT_DEF, C_HEAT_WHEEL};

    // ---- button ids (write), ConstAllAirDara for CAR_455_LZ_Dodge_RAM1500 ----
    static final int B_TEMP_L_UP = 1, B_TEMP_L_DN = 2, B_TEMP_R_UP = 3, B_TEMP_R_DN = 4,
            B_FAN_UP = 5, B_FAN_DN = 6, B_POWER = 7, B_SYNC = 8, B_AC_MAX = 9, B_AC = 10,
            B_RECIRC = 11, B_AUTO = 12, B_FRONT_DEF = 13, B_REAR_DEF = 14,
            B_MODE_FACE = 15, B_MODE_FACE_FEET = 16, B_MODE_FEET = 17, B_MODE_FEET_DEF = 18,
            B_SEAT_HEAT_R = 20, B_SEAT_VENT_R = 21, B_SEAT_HEAT_L = 22, B_SEAT_VENT_L = 23,
            B_HEAT_WHEEL = 24;
    private static final int CMD_CONTROL = 0, CMD_BUTTON = 24;
    private static final long RELEASE_MS = 80;

    private final Activity act;
    private final View card;
    private final SyuIpc ipc;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SparseIntArray state = new SparseIntArray();
    private boolean connected;

    private final TextView status, tempL, tempR, fan, heatL, heatR, ventL, ventR;
    private final TextView power, auto, sync, ac, acMax, frontDef, rearDef, recirc, wheel;
    private final TextView modeFace, modeFaceFeet, modeFeet, modeFeetDef;

    public ClimateWidget(Activity act, View card) {
        this.act = act;
        this.card = card;
        ipc = new SyuIpc(act);
        ipc.setConnectionListener(c -> { connected = c; render(); });
        for (int code : CODES) {
            ipc.subscribe(SyuIpc.MODULE_CANBUS, code, (c, ints, flts, strs) -> {
                if (ints != null && ints.length > 0) {
                    state.put(c, ints[0]);
                    ui.post(this::render);
                }
            });
        }

        status = card.findViewById(R.id.climate_status);
        power = chip(R.id.cl_power, B_POWER);
        auto = chip(R.id.cl_auto, B_AUTO);
        sync = chip(R.id.cl_sync, B_SYNC);
        ac = chip(R.id.cl_ac, B_AC);
        acMax = chip(R.id.cl_acmax, B_AC_MAX);
        frontDef = chip(R.id.cl_fdef, B_FRONT_DEF);
        rearDef = chip(R.id.cl_rdef, B_REAR_DEF);
        recirc = chip(R.id.cl_recirc, B_RECIRC);
        wheel = chip(R.id.cl_wheel, B_HEAT_WHEEL);

        heatL = chip(R.id.cl_heat_l, B_SEAT_HEAT_L);
        ventL = chip(R.id.cl_vent_l, B_SEAT_VENT_L);
        chip(R.id.cl_tl_dn, B_TEMP_L_DN);
        tempL = card.findViewById(R.id.cl_temp_l);
        chip(R.id.cl_tl_up, B_TEMP_L_UP);
        chip(R.id.cl_tr_dn, B_TEMP_R_DN);
        tempR = card.findViewById(R.id.cl_temp_r);
        chip(R.id.cl_tr_up, B_TEMP_R_UP);
        ventR = chip(R.id.cl_vent_r, B_SEAT_VENT_R);
        heatR = chip(R.id.cl_heat_r, B_SEAT_HEAT_R);

        chip(R.id.cl_fan_dn, B_FAN_DN);
        fan = card.findViewById(R.id.cl_fan);
        chip(R.id.cl_fan_up, B_FAN_UP);
        modeFace = chip(R.id.cl_mode_face, B_MODE_FACE);
        modeFaceFeet = chip(R.id.cl_mode_facefeet, B_MODE_FACE_FEET);
        modeFeet = chip(R.id.cl_mode_feet, B_MODE_FEET);
        modeFeetDef = chip(R.id.cl_mode_feetdef, B_MODE_FEET_DEF);
        render();
    }

    private TextView chip(int id, final int button) {
        TextView v = card.findViewById(id);
        v.setOnClickListener(x -> press(button));
        return v;
    }

    public void onResume() { ipc.connect(); }
    public void onPause() { ipc.disconnect(); connected = false; render(); }

    /** Press then release, the way the stock panel sends ACTION_DOWN / ACTION_UP. */
    private void press(final int button) {
        if (!connected) { Log.w(TAG, "climate press " + button + " ignored: no IPC"); return; }
        boolean ok = ipc.cmd(SyuIpc.MODULE_CANBUS, CMD_CONTROL, new int[]{CMD_BUTTON, button});
        Log.i(TAG, "climate press " + button + (ok ? "" : " FAILED"));
        ui.postDelayed(() -> ipc.cmd(SyuIpc.MODULE_CANBUS, CMD_CONTROL, new int[]{CMD_BUTTON}), RELEASE_MS);
    }

    // ---- rendering ----

    private int v(int code) { return state.get(code, -1); }
    private boolean on(int code) { return v(code) > 0; }

    private void render() {
        card.setAlpha(connected ? 1f : 0.55f);
        boolean power = on(C_POWER);
        status.setText(!connected ? "NO CAN BUS" : (power ? "" : "OFF"));

        toggle(this.power, power);
        toggle(auto, on(C_AUTO));
        toggle(sync, on(C_SYNC));
        toggle(ac, on(C_AC));
        toggle(acMax, on(C_AC_MAX));
        toggle(frontDef, on(C_FRONT_DEF));
        toggle(rearDef, on(C_REAR_DEF));
        toggle(recirc, on(C_RECIRC));
        toggle(wheel, on(C_HEAT_WHEEL));

        tempL.setText(temp(v(C_TEMP_L)));
        tempR.setText(temp(v(C_TEMP_R)));
        level(heatL, "HEAT", v(C_SEAT_HEAT_L));
        level(heatR, "HEAT", v(C_SEAT_HEAT_R));
        level(ventL, "VENT", v(C_SEAT_VENT_L));
        level(ventR, "VENT", v(C_SEAT_VENT_R));

        int f = v(C_FAN);
        StringBuilder bars = new StringBuilder();
        for (int i = 1; i <= 8; i++) bars.append(i <= f ? '█' : '░');
        fan.setText(f < 0 ? Fmt.NONE : bars.toString());

        boolean up = on(C_VENT_UP), body = on(C_VENT_BODY), foot = on(C_VENT_FOOT);
        toggle(modeFace, body && !foot && !up);
        toggle(modeFaceFeet, body && foot);
        toggle(modeFeet, foot && !body && !up);
        toggle(modeFeetDef, foot && up);
    }

    private static void toggle(TextView chip, boolean on) {
        chip.setSelected(on);
        chip.setAlpha(on ? 1f : 0.7f);
    }

    private static void level(TextView chip, String label, int lvl) {
        if (lvl < 0) { chip.setText(label); chip.setSelected(false); chip.setAlpha(0.7f); return; }
        StringBuilder s = new StringBuilder(label).append(' ');
        for (int i = 1; i <= 3; i++) s.append(i <= lvl ? '●' : '○');
        chip.setText(s);
        chip.setSelected(lvl > 0);
        chip.setAlpha(lvl > 0 ? 1f : 0.7f);
    }

    /** Raw temp encoding from the decompile: -1 none, -2 LO, -3 HI; else
     *  °F = (v*9+320)/10 when code 37 says Fahrenheit, otherwise °C = v/2. */
    private String temp(int raw) {
        if (raw == -1 || raw == Integer.MIN_VALUE) return Fmt.NONE;
        if (raw == -2) return "LO";
        if (raw == -3) return "HI";
        if (v(C_TEMP_UNIT) == 1) return String.format(Locale.US, "%d°", (raw * 9 + 320) / 10);
        return String.format(Locale.US, "%.1f°", raw / 2f);
    }
}
