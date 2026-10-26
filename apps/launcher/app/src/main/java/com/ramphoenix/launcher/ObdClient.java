package com.ramphoenix.launcher;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

/**
 * ELM327-style OBD-II reader over Bluetooth SPP, built for the OBDLink MX+ that
 * lives in the truck (Beau, 2026-10-25). Uses the unit's own Android Bluetooth
 * adapter (present and on; the vendor audio module is a separate serial chip).
 *
 * Pairing: press the MX+ button, pair it from the unit's Android Settings >
 * Bluetooth. The client picks any bonded device whose name contains "OBD".
 *
 * Reads, once a second each while connected:
 *   coolant  mode 01 PID 05  (A - 40 °C)                 standard
 *   oil temp mode 01 PID 5C  (A - 40 °C)                 standard, not all ECUs
 *   voltage  ATRV                                       adapter supply
 *   trans / oil pressure: manufacturer mode 22 PIDs, not standard across
 *   trucks. Set them live without a rebuild:
 *     setprop persist.phoenix.obd.trans  "<hex request>,<formula>"
 *     setprop persist.phoenix.obd.oilpsi "<hex request>,<formula>"
 *   where the formula is a Java-ish expression in A and B (first two data bytes),
 *   e.g. "221E1C,A-40" or "2206C1,(A*256+B)/4". Empty = not polled.
 *
 * Everything is best-effort: any failure drops the connection and retries in 10 s.
 */
public class ObdClient {

    private static final String TAG = "PhoenixObd";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long RETRY_MS = 10_000, POLL_MS = 1000;

    public interface Listener { void onObdChanged(); }

    private final Context ctx;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Thread worker;
    private volatile boolean running;
    private volatile boolean connected;
    private volatile String status = "OBD: idle";

    private volatile Float coolantC, oilTempC, transC, oilPsi, volts;

    public ObdClient(Context ctx, Listener l) { this.ctx = ctx.getApplicationContext(); listener = l; }

    public boolean connected() { return connected; }
    public String status() { return status; }
    public Float coolantC() { return coolantC; }
    public Float oilTempC() { return oilTempC; }
    public Float transC() { return transC; }
    public Float oilPsi() { return oilPsi; }
    public Float volts() { return volts; }

    public void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "obd");
        worker.start();
    }

    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
    }

    private void set(String st) { status = st; Log.i(TAG, st); ui.post(listener::onObdChanged); }

    @SuppressLint("MissingPermission")
    private BluetoothDevice findAdapter() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) { set("OBD: bluetooth off"); return null; }
            String want = prop("persist.phoenix.obd.name", "OBD").toLowerCase(Locale.US);
            for (BluetoothDevice d : ad.getBondedDevices()) {
                String n = d.getName();
                if (n != null && n.toLowerCase(Locale.US).contains(want)) return d;
            }
            set("OBD: no paired adapter");
        } catch (SecurityException e) {
            set("OBD: no bluetooth permission");
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    private void loop() {
        while (running) {
            BluetoothSocket sock = null;
            try {
                BluetoothDevice dev = findAdapter();
                if (dev == null) { sleep(RETRY_MS); continue; }
                set("OBD: connecting " + dev.getName());
                sock = dev.createRfcommSocketToServiceRecord(SPP);
                sock.connect();
                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();
                init(in, out);
                connected = true;
                set("OBD: connected " + dev.getName());
                String transCfg = prop("persist.phoenix.obd.trans", ""), oilCfg = prop("persist.phoenix.obd.oilpsi", "");
                while (running) {
                    coolantC = pidA(in, out, "0105", 0x05, a -> a - 40f);
                    oilTempC = pidA(in, out, "015C", 0x5C, a -> a - 40f);
                    volts = parseVolts(cmd(in, out, "ATRV"));
                    if (!transCfg.isEmpty()) transC = custom(in, out, transCfg);
                    if (!oilCfg.isEmpty()) oilPsi = custom(in, out, oilCfg);
                    ui.post(listener::onObdChanged);
                    sleep(POLL_MS);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                set("OBD: " + e.getMessage());
            } finally {
                connected = false;
                coolantC = oilTempC = transC = oilPsi = volts = null;
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
                ui.post(listener::onObdChanged);
            }
            try { sleep(RETRY_MS); } catch (InterruptedException e) { break; }
        }
    }

    private void init(InputStream in, OutputStream out) throws Exception {
        cmd(in, out, "ATZ"); sleep(600);
        cmd(in, out, "ATE0");     // echo off
        cmd(in, out, "ATL0");     // no linefeeds
        cmd(in, out, "ATS0");     // no spaces
        cmd(in, out, "ATH0");     // no headers
        cmd(in, out, "ATSP0");    // auto protocol
        cmd(in, out, "0100");     // wakes the bus / settles the protocol
    }

    interface F { float f(int a); }

    private Float pidA(InputStream in, OutputStream out, String req, int pid, F f) throws Exception {
        String r = cmd(in, out, req);
        // response like "41055A" (41 = mode 01 reply, 05 = pid, 5A = A)
        int i = r.indexOf("41" + String.format("%02X", pid));
        if (i < 0 || r.length() < i + 6) return null;
        return f.f(Integer.parseInt(r.substring(i + 4, i + 6), 16));
    }

    /** "<request>,<formula with A and B>" e.g. "221E1C,A-40" or "2206C1,(A*256+B)/4". */
    private Float custom(InputStream in, OutputStream out, String cfg) throws Exception {
        int c = cfg.indexOf(',');
        if (c < 0) return null;
        String req = cfg.substring(0, c).trim(), formula = cfg.substring(c + 1).trim();
        String r = cmd(in, out, req);
        String reply = String.format("%02X", Integer.parseInt(req.substring(0, 2), 16) + 0x40) + req.substring(2);
        int i = r.indexOf(reply);
        if (i < 0) return null;
        String data = r.substring(i + reply.length());
        if (data.length() < 2) return null;
        int a = Integer.parseInt(data.substring(0, 2), 16);
        int b = data.length() >= 4 ? Integer.parseInt(data.substring(2, 4), 16) : 0;
        return Expr.eval(formula, a, b);
    }

    private static Float parseVolts(String r) {
        try {
            String s = r.replace("V", "").trim();
            return Float.parseFloat(s);
        } catch (Exception e) { return null; }
    }

    /** Send one command, collect until the '>' prompt, strip echo/noise. */
    private String cmd(InputStream in, OutputStream out, String c) throws Exception {
        out.write((c + "\r").getBytes());
        out.flush();
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + 2500;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() == 0) { Thread.sleep(20); continue; }
            int ch = in.read();
            if (ch < 0) throw new Exception("socket closed");
            if (ch == '>') break;
            if (ch != '\r' && ch != '\n' && ch != ' ' && ch != 0) sb.append((char) ch);
        }
        String r = sb.toString();
        if (r.startsWith(c)) r = r.substring(c.length());
        if (r.contains("UNABLETOCONNECT") || r.contains("NODATA") || r.contains("STOPPED")) {
            throw new Exception(r);
        }
        return r;
    }

    private static void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }

    private static String prop(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String s = (String) sp.getMethod("get", String.class, String.class).invoke(null, key, "");
            return (s == null || s.isEmpty()) ? def : s;
        } catch (Exception e) { return def; }
    }

    /** Tiny arithmetic evaluator for the custom-PID formula: + - * / parentheses, A and B. */
    static final class Expr {
        private final String s; private int p; private final float a, b;
        private Expr(String s, float a, float b) { this.s = s.replace(" ", ""); this.a = a; this.b = b; }
        static Float eval(String s, float a, float b) {
            try { return new Expr(s, a, b).expr(); } catch (Exception e) { return null; }
        }
        private float expr() { float v = term(); while (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) { char op = s.charAt(p++); float t = term(); v = op == '+' ? v + t : v - t; } return v; }
        private float term() { float v = atom(); while (p < s.length() && (s.charAt(p) == '*' || s.charAt(p) == '/')) { char op = s.charAt(p++); float t = atom(); v = op == '*' ? v * t : v / t; } return v; }
        private float atom() {
            char c = s.charAt(p);
            if (c == '(') { p++; float v = expr(); p++; return v; }
            if (c == 'A' || c == 'a') { p++; return a; }
            if (c == 'B' || c == 'b') { p++; return b; }
            if (c == '-') { p++; return -atom(); }
            int st = p; while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) p++;
            return Float.parseFloat(s.substring(st, p));
        }
    }
}
