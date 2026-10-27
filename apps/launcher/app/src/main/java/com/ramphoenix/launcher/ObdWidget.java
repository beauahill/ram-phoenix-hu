package com.ramphoenix.launcher;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vehicle data card: coolant, trans, oil pressure, factory/OBD oil temp, voltage, plus any extra
 * PIDs configured as persist.phoenix.obd.custom1..4 ("LABEL,request,formula,unit").
 */
public class ObdWidget {
    private final Activity act;
    private final ObdClient obd;
    private final VehicleData vehicle;
    private final LinearLayout tiles;
    private final TextView status;
    private final List<TextView[]> cells = new ArrayList<>();

    public ObdWidget(Activity act, View card, ObdClient obd, VehicleData vehicle) {
        this.act = act; this.obd = obd; this.vehicle = vehicle;
        tiles = card.findViewById(R.id.obd_tiles);
        status = card.findViewById(R.id.obd_status);
        render();
    }

    public void render() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"COOLANT", f(obd.coolantC(), true)});
        rows.add(new String[]{"TRANS", f(obd.transC(), true)});
        rows.add(new String[]{"OIL PSI", obd.oilPsi() == null ? Fmt.NONE : String.format(Locale.US, "%.0f", obd.oilPsi())});
        rows.add(new String[]{"OIL TEMP", f(vehicle.oilTempC(), true)});
        rows.add(new String[]{"VOLTS", Fmt.volts(obd.volts())});
        for (java.util.Map.Entry<String, String> e : obd.customUnit.entrySet()) {
            Float v = obd.custom.get(e.getKey());
            rows.add(new String[]{e.getKey().toUpperCase(Locale.US), v == null ? Fmt.NONE : String.format(Locale.US, "%.0f%s", v, e.getValue())});
        }
        while (cells.size() < rows.size()) cells.add(addTile());
        for (int i = 0; i < cells.size(); i++) {
            TextView[] c = cells.get(i);
            View tile = (View) c[0].getParent();
            if (i < rows.size()) { tile.setVisibility(View.VISIBLE); c[0].setText(rows.get(i)[1]); c[1].setText(rows.get(i)[0]); }
            else tile.setVisibility(View.GONE);
        }
        status.setText(obd.connected() ? "OBDLINK" : obd.status().replace("OBD: ", "").toUpperCase(Locale.US));
    }

    private TextView[] addTile() {
        float d = act.getResources().getDisplayMetrics().density;
        LinearLayout t = new LinearLayout(act);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.glass_tile_sq);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.leftMargin = lp.rightMargin = Math.round(3 * d);
        TextView value = new TextView(act);
        value.setTextColor(act.getColor(R.color.text_primary)); value.setTextSize(22); value.setGravity(Gravity.CENTER);
        TextView label = new TextView(act);
        label.setTextColor(act.getColor(R.color.text_tertiary)); label.setTextSize(9); label.setLetterSpacing(0.12f); label.setGravity(Gravity.CENTER);
        t.addView(value); t.addView(label);
        tiles.addView(t, lp);
        return new TextView[]{value, label};
    }

    private static String f(Float c, boolean temp) { return temp ? Fmt.tempF(c) : (c == null ? Fmt.NONE : String.valueOf(c)); }
}
