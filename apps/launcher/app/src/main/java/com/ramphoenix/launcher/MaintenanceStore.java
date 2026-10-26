package com.ramphoenix.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maintenance tracker data (docs/WISHLIST.md): service items with mileage and
 * month intervals, when each was last done, and the current odometer. Stored as
 * JSON in SharedPreferences; export/import to a JSON file for backup.
 *
 * Odometer: the head unit's bus does not carry it on this truck, so it is
 * entered by hand (or taken from the OBD link when the ECU answers PID 01 A6).
 */
public class MaintenanceStore {

    public static final class Item {
        public String id, name, notes = "";
        public int intervalMiles, intervalMonths;     // 0 = not tracked by that measure
        public int lastMiles = -1;                     // -1 = never recorded
        public long lastDate = 0;                      // epoch ms, 0 = never
        Item(String id, String name, int miles, int months) { this.id = id; this.name = name; intervalMiles = miles; intervalMonths = months; }

        /** Miles until due, or null when untracked / never done. */
        public Integer milesLeft(int odo) { return (intervalMiles <= 0 || lastMiles < 0 || odo < 0) ? null : lastMiles + intervalMiles - odo; }
        /** Days until due, or null. */
        public Integer daysLeft(long now) { return (intervalMonths <= 0 || lastDate <= 0) ? null : (int) ((lastDate + intervalMonths * 30.44 * 86400000L - now) / 86400000L); }
        /** 0 ok, 1 due soon, 2 overdue, -1 unknown. */
        public int status(int odo, long now) {
            Integer m = milesLeft(odo), d = daysLeft(now);
            if (m == null && d == null) return -1;
            if ((m != null && m < 0) || (d != null && d < 0)) return 2;
            if ((m != null && m <= Math.max(500, intervalMiles / 10)) || (d != null && d <= 30)) return 1;
            return 0;
        }
    }

    private static final String PREFS = "maintenance";
    private final SharedPreferences prefs;
    private final List<Item> items = new ArrayList<>();
    private int odometer = -1;

    public MaintenanceStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public List<Item> items() { return items; }
    public int odometer() { return odometer; }
    public void setOdometer(int miles) { odometer = miles; save(); }

    public Item byId(String id) { for (Item i : items) if (i.id.equals(id)) return i; return null; }

    public void markDone(Item it, int miles, long date) { it.lastMiles = miles; it.lastDate = date; save(); }
    public void add(Item it) { items.add(it); save(); }
    public void remove(Item it) { items.remove(it); save(); }
    public void save() { prefs.edit().putString("json", toJson().toString()).apply(); }

    /** Next item due by miles or days, formatted for the vehicle card ("OIL 1,240 MI"), or null. */
    public String nextDueText() {
        long now = System.currentTimeMillis();
        Item best = null; double bestScore = Double.MAX_VALUE;
        for (Item it : items) {
            Integer m = it.milesLeft(odometer), d = it.daysLeft(now);
            double score = Math.min(m == null ? Double.MAX_VALUE : m / Math.max(1.0, it.intervalMiles),
                                    d == null ? Double.MAX_VALUE : d / Math.max(1.0, it.intervalMonths * 30.44));
            if (score < bestScore) { bestScore = score; best = it; }
        }
        if (best == null) return null;
        Integer m = best.milesLeft(odometer), d = best.daysLeft(now);
        String name = shortName(best.name);
        if (m != null && (d == null || m / Math.max(1.0, best.intervalMiles) <= d / Math.max(1.0, best.intervalMonths * 30.44))) {
            return m < 0 ? name + " OVERDUE" : String.format(Locale.US, "%s %,d MI", name, m);
        }
        if (d != null) return d < 0 ? name + " OVERDUE" : String.format(Locale.US, "%s %d D", name, d);
        return null;
    }

    private static String shortName(String n) {
        String u = n.toUpperCase(Locale.US);
        if (u.startsWith("ENGINE OIL")) return "OIL";
        if (u.startsWith("FUEL FILTER")) return "FUEL FLT";
        if (u.startsWith("TIRE")) return "TIRES";
        if (u.startsWith("AIR FILTER")) return "AIR FLT";
        if (u.startsWith("TRANSMISSION")) return "TRANS";
        if (u.startsWith("TRANSFER")) return "T-CASE";
        return u.length() > 9 ? u.substring(0, 9) : u;
    }

    // ---- persistence ----

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("odometer", odometer);
            JSONArray a = new JSONArray();
            for (Item it : items) {
                JSONObject j = new JSONObject();
                j.put("id", it.id); j.put("name", it.name); j.put("notes", it.notes);
                j.put("intervalMiles", it.intervalMiles); j.put("intervalMonths", it.intervalMonths);
                j.put("lastMiles", it.lastMiles); j.put("lastDate", it.lastDate);
                a.put(j);
            }
            o.put("items", a);
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    public void fromJson(JSONObject o) {
        items.clear();
        odometer = o.optInt("odometer", -1);
        JSONArray a = o.optJSONArray("items");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Item it = new Item(j.optString("id", "item" + i), j.optString("name", "?"), j.optInt("intervalMiles"), j.optInt("intervalMonths"));
            it.notes = j.optString("notes", ""); it.lastMiles = j.optInt("lastMiles", -1); it.lastDate = j.optLong("lastDate", 0);
            items.add(it);
        }
        save();
    }

    private void load() {
        String json = prefs.getString("json", null);
        if (json != null) {
            try { fromJson(new JSONObject(json)); return; } catch (Exception ignored) {}
        }
        // Defaults for a 2019+ RAM Cummins (edit freely in the app).
        items.add(new Item("oil", "Engine oil & filter", 15000, 12));
        items.add(new Item("fuel", "Fuel filters (both)", 15000, 12));
        items.add(new Item("tires", "Tire rotation", 10000, 6));
        items.add(new Item("air", "Air filter", 30000, 24));
        items.add(new Item("def", "DEF top-up", 5000, 0));
        items.add(new Item("trans", "Transmission fluid & filter", 60000, 0));
        items.add(new Item("diff", "Axle & transfer case fluids", 30000, 0));
        items.add(new Item("coolant", "Coolant", 100000, 60));
        items.add(new Item("brake", "Brake fluid", 0, 36));
        save();
    }
}
