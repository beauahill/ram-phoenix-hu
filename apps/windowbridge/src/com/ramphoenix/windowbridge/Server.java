package com.ramphoenix.windowbridge;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import com.ramphoenix.windows.WindowAuth;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Shell-UID companion. Controls only the two explicitly selected app tasks. */
public final class Server {
    public static final int VERSION = 2, PORT = 43837;
    private final Object atm;
    private final Class<?> api;
    private final Map<String, Owned> owned = new HashMap<>();
    private final File journal=new File("/data/local/tmp/phoenix-window-owned.json");
    private JSONObject desired = new JSONObject();
    private long lease;
    private final Map<String,Long> nonces=new LinkedHashMap<>();
    private static final class Owned {
        String component; long generation; int id; Rect bounds;
        Owned(String c, long g, int i, Rect b) { component=c; generation=g; id=i; bounds=b; }
    }

    private Server() throws Exception {
        api=Class.forName("android.app.IActivityTaskManager");
        atm=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        if(journal.exists()) {
            JSONObject saved=new JSONObject(new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8));
            for(String key:new String[]{"pane_a","pane_b"}) if(saved.has(key)) {
                JSONObject entry=saved.getJSONObject(key);
                String component=entry.getString("component");
                ComponentName c=ComponentName.unflattenFromString(component);
                if(c==null || c.getPackageName().startsWith("com.ramphoenix.")) throw new SecurityException("Invalid task journal");
                owned.put(key,new Owned(component,entry.getLong("generation"),entry.getInt("id"),
                        Rect.unflattenFromString(entry.getString("bounds"))));
            }
            // Recover only recorded task IDs with matching packages; never enumerate/kill all apps.
            closeAll();
            if(!owned.isEmpty()) throw new IllegalStateException("Could not retire prior owned windows");
        }
    }

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid()!=2000 || args.length!=1) throw new SecurityException("Shell only");
        String token=new String(Files.readAllBytes(new File(args[0]).toPath()), StandardCharsets.UTF_8).trim();
        if (!token.matches("[a-f0-9]{64}")) throw new SecurityException("Invalid credential");
        // Binding is also the singleton lock: a second startup exits without touching windows.
        try (ServerSocket socket=new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", PORT));
            socket.setSoTimeout(100);
            Server server=new Server();
            System.out.println("Phoenix window bridge v"+VERSION+" ready");
            while (true) {
                // Enforce the lease even if another local process sends invalid requests.
                try { server.reconcile(); }
                catch(Exception failure) { server.closeAll(); server.desired=new JSONObject(); }
                boolean authenticated=false;
                try (Socket peer=socket.accept()) {
                    peer.setSoTimeout(500);
                    String line=readLine(peer.getInputStream());
                    JSONObject envelope=new JSONObject(line);
                    String nonce=envelope.optString("nonce"), payload=envelope.optString("payload");
                    if(!WindowAuth.verify(token,nonce,payload,envelope.optString("signature"))) continue;
                    JSONObject request=new JSONObject(payload);
                    long now=SystemClock.elapsedRealtime();
                    server.nonces.entrySet().removeIf(entry -> now-entry.getValue()>2500);
                    if(Math.abs(now-request.optLong("sentAt",0))>2000 || server.nonces.containsKey(nonce)) continue;
                    server.nonces.put(nonce,now);
                    authenticated=true;
                    if (request.has("panes")) { server.desired=request; server.lease=SystemClock.uptimeMillis(); }
                    server.reconcile();
                    JSONObject response=new JSONObject().put("version",VERSION).put("ok",true);
                    JSONArray tasks=new JSONArray();
                    for (Map.Entry<String,Owned> entry:server.owned.entrySet())
                        tasks.put(new JSONObject().put("key",entry.getKey()).put("task",entry.getValue().id));
                    response.put("tasks",tasks);
                    String body=response.toString();
                    JSONObject reply=new JSONObject().put("payload",body).put("signature",WindowAuth.sign(token,nonce,body));
                    peer.getOutputStream().write((reply+"\n").getBytes(StandardCharsets.UTF_8));
                } catch (SocketTimeoutException idle) {
                    // The loop head checks camera priority and the client lease.
                } catch (Exception failure) {
                    System.err.println("Window request: "+failure);
                    if(authenticated) { server.closeAll(); server.desired=new JSONObject(); }
                }
            }
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for (int c; (c=input.read())!=-1 && c!='\n';) {
            if (out.size()>=8192) throw new IOException("Request too large");
            out.write(c);
        }
        return out.toString("UTF-8");
    }

    @SuppressWarnings("unchecked")
    private List<Object> tasks() throws Exception {
        return (List<Object>)api.getMethod("getAllRootTaskInfosOnDisplay",int.class).invoke(atm,0);
    }
    private static Object field(Object value,String name) throws Exception { return value.getClass().getField(name).get(value); }
    private static String pkg(Object task) throws Exception {
        ComponentName base=(ComponentName)field(task,"baseActivity");
        return base==null ? "" : base.getPackageName();
    }
    private Object task(int id) throws Exception {
        for (Object t:tasks()) if ((int)field(t,"taskId")==id) return t;
        return null;
    }
    private void remove(Owned entry) throws Exception {
        Object task=task(entry.id);
        ComponentName c=ComponentName.unflattenFromString(entry.component);
        if (task!=null && c!=null && pkg(task).equals(c.getPackageName())) {
            api.getMethod("removeTask",int.class).invoke(atm,entry.id);
            System.out.println("Closed owned task "+entry.id);
        }
    }
    private void saveJournal() throws Exception {
        JSONObject saved=new JSONObject();
        for(Map.Entry<String,Owned> entry:owned.entrySet()) {
            Owned o=entry.getValue();
            saved.put(entry.getKey(),new JSONObject().put("component",o.component).put("generation",o.generation)
                    .put("id",o.id).put("bounds",o.bounds.flattenToString()));
        }
        File pending=new File(journal.getPath()+".new");
        try(FileOutputStream output=new FileOutputStream(pending)) {
            android.system.Os.chmod(pending.getPath(),0600);
            output.write(saved.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();
        }
        Files.move(pending.toPath(),journal.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private void retire(String key) throws Exception {
        Owned entry=owned.get(key);
        if(entry!=null) { remove(entry); owned.remove(key); saveJournal(); }
    }
    private void closeAll() {
        for (String key:new ArrayList<>(owned.keySet())) try { retire(key); } catch (Exception e) { System.err.println(e); }
    }

    private void reconcile() throws Exception {
        boolean camera=false;
        for (Object t:tasks()) {
            ComponentName top=(ComponentName)field(t,"topActivity");
            if (top!=null && top.getPackageName().equals("com.ramphoenix.camera") && (boolean)field(t,"visible")) camera=true;
        }
        if (SystemClock.uptimeMillis()-lease>1800 || !desired.optBoolean("visible")
                || desired.optBoolean("reverse") || camera) { closeAll(); return; }
        JSONArray panes=desired.optJSONArray("panes");
        // Retire all old generations before launching any replacements. Otherwise
        // moving one package between slots can reuse a task that the old slot then closes.
        Map<String,JSONObject> requested=new HashMap<>();
        for(int i=0;panes!=null && i<Math.min(2,panes.length());i++) {
            JSONObject p=panes.getJSONObject(i);requested.put(p.getString("key"),p);
        }
        for(String key:new ArrayList<>(owned.keySet())) {
            Owned old=owned.get(key);JSONObject p=requested.get(key);
            if(p==null || !p.getString("component").equals(old.component) || p.getLong("generation")!=old.generation)
                retire(key);
        }
        Set<String> keep=new HashSet<>(), packages=new HashSet<>();
        for (int i=0; panes!=null && i<Math.min(2,panes.length()); i++) {
            JSONObject pane=panes.getJSONObject(i);
            String key=pane.getString("key"), flat=pane.getString("component");
            if (!key.equals("pane_a") && !key.equals("pane_b")) throw new IllegalArgumentException("Unknown pane");
            ComponentName c=ComponentName.unflattenFromString(flat);
            if (c==null || !c.getPackageName().matches("[a-zA-Z0-9_.]+")
                    || !c.getClassName().matches("[a-zA-Z0-9_.$]+")
                    || c.getPackageName().startsWith("com.ramphoenix.") || !packages.add(c.getPackageName()))
                throw new IllegalArgumentException("Invalid or duplicate target");
            Rect b=Rect.unflattenFromString(pane.getString("bounds"));
            if (b==null || b.left<0 || b.top<0 || b.width()<200 || b.height()<150 || b.right>1080 || b.bottom>1920)
                throw new IllegalArgumentException("Invalid frame");
            keep.add(key);
            long generation=pane.getLong("generation");
            Owned old=owned.get(key);
            if (old!=null && (!old.component.equals(flat) || old.generation!=generation)) { retire(key); old=null; }
            if (old==null) {
                launch(c,b);
                Object newest=null;
                for (Object t:tasks()) if (pkg(t).equals(c.getPackageName())
                        && (newest==null || (long)field(t,"lastActiveTime")>(long)field(newest,"lastActiveTime"))) newest=t;
                if (newest==null) throw new IllegalStateException("App did not create a task");
                int id=(int)field(newest,"taskId");
                api.getMethod("resizeTask",int.class,Rect.class,int.class).invoke(atm,id,b,0);
                owned.put(key,new Owned(flat,generation,id,new Rect(b)));
                saveJournal();
                System.out.println("Opened "+key+" task="+id+" "+flat+" "+b);
            } else if (!old.bounds.equals(b)) {
                Object task=task(old.id);
                if (task!=null && pkg(task).equals(c.getPackageName())) {
                    api.getMethod("resizeTask",int.class,Rect.class,int.class).invoke(atm,old.id,b,0);
                    old.bounds=new Rect(b);
                    saveJournal();
                    System.out.println("Resized task "+old.id+" "+b);
                }
            }
        }
        for (String key:new ArrayList<>(owned.keySet())) if (!keep.contains(key)) retire(key);
    }

    private void launch(ComponentName component,Rect bounds) throws Exception {
        ActivityOptions options=ActivityOptions.makeBasic().setLaunchBounds(bounds);
        ActivityOptions.class.getMethod("setLaunchWindowingMode",int.class).invoke(options,5);
        ActivityOptions.class.getMethod("setTaskAlwaysOnTop",boolean.class).invoke(options,true);
        Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        for (Method m:api.getMethods()) if (m.getName().equals("startActivityAsUser")) {
            Class<?>[] types=m.getParameterTypes(); Object[] args=new Object[types.length]; boolean pkg=false;
            for (int i=0;i<types.length;i++) {
                if (types[i]==String.class && !pkg) { args[i]="com.android.shell"; pkg=true; }
                else if (types[i]==Intent.class) args[i]=intent;
                else if (types[i]==Bundle.class) args[i]=options.toBundle();
                else if (types[i]==int.class) args[i]=0;
                else if (types[i]==boolean.class) args[i]=false;
            }
            int result=(int)m.invoke(atm,args);
            if (result<0) throw new IllegalStateException("Launch result "+result);
            return;
        }
        throw new IllegalStateException("Unsupported task manager");
    }
}
