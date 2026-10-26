package com.ramphoenix.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which cards the FULL dashboard shows and in what order (Beau, 2026-10-26:
 * "custom cards instead of the fixed set"). Stored as a comma list in prefs.
 */
public final class CardsConfig {
    private CardsConfig() {}

    public static final String MAP = "map", MEDIA = "media", VEHICLE = "vehicle", CLIMATE = "climate",
            PHONE = "phone", OBD = "obd", APPS = "apps";
    public static final String[] ALL = {MAP, MEDIA, VEHICLE, CLIMATE, PHONE, OBD, APPS};
    private static final String DEFAULT = "map,media,vehicle,climate";

    public static List<String> enabled(Context ctx) {
        String v = prefs(ctx).getString("cards", DEFAULT);
        List<String> out = new ArrayList<>();
        for (String k : v.split(",")) if (Arrays.asList(ALL).contains(k.trim())) out.add(k.trim());
        return out;
    }

    public static void save(Context ctx, List<String> keys) {
        prefs(ctx).edit().putString("cards", String.join(",", keys)).apply();
    }

    /** Weight of a card in the FULL column; 0 = wrap its content. */
    public static float weight(String key) {
        switch (key) {
            case MAP: return 2.5f;
            case MEDIA: return 2.6f;
            case VEHICLE: return 2.0f;
            case PHONE: return 1.1f;
            case OBD: return 1.5f;
            case APPS: return 2.0f;
            default: return 0f;
        }
    }

    public static int label(String key) {
        switch (key) {
            case MAP: return R.string.card_map;
            case MEDIA: return R.string.card_media;
            case VEHICLE: return R.string.card_vehicle;
            case CLIMATE: return R.string.card_climate;
            case PHONE: return R.string.card_phone;
            case OBD: return R.string.card_obd;
            default: return R.string.card_apps;
        }
    }

    private static SharedPreferences prefs(Context ctx) { return ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE); }

    /** Checkbox + up/down per card; applies on OK. */
    public static void showDialog(final Activity act, final Runnable onChanged) {
        final List<String> order = new ArrayList<>(enabled(act));
        for (String k : ALL) if (!order.contains(k)) order.add(k);
        final Map<String, Boolean> on = new LinkedHashMap<>();
        for (String k : order) on.put(k, enabled(act).contains(k));

        final float d = act.getResources().getDisplayMetrics().density;
        final LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Math.round(12 * d), Math.round(8 * d), Math.round(12 * d), 0);
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < order.size(); i++) {
                final String key = order.get(i);
                final int idx = i;
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                CheckBox cb = new CheckBox(act);
                cb.setText(act.getString(label(key)));
                cb.setChecked(Boolean.TRUE.equals(on.get(key)));
                cb.setOnCheckedChangeListener((b, checked) -> on.put(key, checked));
                row.addView(cb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                ImageView up = new ImageView(act); up.setImageResource(R.drawable.ic_m_keyboard_arrow_up);
                ImageView down = new ImageView(act); down.setImageResource(R.drawable.ic_m_keyboard_arrow_down);
                int sz = Math.round(40 * d), pad = Math.round(8 * d);
                up.setPadding(pad, pad, pad, pad); down.setPadding(pad, pad, pad, pad);
                up.setAlpha(idx == 0 ? 0.25f : 1f); down.setAlpha(idx == order.size() - 1 ? 0.25f : 1f);
                up.setOnClickListener(v -> { if (idx > 0) { order.add(idx - 1, order.remove(idx)); rebuild[0].run(); } });
                down.setOnClickListener(v -> { if (idx < order.size() - 1) { order.add(idx + 1, order.remove(idx)); rebuild[0].run(); } });
                row.addView(up, new LinearLayout.LayoutParams(sz, sz));
                row.addView(down, new LinearLayout.LayoutParams(sz, sz));
                list.addView(row);
            }
        };
        rebuild[0].run();
        ScrollView sv = new ScrollView(act);
        sv.addView(list);
        new AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.cards_title)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, (dlg, w) -> {
                    List<String> keys = new ArrayList<>();
                    for (String k : order) if (Boolean.TRUE.equals(on.get(k))) keys.add(k);
                    save(act, keys);
                    onChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
