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
    private final ObdWorker worker = new ObdWorker(this::loop);
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
    public Float oilPsi() { return oilPsiClamped(); }
    public Float volts() { return volts; }

    public synchronized void start() { worker.start(); }

    public synchronized void stop() {
        worker.stop();
        clearReadings();
        set("OBD: idle");
    }

    private void clearReadings() {
        connected = false;
        coolantC = oilTempC = transC = oilPsi = volts = null;
        custom.clear();
        customUnit.clear();
    }

    private void set(String st) { status = st; Log.i(TAG, st); ui.post(listener::onObdChanged); }

    @SuppressLint("MissingPermission")
    private BluetoothDevice findAdapter(ObdWorker.Session session) {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) { session.publish(() -> set("OBD: bluetooth off")); return null; }
            String want = prop("persist.phoenix.obd.name", "OBD").toLowerCase(Locale.US);
            for (BluetoothDevice d : ad.getBondedDevices()) {
                String n = d.getName();
                if (n != null && n.toLowerCase(Locale.US).contains(want)) return d;
            }
            session.publish(() -> set("OBD: no paired adapter"));
        } catch (SecurityException e) {
            session.publish(() -> set("OBD: no bluetooth permission"));
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    private void loop(ObdWorker.Session session) {
        while (session.active()) {
            BluetoothSocket sock = null;
            try {
                BluetoothDevice dev = findAdapter(session);
                if (dev == null) { sleep(RETRY_MS); continue; }
                session.publish(() -> set("OBD: connecting " + dev.getName()));
                sock = openSpp(dev, session);
                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();
                init(in, out);
                curHeader = "";
                if (!session.publish(() -> {
                    connected = true;
                    set("OBD: connected " + dev.getName());
                })) break;
                // Defaults are community-reported Chrysler/RAM mode-22 PIDs on the engine
                // header (ramforum / promasterforum, 2026-10-25 search): trans temp 22 B010
                // = ((A*256)+B)/64 °F (converted to °C here), oil pressure 22 022A (scale
                // unverified, raw A shown as psi). Override live with the setprops below.
                // Pinned live against the cluster, 2026-10-26 (scan + diff, see docs):
                //   trans temp   7E0 22 A0A3  = °C x 128      (cluster 93 °F ↔ 36 °C)
                //   oil pressure 7E0 22 A05C  = 0.25 kPa gauge (cluster 37 psi ↔ 0x40)
                //   coolant      7E0 22 A047  = °C x 128      (same as 01 05)
                //   oil temp     not found yet (not in A0xx / 90xx / 01xx); 01 5C unsupported.
                String transCfg = prop("persist.phoenix.obd.trans", "7E0:22A0A3,(A*256+B)/128");
                // oil pressure: A05C = gauge pressure in 0.25 kPa (A058 is the same +100 kPa, i.e.
                // absolute). 0x40 -> 256 kPa -> 37 psi, exactly the cluster. A0E1 was a coincidence.
                String oilCfg = prop("persist.phoenix.obd.oilpsi", "7E0:22A05C,S*0.0362595");   // S = signed 16-bit A:B
                String oilTCfg = prop("persist.phoenix.obd.oiltemp", "");
                java.util.List<String[]> extras = new java.util.ArrayList<>();
                for (int i = 1; i <= 4; i++) {
                    String c = prop("persist.phoenix.obd.custom" + i, "");
                    String[] parts = c.split(",");
                    if (parts.length >= 3) extras.add(parts);      // LABEL,req,formula[,unit]
                }
                // One-shot DID scan for finding the Cummins/68RFE identifiers that are not
                // published anywhere: setprop persist.phoenix.obd.scan "7E1:B000-B0FF"
                // then watch `logcat -s PhoenixObd` for "scan HIT". Cleared after it runs.
                while (session.active()) {
                    String scan = prop("persist.phoenix.obd.scan", "");
                    if (!scan.isEmpty()) { setProp("persist.phoenix.obd.scan", ""); scanDids(in, out, scan, session); }
                    // live watch for identification: persist.phoenix.obd.watch = "7E0:A0CA,A0CB,...;7E1:A034"
                    String watch = prop("persist.phoenix.obd.watch", "");
                    if (!watch.isEmpty()) {
                        StringBuilder wb = new StringBuilder("watch");
                        for (String grp : watch.split(";")) {
                            int ci = grp.indexOf(':'); if (ci < 0) continue;
                            String hdr = grp.substring(0, ci);
                            for (String did : grp.substring(ci + 1).split(",")) {
                                did = did.trim(); if (did.isEmpty()) continue;
                                if (did.length() == 4 && !did.startsWith("01")) { header(in, out, hdr); wb.append(' ').append(hdr).append(':').append(did).append('=').append(cmd(in, out, "22" + did).replaceFirst("^62" + did, "")); }
                                else { header(in, out, "7DF"); wb.append(' ').append(did).append('=').append(cmd(in, out, did).replaceFirst("^4" + did.substring(1), "")); }
                            }
                        }
                        Log.i(TAG, wb.toString());
                    }
                    header(in, out, "7DF");
                    Float nextCoolant = pidA(in, out, "0105", 0x05, a -> a - 40f);
                    Float ot = pidA(in, out, "015C", 0x5C, a -> a - 40f);
                    if (ot == null && !oilTCfg.isEmpty()) ot = custom(in, out, oilTCfg);
                    final Float nextOilTemp = ot;
                    Float nextVolts = parseVolts(cmd(in, out, "ATRV"));
                    Float nextTrans = transCfg.isEmpty() ? null : custom(in, out, transCfg);
                    Float nextOilPsi = oilCfg.isEmpty() ? null : custom(in, out, oilCfg);
                    java.util.Map<String, Float> nextCustom = new java.util.LinkedHashMap<>();
                    java.util.Map<String, String> nextUnits = new java.util.LinkedHashMap<>();
                    for (String[] x : extras) {
                        Float v = custom(in, out, x[1].trim() + "," + x[2].trim());
                        if (v != null) nextCustom.put(x[0].trim(), v);
                        nextUnits.put(x[0].trim(), x.length > 3 ? x[3].trim() : "");
                    }
                    session.publish(() -> {
                        coolantC = nextCoolant;
                        oilTempC = nextOilTemp;
                        volts = nextVolts;
                        transC = nextTrans;
                        oilPsi = nextOilPsi;
                        custom.clear(); custom.putAll(nextCustom);
                        customUnit.clear(); customUnit.putAll(nextUnits);
                        ui.post(listener::onObdChanged);
                    });
                    sleep(POLL_MS);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                session.publish(() -> set("OBD: " + e.getMessage()));
            } finally {
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
                session.release(sock);
                session.publish(() -> { clearReadings(); ui.post(listener::onObdChanged); });
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
    private BluetoothSocket openSpp(BluetoothDevice dev, ObdWorker.Session session) throws Exception {
        Exception last = null;
        String[] names = {"secure SDP", "insecure SDP", "channel 1", "channel 2"};
        for (int attempt = 0; attempt < names.length; attempt++) {
            if (!session.active()) throw new InterruptedException();
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
                session.attach(s);
                s.connect();
                if (!session.active()) throw new InterruptedException();
                Log.i(TAG, "OBD: SPP up via " + names[attempt]);
                return s;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "OBD: " + names[attempt] + " failed: " + e.getMessage());
                try { if (s != null) s.close(); } catch (Exception ignored) {}
                session.release(s);
                if (e instanceof InterruptedException || !session.active()) throw new InterruptedException();
                Thread.sleep(400);
            }
        }
        throw last != null ? last : new Exception("no SPP route");
    }

    private void init(InputStream in, OutputStream out) throws Exception {
        cmd(in, out, "ATWS"); sleep(800);              // warm start: no firmware reboot, no link hiccup
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

    private void scanDids(InputStream in, OutputStream out, String spec, ObdWorker.Session session) throws Exception {
        try {
            String hdr = spec.substring(0, spec.indexOf(':'));
            String[] range = spec.substring(spec.indexOf(':') + 1).split("-");
            int from = Integer.parseInt(range[0], 16), to = Integer.parseInt(range[1], 16);
            boolean open = hdr.startsWith("!");
            if (open) hdr = hdr.substring(1);
            header(in, out, hdr);
            if (open) { cmd(in, out, "ATCRA"); cmd(in, out, "ATH1"); curHeader = ""; }   // show every reply, with its id
            cmd(in, out, "ATST10");                       // short per-request timeout (16 x 4 ms)
            int hits = 0;
            for (int did = from; did <= to && session.active(); did++) {
                String req = String.format("22%04X", did);
                String r = cmd(in, out, req);
                if (r.startsWith("62") || (open && r.contains("62"))) { hits++; Log.i(TAG, "scan HIT " + hdr + " " + req + " -> " + r); }
                else if (r.startsWith("7F") || (open && r.contains("7F"))) Log.i(TAG, "scan NEG " + hdr + " " + req + " -> " + r);   // module present, DID refused
                else if (open && !r.isEmpty()) Log.i(TAG, "scan ANY " + hdr + " " + req + " -> " + r);
            }
            Log.i(TAG, "scan done " + spec + ": " + hits + " hits");
            cmd(in, out, "ATST32");
            if (open) cmd(in, out, "ATH0");
        } catch (InterruptedException | java.io.IOException e) {
            throw e; // A missing prompt must retire the socket, including during a scan.
        } catch (Exception e) {
            Log.w(TAG, "scan aborted: " + e.getMessage());
        }
    }

    private static void setProp(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            sp.getMethod("set", String.class, String.class).invoke(null, key, value);
        } catch (Exception ignored) {}
    }

    /**
     * Set the request header (ATSH) only when it changes; the reply filter follows it.
     * 3-digit header = 11-bit CAN (ATSP6; engine 7E0, trans 7E1, broadcast 7DF).
     * 8-digit header = 29-bit CAN (ATSP7; FCA body modules are 18DA<target>F1 and
     * answer on 18DAF1<target>).
     */
    private void header(InputStream in, OutputStream out, String h) throws Exception {
        if (h.equalsIgnoreCase(curHeader)) return;
        boolean ext = h.length() == 8;
        boolean curExt = curHeader.length() == 8;
        if (ext != curExt || curHeader.isEmpty()) cmd(in, out, ext ? "ATSP7" : "ATSP6");
        if (ext) {
            cmd(in, out, "ATCP" + h.substring(0, 2));            // priority byte (18)
            cmd(in, out, "ATSH" + h.substring(2));               // DA 44 F1
            String rx = h.substring(0, 4) + h.substring(6, 8) + h.substring(4, 6);   // 18DA F1 44
            cmd(in, out, "ATCRA" + rx);
        } else {
            cmd(in, out, "ATSH" + h);
            if (h.equalsIgnoreCase("7DF")) cmd(in, out, "ATCRA"); else {
                int rx = Integer.parseInt(h, 16) + 8;
                cmd(in, out, "ATCRA" + String.format("%03X", rx));
            }
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

    public Float oilPsiClamped() { Float v = oilPsi; return v == null ? null : Math.max(0f, v); }

    private static Float parseVolts(String r) {
        try {
            String s = r.replace("V", "").trim();
            return Float.parseFloat(s);
        } catch (Exception e) { return null; }
    }

    /** Send one command, collect until the '>' prompt, strip echo/noise. */
    private String cmd(InputStream in, OutputStream out, String c) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        out.write((c + "\r").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.flush();
        String r = ElmResponseReader.read(in, 4000, 12000);
        if (r.startsWith(c)) r = r.substring(c.length());
        r = r.replace("SEARCHING...", "").replace("SEARCHING", "");
        Log.d(TAG, c + " -> " + r);
        if (r.contains("UNABLETOCONNECT") || r.contains("CANERROR") || r.contains("BUSINIT")) {
            throw new Exception(r);
        }
        if (r.contains("STOPPED") && !retrying) {
            retrying = true;
            try { Thread.sleep(600); return cmd(in, out, c); } finally { retrying = false; }
        }
        if (r.contains("NODATA") || r.contains("?")) return "";
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

    /** Tiny arithmetic evaluator for the custom-PID formula: + - * / parentheses, A, B and S (signed A:B). */
    static final class Expr {
        private final String s; private int p; private final float a, b, sg;
        private Expr(String s, float a, float b) {
            this.s = s.replace(" ", ""); this.a = a; this.b = b;
            int w = (int) a * 256 + (int) b; this.sg = w > 32767 ? w - 65536 : w;
        }
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
            if (c == 'S' || c == 's') { p++; return sg; }
            if (c == '-') { p++; return -atom(); }
            int st = p; while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) p++;
            return Float.parseFloat(s.substring(st, p));
        }
    }
}
