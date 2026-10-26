package com.ramphoenix.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Maintenance tracker: odometer, service items with due state, mark done, edit, export. */
public class MaintenanceActivity extends Activity {

    private MaintenanceStore store;
    private LinearLayout list;
    private TextView odoText;
    private final SimpleDateFormat df = new SimpleDateFormat("MMM d, yyyy", Locale.US);

    @Override protected void attachBaseContext(Context base) { super.attachBaseContext(base); Ui.applyDensity(this); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_maintenance);
        store = new MaintenanceStore(this);
        list = findViewById(R.id.maint_list);
        odoText = findViewById(R.id.maint_odo);
        findViewById(R.id.maint_back).setOnClickListener(v -> finish());
        findViewById(R.id.maint_odo_row).setOnClickListener(v -> askNumber(getString(R.string.maint_odometer), store.odometer(), n -> { store.setOdometer(n); refresh(); }));
        findViewById(R.id.maint_add).setOnClickListener(v -> editItem(null));
        findViewById(R.id.maint_export).setOnClickListener(v -> export());
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        int odo = store.odometer();
        odoText.setText(odo < 0 ? getString(R.string.maint_set_odo) : String.format(Locale.US, "%,d mi", odo));
        list.removeAllViews();
        long now = System.currentTimeMillis();
        for (MaintenanceStore.Item it : store.items()) {
            View row = getLayoutInflater().inflate(R.layout.item_maintenance, list, false);
            ((TextView) row.findViewById(R.id.mi_name)).setText(it.name);
            StringBuilder last = new StringBuilder();
            if (it.lastMiles >= 0) last.append(String.format(Locale.US, "%,d mi", it.lastMiles));
            if (it.lastDate > 0) last.append(last.length() > 0 ? " · " : "").append(df.format(new Date(it.lastDate)));
            ((TextView) row.findViewById(R.id.mi_last)).setText(last.length() == 0 ? getString(R.string.maint_never) : getString(R.string.maint_last, last));
            StringBuilder iv = new StringBuilder();
            if (it.intervalMiles > 0) iv.append(String.format(Locale.US, "every %,d mi", it.intervalMiles));
            if (it.intervalMonths > 0) iv.append(iv.length() > 0 ? " / " : "every ").append(it.intervalMonths).append(" mo");
            ((TextView) row.findViewById(R.id.mi_interval)).setText(iv);
            TextView due = row.findViewById(R.id.mi_due);
            Integer m = it.milesLeft(odo), d = it.daysLeft(now);
            String dueText;
            if (m == null && d == null) dueText = "—";
            else {
                StringBuilder sb = new StringBuilder();
                if (m != null) sb.append(m < 0 ? String.format(Locale.US, "%,d mi over", -m) : String.format(Locale.US, "%,d mi", m));
                if (d != null) sb.append(sb.length() > 0 ? "\n" : "").append(d < 0 ? (-d) + " d over" : d + " d");
                dueText = sb.toString();
            }
            due.setText(dueText);
            int st = it.status(odo, now);
            due.setTextColor(getColor(st == 2 ? R.color.bad_red : st == 1 ? R.color.warn_amber : st == 0 ? R.color.ok_green : R.color.text_tertiary));
            row.findViewById(R.id.mi_done).setOnClickListener(v -> markDone(it));
            row.setOnClickListener(v -> editItem(it));
            row.setOnLongClickListener(v -> { confirmDelete(it); return true; });
            list.addView(row);
        }
    }

    interface IntFn { void run(int n); }

    private void askNumber(String title, int current, IntFn fn) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (current >= 0) e.setText(String.valueOf(current));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setView(e)
                .setPositiveButton(android.R.string.ok, (d, w) -> { try { fn.run(Integer.parseInt(e.getText().toString().trim())); } catch (NumberFormatException ignored) {} })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void markDone(MaintenanceStore.Item it) {
        askNumber(getString(R.string.maint_done_at, it.name), store.odometer(), n -> {
            if (n > store.odometer()) store.setOdometer(n);
            store.markDone(it, n, System.currentTimeMillis());
            refresh();
        });
    }

    private void editItem(MaintenanceStore.Item it) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(16 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p, p, 0);
        EditText name = field(box, R.string.maint_name, it == null ? "" : it.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText miles = field(box, R.string.maint_interval_miles, it == null ? "" : String.valueOf(it.intervalMiles), InputType.TYPE_CLASS_NUMBER);
        EditText months = field(box, R.string.maint_interval_months, it == null ? "" : String.valueOf(it.intervalMonths), InputType.TYPE_CLASS_NUMBER);
        EditText lastM = field(box, R.string.maint_last_miles, it == null || it.lastMiles < 0 ? "" : String.valueOf(it.lastMiles), InputType.TYPE_CLASS_NUMBER);
        EditText notes = field(box, R.string.maint_notes, it == null ? "" : it.notes, InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(it == null ? R.string.maint_add : R.string.maint_edit).setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    MaintenanceStore.Item target = it != null ? it : new MaintenanceStore.Item("u" + System.currentTimeMillis(), "", 0, 0);
                    target.name = name.getText().toString().trim();
                    target.intervalMiles = parse(miles); target.intervalMonths = parse(months);
                    int lm = parse(lastM); if (lm > 0) { target.lastMiles = lm; if (target.lastDate == 0) target.lastDate = System.currentTimeMillis(); }
                    target.notes = notes.getText().toString().trim();
                    if (target.name.isEmpty()) return;
                    if (it == null) store.add(target); else store.save();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private EditText field(LinearLayout box, int hint, String value, int type) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setText(value); e.setInputType(type); e.setSingleLine(true);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private static int parse(EditText e) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return 0; } }

    private void confirmDelete(MaintenanceStore.Item it) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(getString(R.string.maint_delete, it.name))
                .setPositiveButton(R.string.delete, (d, w) -> { store.remove(it); refresh(); })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void export() {
        try {
            File dir = new File(getExternalFilesDir(null), "export");
            dir.mkdirs();
            File f = new File(dir, "phoenix-maintenance.json");
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(store.toJson().toString(2).getBytes("UTF-8")); }
            Toast.makeText(this, getString(R.string.maint_exported, f.getAbsolutePath()), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
