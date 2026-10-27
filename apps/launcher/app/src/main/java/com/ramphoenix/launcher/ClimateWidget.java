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
 *   write : cmd(MODULE_CANBUS, 6, {1, BUTTON}) on press (PA RAM profile, live 2026-10-25)
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

    // ---- button ids (write) ----
    // This truck's profile is a "PA" RAM box, not the LZ one the docs assumed: the
    // stock panel the unit opens for it is com.syu.carinfo.xp.ziyouguang.
    // PAJeepAirControlAct_SP, and pressing driver seat heat there was logged by
    // com.syu.ms as `cmd cmdid = 6 [1, 17] from com.syu.canbus` (live 2026-10-25).
    // Codes from that activity's onTouch: sent as cmd(CANBUS, 6, {1, code}) on
    // ACTION_DOWN only, no release packet.
    static final int B_AC = 1, B_AUTO = 2, B_RECIRC = 3, B_TEMP_L_UP = 4, B_TEMP_L_DN = 5,
            B_FAN_UP = 6, B_FAN_DN = 7, B_MODE_FACE = 8, B_MODE_FACE_FEET = 9, B_MODE_FEET = 10,
            B_MODE_FEET_DEF = 11, B_FRONT_DEF = 12, B_SYNC = 13, B_REAR_DEF = 14, B_AC_MAX = 15,
            B_POWER = 16, B_SEAT_HEAT_L = 17, B_SEAT_HEAT_R = 18, B_TEMP_R_UP = 20, B_TEMP_R_DN = 21,
            B_SEAT_VENT_L = 22, B_SEAT_VENT_R = 23, B_HEAT_WHEEL = 24;
    private static final int CMD_AIR = 6, AIR_FRONT_ZONE = 1;

    private final Activity act;
    private final View card;
    private final SyuIpc ipc;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SparseIntArray state = new SparseIntArray();
    private boolean connected;

    private final TextView status, tempL, tempR, heatL, heatR, ventL, ventR;
    private final View power, rearDef, wheel;
    private final TextView auto, sync, ac, acMax;
    private final View frontDef, recirc, modeFace, modeFaceFeet, modeFeet, modeFeetDef;
    private final android.widget.SeekBar fan;
    private boolean fanDragging;

    public ClimateWidget(Activity act, View card) {
        this.act = act;
        this.card = card;
        ipc = new SyuIpc(act);
        ipc.setConnectionListener(c -> { connected = c; render(); });
        for (int code : CODES) {
            ipc.subscribe(SyuIpc.MODULE_CANBUS, code, (c, ints, flts, strs) -> {
                if (ints != null && ints.length > 0) {
                    final int value = ints[0];
                    ui.post(() -> {
                        if (state.get(c, Integer.MIN_VALUE) != value) Log.i(TAG, "climate update " + c + " -> " + value);
                        state.put(c, value);
                        render();
                    });
                }
            });
        }

        status = card.findViewById(R.id.climate_status);
        power = button(R.id.cl_power, B_POWER);
        auto = chip(R.id.cl_auto, B_AUTO);
        sync = chip(R.id.cl_sync, B_SYNC);
        ac = chip(R.id.cl_ac, B_AC);
        acMax = chip(R.id.cl_acmax, B_AC_MAX);
        frontDef = button(R.id.cl_fdef, B_FRONT_DEF);
        rearDef = button(R.id.cl_rdef, B_REAR_DEF);
        recirc = button(R.id.cl_recirc, B_RECIRC);
        wheel = button(R.id.cl_wheel, B_HEAT_WHEEL);

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

        button(R.id.cl_fan_dn, B_FAN_DN);
        button(R.id.cl_fan_up, B_FAN_UP);
        fan = card.findViewById(R.id.cl_fan);
        if (fan != null) fan.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { fanDragging = true; }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {
                fanDragging = false;
                stepFan(sb.getProgress());
            }
        });
        modeFace = button(R.id.cl_mode_face, B_MODE_FACE);
        modeFaceFeet = button(R.id.cl_mode_facefeet, B_MODE_FACE_FEET);
        modeFeet = button(R.id.cl_mode_feet, B_MODE_FEET);
        modeFeetDef = button(R.id.cl_mode_feetdef, B_MODE_FEET_DEF);

        expanded = card.findViewById(R.id.climate_expanded);
        expandBtn = card.findViewById(R.id.climate_expand);
        if (expandBtn != null) {
            prefs = act.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE);
            setExpanded(prefs.getBoolean("climate_open", false));
            expandBtn.setOnClickListener(v -> {
                boolean open = expanded.getVisibility() != View.VISIBLE;
                prefs.edit().putBoolean("climate_open", open).apply();
                setExpanded(open);
            });
        }
        View full = card.findViewById(R.id.climate_full);
        if (full != null) full.setOnClickListener(v ->
                act.startActivity(new android.content.Intent(act, ClimateActivity.class)));
        render();
    }

    private View expanded;
    private android.widget.ImageView expandBtn;
    private android.content.SharedPreferences prefs;

    private void setExpanded(boolean open) {
        if (expanded == null) return;
        expanded.setVisibility(open ? View.VISIBLE : View.GONE);
        expandBtn.setImageResource(open ? R.drawable.ic_m_keyboard_arrow_down : R.drawable.ic_m_keyboard_arrow_up);
        expandBtn.setContentDescription(open ? "Hide extra climate controls" : "Show more climate controls");
    }

    private TextView chip(int id, final int button) {
        TextView v = card.findViewById(id);
        if (v != null) v.setOnClickListener(x -> press(button));
        return v;
    }

    private View button(int id, final int button) {
        View v = card.findViewById(id);
        if (v != null) v.setOnClickListener(x -> press(button));
        return v;
    }

    private static void sel(View v, boolean on) { if (v != null) v.setSelected(on); }
    private static void txt(TextView v, CharSequence t) { if (v != null) v.setText(t); }

    /** The bus only has fan +/- buttons: walk from the current level to the target. */
    private void stepFan(int target) {
        cancelFanSteps();
        int cur = v(C_FAN);
        if (cur < 0) return;
        int delta = target - cur;
        int btn = delta > 0 ? B_FAN_UP : B_FAN_DN;
        for (int i = 0; i < Math.abs(delta); i++) {
            Runnable step = () -> press(btn);
            fanSteps.add(step);
            ui.postDelayed(step, i * 180L);
        }
    }

    private final java.util.List<Runnable> fanSteps = new java.util.ArrayList<>();

    private void cancelFanSteps() {
        for (Runnable step : fanSteps) ui.removeCallbacks(step);
        fanSteps.clear();
    }

    public void onResume() { ipc.connect(); }
    public void onPause() { cancelFanSteps(); ipc.disconnect(); connected = false; render(); }

    /** One packet per press, exactly what the stock PA panel sends. */
    private void press(final int button) {
        if (!connected) { Log.w(TAG, "climate press " + button + " ignored: no IPC"); return; }
        boolean ok = ipc.cmd(SyuIpc.MODULE_CANBUS, CMD_AIR, new int[]{AIR_FRONT_ZONE, button});
        Log.i(TAG, "climate press " + button + (ok ? "" : " FAILED"));
    }

    /** Raw state for other views (the full-screen climate page draws its own temp bars). */
    public int raw(int code) { return v(code); }
    public boolean isConnected() { return connected; }

    // ---- rendering ----

    private int v(int code) { return state.get(code, -1); }
    private boolean on(int code) { return v(code) > 0; }

    private void render() {
        card.setAlpha(connected ? 1f : 0.55f);
        boolean power = on(C_POWER);
        txt(status, !connected ? "NO CAN BUS" : v(C_POWER) < 0 ? "WAITING FOR VEHICLE" : (power ? "" : "OFF"));

        sel(this.power, power);
        toggle(auto, on(C_AUTO));
        toggle(sync, on(C_SYNC));
        toggle(ac, on(C_AC));
        toggle(acMax, on(C_AC_MAX));
        sel(frontDef, on(C_FRONT_DEF));
        sel(rearDef, on(C_REAR_DEF));
        sel(recirc, on(C_RECIRC));
        sel(wheel, on(C_HEAT_WHEEL));

        txt(tempL, temp(v(C_TEMP_L)));
        txt(tempR, temp(v(C_TEMP_R)));
        level(heatL, true, true, v(C_SEAT_HEAT_L));
        level(heatR, false, true, v(C_SEAT_HEAT_R));
        level(ventL, true, false, v(C_SEAT_VENT_L));
        level(ventR, false, false, v(C_SEAT_VENT_R));

        int f = v(C_FAN);
        TextView fanValue = card.findViewById(R.id.cl_fan_value);
        txt(fanValue, f < 0 ? Fmt.NONE : on(C_AUTO) ? "AUTO" : Integer.toString(f));
        if (fan != null) {
            if (!fanDragging) fan.setProgress(Math.max(0, Math.min(7, f)));
            fan.setEnabled(f >= 0);
        }

        boolean up = on(C_VENT_UP), body = on(C_VENT_BODY), foot = on(C_VENT_FOOT);
        sel(modeFace, body && !foot && !up);
        sel(modeFaceFeet, body && foot);
        sel(modeFeet, foot && !body && !up);
        sel(modeFeetDef, foot && up);
    }

    private static void toggle(TextView chip, boolean on) {
        if (chip == null) return;
        chip.setSelected(on);
        chip.setAlpha(1f);
    }

    /** Seat controls use a short label and a readable level, with warm/cool state. */
    private static void level(TextView chip, boolean left, boolean heat, int lvl) {
        if (chip == null) return;
        int l = Math.max(0, Math.min(3, lvl));
        String label = heat ? "HEAT" : "VENT";
        chip.setText(label + "\n" + (lvl < 0 ? Fmt.NONE : l == 0 ? "OFF" : Integer.toString(l)));
        chip.setContentDescription((left ? "Driver" : "Passenger") + " seat "
                + (heat ? "heat" : "ventilation") + (lvl < 0 ? " unavailable" : " level " + l));
        int color = chip.getContext().getColor(l > 0 ? (heat ? R.color.accent : R.color.control_cool) : R.color.text_secondary);
        chip.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color));
        chip.setTextColor(color);
        chip.setSelected(l > 0);
        chip.setAlpha(l > 0 ? 1f : 0.8f);
    }

    /** Temp encoding as the stock PA panel renders it (PAJeepAirControlAct_SP.mUpdateAirTempLeft):
     *  -1 none, -2 LO, -3 HI; valid raw 20..128; code 37 = 1 -> raw is °F as-is, else °C = raw/2. */
    private String temp(int raw) {
        if (raw == -1 || raw == Integer.MIN_VALUE) return Fmt.NONE;
        if (raw == -2) return "LO";
        if (raw == -3) return "HI";
        if (raw < 20 || raw > 128) return Fmt.NONE;
        if (v(C_TEMP_UNIT) == 1) return String.format(Locale.US, "%d°", raw);
        return String.format(Locale.US, "%.1f°", raw / 2f);
    }
}
