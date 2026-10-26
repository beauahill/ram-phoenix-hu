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
    /** Extra readings for the OBD card: label -> value (unit kept in {@link #customUnit}). */
    public final java.util.Map<String, Float> custom = new java.util.concurrent.ConcurrentHashMap<>();
    public final java.util.Map<String, String> customUnit = new java.util.concurrent.ConcurrentHashMap<>();

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
                sock = openSpp(dev);
                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();
                init(in, out);
                connected = true;
                curHeader = "";
                set("OBD: connected " + dev.getName());
                // Defaults are community-reported Chrysler/RAM mode-22 PIDs on the engine
                // header (ramforum / promasterforum, 2026-10-25 search): trans temp 22 B010
                // = ((A*256)+B)/64 °F (converted to °C here), oil pressure 22 022A (scale
                // unverified, raw A shown as psi). Override live with the setprops below.
                // Live 2026-10-26: mode 22 on the broadcast header (7DF) got NO DATA; Chrysler
                // modules answer these only when addressed directly, so a request may carry
                // its header as "7E0:22B010". Oil temp via 01 5C is unsupported on this ECU;
                // the Chrysler 22 0121 is tried instead (formula unverified).
                String transCfg = prop("persist.phoenix.obd.trans", "7E1:22B010,((A*256+B)/64-32)*5/9");
                String oilCfg = prop("persist.phoenix.obd.oilpsi", "7E0:22022A,A");
                String oilTCfg = prop("persist.phoenix.obd.oiltemp", "7E0:220121,A-40");
                java.util.List<String[]> extras = new java.util.ArrayList<>();
                for (int i = 1; i <= 4; i++) {
                    String c = prop("persist.phoenix.obd.custom" + i, "");
                    String[] parts = c.split(",");
                    if (parts.length >= 3) extras.add(parts);      // LABEL,req,formula[,unit]
                }
                while (running) {
                    header(in, out, "7DF");
                    coolantC = pidA(in, out, "0105", 0x05, a -> a - 40f);
                    Float ot = pidA(in, out, "015C", 0x5C, a -> a - 40f);
                    if (ot == null && !oilTCfg.isEmpty()) ot = custom(in, out, oilTCfg);
                    oilTempC = ot;
                    volts = parseVolts(cmd(in, out, "ATRV"));
                    if (!transCfg.isEmpty()) transC = custom(in, out, transCfg);
                    if (!oilCfg.isEmpty()) oilPsi = custom(in, out, oilCfg);
                    for (String[] x : extras) {
                        Float v = custom(in, out, x[1].trim() + "," + x[2].trim());
                        if (v != null) custom.put(x[0].trim(), v); else custom.remove(x[0].trim());
                        customUnit.put(x[0].trim(), x.length > 3 ? x[3].trim() : "");
                    }
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
                custom.clear();
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
                ui.post(listener::onObdChanged);
            }
            try { sleep(RETRY_MS); } catch (InterruptedException e) { break; }
        }
    }

    /**
     * SPP connect with the usual Android fallbacks: the secure SDP socket failed
     * instantly on this unit ("read failed, socket might closed", live 2026-10-25),
     * which on many stacks means the insecure socket or a fixed RFCOMM channel is
     * needed. Tries each in turn and reports which one worked.
     */
    @SuppressLint({"MissingPermission", "DiscouragedPrivateApi"})
    private BluetoothSocket openSpp(BluetoothDevice dev) throws Exception {
        Exception last = null;
        String[] names = {"secure SDP", "insecure SDP", "channel 1", "channel 2"};
        for (int attempt = 0; attempt < names.length; attempt++) {
            BluetoothSocket s = null;
            try {
                switch (attempt) {
                    case 0: s = dev.createRfcommSocketToServiceRecord(SPP); break;
                    case 1: s = dev.createInsecureRfcommSocketToServiceRecord(SPP); break;
                    default: {
                        java.lang.reflect.Method m = dev.getClass().getMethod("createRfcommSocket", int.class);
                        s = (BluetoothSocket) m.invoke(dev, attempt - 1);
                    }
                }
                s.connect();
                Log.i(TAG, "OBD: SPP up via " + names[attempt]);
                return s;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "OBD: " + names[attempt] + " failed: " + e.getMessage());
                try { if (s != null) s.close(); } catch (Exception ignored) {}
                Thread.sleep(400);
            }
        }
        throw last != null ? last : new Exception("no SPP route");
    }

    private void init(InputStream in, OutputStream out) throws Exception {
        cmd(in, out, "ATZ"); sleep(1500);
        // drain anything the reset banner left behind
        while (in.available() > 0) in.read();
        cmd(in, out, "ATE0");     // echo off
        cmd(in, out, "ATL0");     // no linefeeds
        cmd(in, out, "ATS0");     // no spaces
        cmd(in, out, "ATH0");     // no headers
        cmd(in, out, "ATSP6");    // ISO 15765-4 CAN 11-bit 500k: what the RAM uses; no bus search
        cmd(in, out, "ATAT1");    // adaptive timing
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
    private String curHeader = "";

    /** Set the request header (ATSH) only when it changes; the reply filter follows it. */
    private void header(InputStream in, OutputStream out, String h) throws Exception {
        if (h.equalsIgnoreCase(curHeader)) return;
        cmd(in, out, "ATSH" + h);
        if (h.equalsIgnoreCase("7DF")) cmd(in, out, "ATCRA"); else {
            // 7E0 -> replies from 7E8, 7E1 -> 7E9
            try { int rx = Integer.parseInt(h, 16) + 8; cmd(in, out, "ATCRA" + String.format("%03X", rx)); } catch (Exception ignored) {}
        }
        curHeader = h;
    }

    private Float custom(InputStream in, OutputStream out, String cfg) throws Exception {
        int c = cfg.indexOf(',');
        if (c < 0) return null;
        String req = cfg.substring(0, c).trim(), formula = cfg.substring(c + 1).trim();
        int colon = req.indexOf(':');
        if (colon > 0) { header(in, out, req.substring(0, colon)); req = req.substring(colon + 1); }
        else header(in, out, "7DF");
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
        long deadline = System.currentTimeMillis() + 4000;
        boolean prompt = false;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() == 0) { Thread.sleep(20); continue; }
            int ch = in.read();
            if (ch < 0) throw new Exception("socket closed");
            if (ch == '>') { prompt = true; break; }
            if (ch != '\r' && ch != '\n' && ch != ' ' && ch != 0) sb.append((char) ch);
            // a protocol search can take a while: never cut it off, never resend into it
            if (sb.indexOf("SEARCHING") >= 0) deadline = Math.max(deadline, System.currentTimeMillis() + 12000);
        }
        String r = sb.toString();
        if (r.startsWith(c)) r = r.substring(c.length());
        Log.d(TAG, c + " -> " + r + (prompt ? "" : " (no prompt)"));
        if (r.contains("UNABLETOCONNECT") || r.contains("CANERROR") || r.contains("BUSINIT")) {
            throw new Exception(r);
        }
        if (r.contains("STOPPED") && !retrying) {
            retrying = true;
            try { Thread.sleep(600); return cmd(in, out, c); } finally { retrying = false; }
        }
        if (r.contains("NODATA") || r.contains("?") || r.contains("SEARCHING")) return "";
        return r;
    }

    private boolean retrying;

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
