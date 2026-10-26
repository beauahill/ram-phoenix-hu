package com.ramphoenix.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

/**
 * Phone / Bluetooth card over the vendor BT module (module 2) updates:
 * 7 phone name, 9 phone state (0 disconnected, 1 linking, 2 connected, 3 dialling,
 * 4 ringing, 5 in call, 6 pairing), 34 A2DP state. Dial opens the stock BT app.
 */
public class PhoneWidget {
    private final Activity act;
    private final SyuIpc ipc;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TextView name, state;
    private final View icon;
    private String phoneName; private int phoneState = -1; private boolean connected;

    public PhoneWidget(Activity act, View card) {
        this.act = act;
        name = card.findViewById(R.id.ph_name);
        state = card.findViewById(R.id.ph_state);
        icon = card.findViewById(R.id.ph_icon);
        card.findViewById(R.id.ph_dial).setOnClickListener(v -> {
            Intent i = Dock.phoneIntent(act);
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { act.startActivity(i); } catch (Exception ignored) {} }
        });
        ipc = new SyuIpc(act);
        ipc.setConnectionListener(c -> { connected = c; ui.post(this::render); });
        ipc.subscribe(SyuIpc.MODULE_BT, 7, (code, ints, f, s) -> { phoneName = s != null && s.length > 0 ? s[0] : null; ui.post(this::render); });
        ipc.subscribe(SyuIpc.MODULE_BT, 9, (code, ints, f, s) -> { phoneState = ints != null && ints.length > 0 ? ints[0] : -1; ui.post(this::render); });
        render();
    }

    public void onResume() { ipc.connect(); }
    public void onPause() { ipc.disconnect(); connected = false; render(); }

    private void render() {
        boolean linked = phoneState >= 2;
        name.setText(phoneName != null && !phoneName.isEmpty() && linked ? phoneName : act.getString(R.string.phone));
        String st;
        switch (phoneState) {
            case 0: st = act.getString(R.string.bt_not_connected); break;
            case 1: st = "Connecting…"; break;
            case 2: st = "Connected"; break;
            case 3: st = "Dialling…"; break;
            case 4: st = "Incoming call"; break;
            case 5: st = "In call"; break;
            case 6: st = "Pairing…"; break;
            default: st = connected ? act.getString(R.string.bt_not_connected) : "NO BLUETOOTH MODULE";
        }
        state.setText(st);
        icon.setAlpha(linked ? 1f : 0.5f);
    }
}
