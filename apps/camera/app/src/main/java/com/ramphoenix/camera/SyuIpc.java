package com.ramphoenix.camera;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal client for the SYU/LSEC "ToolkitService" IPC that every stock app on
 * this head unit uses (com.syu.ms / app.ToolkitService). Hand-written binder
 * proxies, no AIDL, so this app has zero dependency on SYU jars.
 *
 * Protocol (from the com.syu.doublecamera / com.syu.canbus decompiles, see
 * docs/CANBUS-IPC.md):
 *
 *   bind  -> IRemoteToolkit   ("com.syu.ipc.IRemoteToolkit")
 *            txn 1  getRemoteModule(int moduleCode) -> IRemoteModule binder
 *   module-> IRemoteModule    ("com.syu.ipc.IRemoteModule")
 *            txn 1  cmd(int code, int[] ints, float[] flts, String[] strs)   oneway
 *            txn 2  get(int code, int[] ints, float[] flts, String[] strs)   -> ModuleObject
 *            txn 3  register(IModuleCallback cb, int updateCode, int update) oneway
 *            txn 4  unregister(IModuleCallback cb, int updateCode)           oneway
 *   cb    -> IModuleCallback  ("com.syu.ipc.IModuleCallback")
 *            txn 1  update(int code, int[] ints, float[] flts, String[] strs) oneway
 *
 * The stock camera app subscribes to MODULE_MAIN update 12 (U_BACKCAR) and reads
 * ints[0] == 1 for "in reverse". That is the trigger the sysfs flag turned out
 * not to be.
 */
public final class SyuIpc {

    private static final String TAG = "PhoenixCam";

    public static final String SERVICE_PKG = "com.syu.ms";
    public static final String SERVICE_CLS = "app.ToolkitService";

    private static final String DESC_TOOLKIT  = "com.syu.ipc.IRemoteToolkit";
    private static final String DESC_MODULE   = "com.syu.ipc.IRemoteModule";
    private static final String DESC_CALLBACK = "com.syu.ipc.IModuleCallback";

    private static final int TXN_GET_MODULE = 1;
    private static final int TXN_CMD        = 1;
    private static final int TXN_GET        = 2;
    private static final int TXN_REGISTER   = 3;
    private static final int TXN_UNREGISTER = 4;
    private static final int TXN_UPDATE     = 1;

    // FinalRemoteToolkit.MODULE_CODE_*
    public static final int MODULE_MAIN   = 0;
    public static final int MODULE_RADIO  = 1;
    public static final int MODULE_BT     = 2;
    public static final int MODULE_SOUND  = 4;
    public static final int MODULE_CANBUS = 7;

    // FinalMain update codes (MODULE_MAIN)
    public static final int U_BACKCAR            = 12;   // ints[0] 1=reverse engaged
    public static final int U_BACKCAR_RADAR      = 22;
    public static final int U_BACKCAR_TRACK_ENABLE = 23;
    public static final int U_BACKCAR_MIRROR     = 24;
    public static final int U_STEER_ANGLE_INFO   = 41;   // ints[1]=SCALE_MAX ints[2]=ANGLE_MAX
    public static final int U_ACC_ON             = 50;
    public static final int U_BACKCAR_360_CAMERA = 81;

    // FinalCanbus (MODULE_CANBUS)
    public static final int U_CANBUS_ID = 1000;          // DataCanbus.DATA[1000]
    public static final int CANBUS_ID_RAM1500   = 6357447; // CAR_455_LZ_Dodge_RAM1500
    public static final int CANBUS_ID_RAM1500_H = 6422983; // CAR_455_LZ_Dodge_RAM1500_H

    /** Values pushed by the service for a registered update code. Called on a binder thread. */
    public interface UpdateListener {
        void onUpdate(int code, int[] ints, float[] flts, String[] strs);
    }

    /** Connection state. Called on the main thread. */
    public interface ConnectionListener {
        void onConnected(boolean connected);
    }

    private static final class Sub {
        final int module; final int code; final UpdateListener l; final CallbackBinder cb;
        Sub(int module, int code, UpdateListener l) {
            this.module = module; this.code = code; this.l = l; this.cb = new CallbackBinder(l);
        }
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Sub> subs = new ArrayList<>();
    private ConnectionListener connListener;
    private IBinder toolkit;
    private volatile boolean wantBound;
    private int backoffMs = 1000;

    public SyuIpc(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public void setConnectionListener(ConnectionListener l) { connListener = l; }

    public boolean isConnected() { IBinder t = toolkit; return t != null && t.isBinderAlive(); }

    /** Bind (and keep rebinding through SYU service restarts). Idempotent. */
    public synchronized void connect() {
        wantBound = true;
        if (toolkit != null) return;
        Intent i = new Intent().setClassName(SERVICE_PKG, SERVICE_CLS);
        boolean ok;
        try {
            ok = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            ok = false;
            Log.e(TAG, "ipc bind refused: " + e.getMessage());
        }
        if (!ok) {
            Log.w(TAG, "ipc bind failed, retry in " + backoffMs + "ms");
            scheduleReconnect();
        }
    }

    public synchronized void disconnect() {
        wantBound = false;
        main.removeCallbacks(reconnect);
        unregisterAll();
        try { ctx.unbindService(conn); } catch (IllegalArgumentException ignored) {}
        toolkit = null;
    }

    /**
     * Subscribe to an update code on a module. Survives reconnects: the
     * subscription is re-registered every time the service comes back.
     */
    public synchronized void subscribe(int module, int code, UpdateListener l) {
        Sub s = new Sub(module, code, l);
        subs.add(s);
        if (toolkit != null) register(s);
    }

    /** Synchronous read of the current value for a code. null if not connected / no data. */
    public int[] getInts(int module, int code) {
        IBinder mod = module(module);
        if (mod == null) return null;
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            in.writeInterfaceToken(DESC_MODULE);
            in.writeInt(code);
            in.writeIntArray(null);
            in.writeFloatArray(null);
            in.writeStringArray(null);
            mod.transact(TXN_GET, in, out, 0);
            out.readException();
            if (out.readInt() == 0) return null;
            int[] ints = out.createIntArray();
            out.createFloatArray();
            out.createStringArray();
            return ints;
        } catch (RemoteException e) {
            Log.w(TAG, "ipc get(" + module + "," + code + ") failed: " + e);
            return null;
        } finally { in.recycle(); out.recycle(); }
    }

    /** Fire a command at a module. e.g. cmd(MODULE_CANBUS, 0, new int[]{24, button}) for climate. */
    public boolean cmd(int module, int code, int[] ints) {
        IBinder mod = module(module);
        if (mod == null) return false;
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            in.writeInterfaceToken(DESC_MODULE);
            in.writeInt(code);
            in.writeIntArray(ints);
            in.writeFloatArray(null);
            in.writeStringArray(null);
            mod.transact(TXN_CMD, in, out, IBinder.FLAG_ONEWAY);
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "ipc cmd(" + module + "," + code + ") failed: " + e);
            return false;
        } finally { in.recycle(); out.recycle(); }
    }

    // ---- internals -------------------------------------------------------

    private IBinder module(int moduleCode) {
        IBinder t = toolkit;
        if (t == null) return null;
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            in.writeInterfaceToken(DESC_TOOLKIT);
            in.writeInt(moduleCode);
            t.transact(TXN_GET_MODULE, in, out, 0);
            out.readException();
            return out.readStrongBinder();
        } catch (RemoteException e) {
            Log.w(TAG, "ipc getRemoteModule(" + moduleCode + ") failed: " + e);
            return null;
        } finally { in.recycle(); out.recycle(); }
    }

    private void register(Sub s) {
        IBinder mod = module(s.module);
        if (mod == null) { Log.w(TAG, "ipc: module " + s.module + " unavailable"); return; }
        Parcel in = Parcel.obtain(), out = Parcel.obtain();
        try {
            in.writeInterfaceToken(DESC_MODULE);
            in.writeStrongBinder(s.cb);
            in.writeInt(s.code);
            in.writeInt(1); // update=1: push current value on register (stock apps do this)
            mod.transact(TXN_REGISTER, in, out, IBinder.FLAG_ONEWAY);
            Log.i(TAG, "ipc registered module=" + s.module + " code=" + s.code);
        } catch (RemoteException e) {
            Log.w(TAG, "ipc register failed: " + e);
        } finally { in.recycle(); out.recycle(); }
    }

    private void unregisterAll() {
        for (Sub s : subs) {
            IBinder mod = module(s.module);
            if (mod == null) continue;
            Parcel in = Parcel.obtain(), out = Parcel.obtain();
            try {
                in.writeInterfaceToken(DESC_MODULE);
                in.writeStrongBinder(s.cb);
                in.writeInt(s.code);
                mod.transact(TXN_UNREGISTER, in, out, IBinder.FLAG_ONEWAY);
            } catch (RemoteException ignored) {
            } finally { in.recycle(); out.recycle(); }
        }
    }

    private final Runnable reconnect = new Runnable() {
        @Override public void run() {
            if (!wantBound) return;
            backoffMs = Math.min(backoffMs * 2, 10_000);
            connect();
        }
    };

    private void scheduleReconnect() {
        main.removeCallbacks(reconnect);
        main.postDelayed(reconnect, backoffMs);
    }

    private final IBinder.DeathRecipient death = () -> {
        Log.w(TAG, "ipc: ToolkitService died");
        synchronized (SyuIpc.this) { toolkit = null; }
        main.post(() -> { if (connListener != null) connListener.onConnected(false); });
        try { SyuIpc.this.ctx.unbindService(SyuIpc.this.conn); } catch (IllegalArgumentException ignored) {}
        if (wantBound) scheduleReconnect();
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (SyuIpc.this) {
                toolkit = service;
                backoffMs = 1000;
                try { service.linkToDeath(death, 0); } catch (RemoteException ignored) {}
                for (Sub s : subs) register(s);
            }
            Log.i(TAG, "ipc connected to " + name.flattenToShortString());
            if (connListener != null) connListener.onConnected(true);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (SyuIpc.this) { toolkit = null; }
            Log.w(TAG, "ipc disconnected");
            if (connListener != null) connListener.onConnected(false);
            if (wantBound) scheduleReconnect();
        }
        @Override public void onBindingDied(ComponentName name) {
            synchronized (SyuIpc.this) { toolkit = null; }
            try { ctx.unbindService(this); } catch (IllegalArgumentException ignored) {}
            if (wantBound) scheduleReconnect();
        }
    };

    /** Our side of IModuleCallback. */
    private static final class CallbackBinder extends Binder {
        private final UpdateListener l;
        CallbackBinder(UpdateListener l) { this.l = l; }

        @Override public String getInterfaceDescriptor() { return DESC_CALLBACK; }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == TXN_UPDATE) {
                data.enforceInterface(DESC_CALLBACK);
                int c = data.readInt();
                int[] ints = data.createIntArray();
                float[] flts = data.createFloatArray();
                String[] strs = data.createStringArray();
                try { l.onUpdate(c, ints, flts, strs); }
                catch (RuntimeException e) { Log.e(TAG, "ipc listener threw", e); }
                return true;
            }
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESC_CALLBACK);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
