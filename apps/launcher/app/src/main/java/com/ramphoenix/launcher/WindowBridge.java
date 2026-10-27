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
        File file=new File(context.getNoBackupFilesDir(),"window-token");
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
        catch(Exception e) { status="Split app windows need one-time setup"; }
    }
    private void bootstrap(boolean allowApproval) throws Exception {
        if(reverse) return;
        try(LocalAdb adb=new LocalAdb(context,allowApproval)) {
            byte[] jar;
            try(InputStream input=context.getAssets().open("window_bridge.jar")) { jar=input.readAllBytes(); }
            adb.push("/data/local/tmp/phoenix-window-bridge.jar",jar);
            adb.push("/data/local/tmp/phoenix-window-token",token.getBytes(StandardCharsets.US_ASCII));
            adb.shell("nohup sh -c 'CLASSPATH=/data/local/tmp/phoenix-window-bridge.jar app_process / --nice-name=phoenix-window-bridge com.ramphoenix.windowbridge.Server /data/local/tmp/phoenix-window-token' </dev/null >/data/local/tmp/phoenix-window-bridge.log 2>&1 &");
        }
    }
}
