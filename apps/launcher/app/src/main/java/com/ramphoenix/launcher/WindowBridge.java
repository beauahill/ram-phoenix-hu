package com.ramphoenix.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.ramphoenix.windows.WindowAuth;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated loopback client; window lifetime is independent of keyboard/touch focus. */
final class WindowBridge {
    private static WindowBridge instance;
    static synchronized WindowBridge get(Context context) {
        if(instance==null) instance=new WindowBridge(context.getApplicationContext()); return instance;
    }
    private final Context context;
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Map<String,JSONObject> panes=new LinkedHashMap<>();
    private volatile boolean visible, reverse, ready;
    private String token;
    private long generation, nextBootstrap;
    private volatile String status="Split app windows need one-time setup";
    private WindowBridge(Context c) {
        context=c;
        io.scheduleWithFixedDelay(this::pump,0,300,TimeUnit.MILLISECONDS);
    }
    boolean ready() { return ready; }
    String status() { return status; }
    synchronized boolean wanted(String key) { return panes.containsKey(key); }
    synchronized void open(String key,ComponentName component,Rect bounds) {
        if(reverse) return;
        try {
            for(String other:new ArrayList<>(panes.keySet())) if(panes.get(other).getString("component").startsWith(component.getPackageName()+"/")) panes.remove(other);
            panes.put(key,new JSONObject().put("key",key).put("component",component.flattenToString())
                    .put("bounds",bounds.flattenToString()).put("generation",++generation));
            refresh();
        } catch(JSONException impossible) { throw new IllegalArgumentException(impossible); }
    }
    synchronized void resize(String key,Rect frame) {
        JSONObject pane=panes.get(key);
        if(pane!=null && !frame.isEmpty()) try { pane.put("bounds",frame.flattenToString()); refresh(); } catch(JSONException ignored) {}
    }
    synchronized void close(String key) { panes.remove(key); refresh(); }
    synchronized void closeAll() { panes.clear(); refresh(); }
    void visible(boolean value) { visible=value; refresh(); }
    void reverse(boolean value) { reverse=value; refresh(); }
    private void refresh() { io.execute(this::pump); }
    void afterClose(Runnable action) {
        closeAll();
        io.execute(() -> { boolean acknowledged=request(); ui.postDelayed(action,acknowledged?0:1900); });
    }
    void setup(Runnable complete) {
        io.execute(() -> {
            try { credential(); bootstrap(true); status="App windows ready"; }
            catch(Exception e) { status="Setup needs Android debugging approval"; Log.w("PhoenixWindows",status,e); }
            pump(); ui.post(complete);
        });
    }
    private synchronized JSONObject snapshot() throws JSONException {
        JSONArray windows=new JSONArray(); for(JSONObject pane:panes.values()) windows.put(new JSONObject(pane.toString()));
        return new JSONObject().put("sentAt",SystemClock.elapsedRealtime()).put("visible",visible).put("reverse",reverse).put("panes",windows);
    }
    private void credential() throws Exception {
        if(token!=null) return;
        // Rotate the early token when migrating to directory-protected staging.
        File file=new File(context.getNoBackupFilesDir(),"window-token-private");
        if(file.exists()) token=new String(Files.readAllBytes(file.toPath()),StandardCharsets.US_ASCII).trim();
        else {
            byte[] random=new byte[32]; new SecureRandom().nextBytes(random); StringBuilder hex=new StringBuilder();
            for(byte value:random) hex.append(String.format(Locale.US,"%02x",value&255)); token=hex.toString();
            Files.write(file.toPath(),token.getBytes(StandardCharsets.US_ASCII));
        }
    }
    private boolean request() {
        try(Socket socket=new Socket()) {
            credential(); socket.connect(new InetSocketAddress("127.0.0.1",43837),300); socket.setSoTimeout(1200);
            String nonce=WindowAuth.nonce(), payload=snapshot().toString();
            JSONObject envelope=new JSONObject().put("nonce",nonce).put("payload",payload)
                    .put("signature",WindowAuth.sign(token,nonce,payload));
            socket.getOutputStream().write((envelope+"\n").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream incoming=new ByteArrayOutputStream();
            InputStream input=new BufferedInputStream(socket.getInputStream());
            for(int value;(value=input.read())!=-1 && value!='\n';) {
                if(incoming.size()>=8192) throw new IOException("Window response too large");
                incoming.write(value);
            }
            String line=incoming.toString("UTF-8");
            JSONObject reply=new JSONObject(line);
            String body=reply.getString("payload");
            if(!WindowAuth.verify(token,nonce,body,reply.getString("signature"))) return ready=false;
            JSONObject response=new JSONObject(body);
            if(response.getInt("version")!=2 || !response.getBoolean("ok")) return ready=false;
            status="App windows ready"; return ready=true;
        } catch(Exception e) { return ready=false; }
    }
    private void pump() {
        if(request()) return;
        if(!visible || reverse || SystemClock.uptimeMillis()<nextBootstrap) return;
        nextBootstrap=SystemClock.uptimeMillis()+15000;
        try { credential(); bootstrap(false); request(); }
        catch(Exception e) {
            status="Split app windows need setup or reconnection";
            Log.w("PhoenixWindows","Local window startup failed",e);
        }
    }
    private void bootstrap(boolean allowApproval) throws Exception {
        if(reverse) return;
        try(LocalAdb adb=new LocalAdb(context,allowApproval)) {
            // adbd may widen sync file permissions. Protect the directory before
            // transferring a credential, and verify actual ownership/modes.
            String directory="/data/local/tmp/phoenix-windows";
            String mode=adb.shell("mkdir -p "+directory+" && chmod 700 "+directory
                    +" && stat -c '%u %a' "+directory).trim();
            if(!mode.equals("2000 700")) throw new IOException("Window directory is not private");
            byte[] jar;
            try(InputStream input=context.getAssets().open("window_bridge.jar")) { jar=input.readAllBytes(); }
            adb.push(directory+"/bridge.jar",jar);
            adb.push(directory+"/token",token.getBytes(StandardCharsets.US_ASCII));
            mode=adb.shell("chmod 600 "+directory+"/bridge.jar "+directory+"/token && stat -c '%u %a' "
                    +directory+"/bridge.jar "+directory+"/token").trim();
            if(!mode.equals("2000 600\n2000 600")) throw new IOException("Window files are not private");
            String startedLog=adb.shell("CLASSPATH="+directory+"/bridge.jar nohup setsid app_process / --nice-name=phoenix-window-bridge "
                    +"com.ramphoenix.windowbridge.Server "+directory+"/token </dev/null >"+directory+"/server.log 2>&1 &\n"
                    +"for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do "
                    +"if grep -q '^Phoenix window bridge v2 ready$' "+directory+"/server.log; then printf READY; exit 0; fi; sleep 0.1; done; printf FAILED");
            if(!startedLog.trim().equals("READY")) throw new IOException("Window startup did not report readiness");
            // Keep the shell stream itself alive until setsid and the child have
            // started. An open ADB transport alone does not retain an exited
            // shell's process group on this firmware.
            boolean started=false;
            for(int attempt=0;attempt<25;attempt++) {
                if(request()) { started=true; break; }
                Thread.sleep(100);
            }
            if(!started) throw new IOException("Window companion did not start");
        }
        Thread.sleep(150);
        if(!request()) throw new IOException("Window companion stopped after bootstrap");
    }
}
