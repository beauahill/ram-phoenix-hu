package com.ramphoenix.launcher;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.security.MessageDigest;
import java.util.*;

/** Foreground-only BLE client. Main-thread serialized GATT operations; read-only reconnects.
 * The only characteristic this class can write is FC10, after an explicit user request. */
final class WirelessOneClient {
    private static WirelessOneClient instance;
    static synchronized WirelessOneClient get(Context context) {
        if (instance == null) instance = new WirelessOneClient(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<Object> owners = new HashSet<>();
    private final Set<Runnable> listeners = new HashSet<>();
    private final ArrayDeque<Operation> queue = new ArrayDeque<>();
    final SuspensionState state = new SuspensionState();
    private BluetoothGatt gatt;
    private Operation operation;
    private int generation;
    private boolean connected, connecting, trustedApp, yielded, reversing;
    private SuspensionCommand command;
    private String status = "Set up WirelessOne";

    private WirelessOneClient(Context context) { this.context = context; state.reset(false); }
    private static long now() { return SystemClock.elapsedRealtime(); }
    void listen(Runnable listener) { listeners.add(listener); }
    void unlisten(Runnable listener) { listeners.remove(listener); }
    private void changed() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
    void start(Object owner) { owners.add(owner); if (gatt == null && !yielded && !reversing) connect(false); }
    void stop(Object owner) { owners.remove(owner); if (owners.isEmpty()) close("Disconnected"); }
    void reverse(boolean active) {
        reversing = active;
        if (active) close("Camera active");
        else if (!owners.isEmpty() && !yielded && gatt == null) connect(false);
    }
    void yieldToOfficialApp() { yielded = true; close("WirelessOne app has control · tap Connect to return"); }
    void disconnect() { yielded = true; close("Disconnected · tap Connect to return"); }

    String status() { return status; }
    boolean connected() { return connected; }
    boolean pending() { return command != null && !command.cancelled && !command.confirmed; }
    String blocked() {
        if (reversing) return "Camera active";
        if (pending()) return "Waiting for target readback";
        SuspensionConfig config = new SuspensionConfig(context);
        return state.blocked(now(), connected, trustedApp, config.minimum, config.maximum);
    }
    String actualText() {
        return SuspensionState.fresh(state.actualAt, now())
                ? String.format(Locale.US, "%.0f", WirelessOneProtocol.psi(state.actualKpa)) : "—";
    }
    String targetText() {
        return SuspensionState.fresh(state.targetAt, now())
                ? String.format(Locale.US, "%.0f psi", WirelessOneProtocol.psi(state.targetKpa)) : "—";
    }

    List<BluetoothDevice> paired() {
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        if (!permission()) return devices;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) return devices;
        try { for (BluetoothDevice device : adapter.getBondedDevices()) {
            String name = device.getName();
            if (name != null && name.toLowerCase(Locale.US).startsWith("wirelessone")) devices.add(device);
        } } catch (SecurityException revoked) { return new ArrayList<>(); }
        devices.sort(Comparator.comparing(BluetoothDevice::getAddress));
        return devices;
    }
    private boolean permission() { return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }
    private boolean compatibleApp() {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(
                    WirelessOneProtocol.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null || !"2.3.0".equals(info.versionName)) return false;
            for (android.content.pm.Signature signer : info.signingInfo.getApkContentsSigners()) {
                StringBuilder hex = new StringBuilder();
                for (byte value : MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()))
                    hex.append(String.format(Locale.US, "%02x", value & 255));
                if (WirelessOneProtocol.REFERENCE_SIGNER.equals(hex.toString())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    void connect(boolean explicit) {
        if (reversing || owners.isEmpty()) return;
        if (explicit) { yielded = false; close("Connecting…"); }
        if (gatt != null || yielded) return;
        SuspensionConfig config = new SuspensionConfig(context);
        if (config.address.isEmpty()) { status = "Choose the paired manifold in Setup"; changed(); return; }
        if (!permission()) { status = "Bluetooth permission is required"; changed(); return; }
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) { status = "Turn on Bluetooth to connect"; changed(); return; }
            BluetoothDevice device = null;
            for (BluetoothDevice candidate : paired()) if (candidate.getAddress().equals(config.address)) device = candidate;
            if (device == null) { status = "Pair this HU using the WirelessOne app, then choose it in Setup"; changed(); return; }
            generation++; trustedApp = compatibleApp(); state.reset(false);
            connecting = true; status = "Connecting to " + config.name + "…";
            gatt = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { close("Bluetooth connection could not start"); return; }
            int token = generation;
            ui.postDelayed(() -> { if (token == generation && connecting) close("Connection timed out · close other WirelessOne clients and tap Connect"); }, 25000);
            changed();
        } catch (SecurityException denied) { close("Bluetooth permission is required"); }
        catch (RuntimeException failure) { close("Bluetooth connection failed · tap Connect to retry"); }
    }

    private void close(String reason) {
        boolean uncertain = command != null && command.attempted && !command.confirmed && !command.cancelled;
        if (command != null) command.cancel();
        command = null; generation++; connected = connecting = false;
        queue.clear(); operation = null; ui.removeCallbacks(poll);
        BluetoothGatt old = gatt; gatt = null; state.reset(false);
        if (old != null) {
            try { old.disconnect(); } catch (RuntimeException ignored) {}
            try { old.close(); } catch (RuntimeException ignored) {}
        }
        status = uncertain ? reason + " · last target change was not confirmed" : reason;
        changed();
    }

    boolean setPressure(int psi) {
        String block = blocked();
        if (block != null) { status = block; changed(); return false; }
        SuspensionConfig config = new SuspensionConfig(context);
        byte[] bytes;
        try { bytes = WirelessOneProtocol.target(psi, config.minimum, config.maximum); }
        catch (IllegalArgumentException invalid) { status = invalid.getMessage(); changed(); return false; }
        command = new SuspensionCommand(generation, WirelessOneProtocol.kpa(psi), now());
        queue.addFirst(new Operation(WirelessOneProtocol.TARGET, bytes, command));
        status = "Requesting " + psi + " psi…"; changed(); next(); return true;
    }

    private BluetoothGattCharacteristic characteristic(UUID uuid) {
        if (gatt == null) return null;
        UUID service = uuid.equals(WirelessOneProtocol.MODE) ? WirelessOneProtocol.BOOT
                : uuid.equals(WirelessOneProtocol.TARGET) || uuid.equals(WirelessOneProtocol.TANK)
                ? WirelessOneProtocol.SETTINGS : WirelessOneProtocol.CONTROL;
        BluetoothGattService s = gatt.getService(service);
        return s == null ? null : s.getCharacteristic(uuid);
    }
    private boolean readable(UUID uuid) {
        BluetoothGattCharacteristic c = characteristic(uuid);
        return c != null && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!connected) return;
            if (command != null && command.expired(now())) {
                command.cancel(); command = null;
                status = "Target change not confirmed · check the reported target";
            }
            if (operation == null && queue.isEmpty()) {
                queue.add(new Operation(WirelessOneProtocol.MODE));
                queue.add(new Operation(WirelessOneProtocol.FAULTS));
                if (state.tankPresent) queue.add(new Operation(WirelessOneProtocol.TANK));
                queue.add(new Operation(WirelessOneProtocol.STATE));
                queue.add(new Operation(WirelessOneProtocol.TARGET));
                next();
            }
            changed(); ui.postDelayed(this, 2000);
        }
    };

    private void next() {
        if (!connected || operation != null || queue.isEmpty()) return;
        Operation op = queue.removeFirst();
        BluetoothGattCharacteristic c = characteristic(op.uuid);
        if (c == null) { close("Required Bluetooth field is unavailable"); return; }
        try {
            if (op.write != null) {
                SuspensionConfig config = new SuspensionConfig(context);
                String block = state.blocked(now(), connected, trustedApp, config.minimum, config.maximum);
                if (op.request != command || block != null || reversing || owners.isEmpty()
                        || !op.request.beginWrite(generation, now())) {
                    op.request.cancel(); command = null;
                    status = block == null ? "Request expired · select the preset again" : block;
                    changed(); next(); return;
                }
            }
            operation = op; op.startedAt = now();
            boolean started;
            if (op.write == null) started = gatt.readCharacteristic(c);
            else {
                // The UUID and three-byte payload were constructed here, never supplied by an Intent.
                int type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    started = gatt.writeCharacteristic(c, op.write, type) == BluetoothStatusCodes.SUCCESS;
                else { c.setWriteType(type); c.setValue(op.write); started = gatt.writeCharacteristic(c); }
            }
            if (!started) { close(op.write == null ? "Pressure read could not start · tap Connect" : "Target write could not start; no retry was sent"); return; }
            int token = generation;
            ui.postDelayed(() -> { if (token == generation && operation == op) close("Bluetooth operation timed out · tap Connect"); }, 8000);
        } catch (RuntimeException failure) { close("Bluetooth operation failed · tap Connect"); }
    }

    private void read(BluetoothGatt source, BluetoothGattCharacteristic c, byte[] bytes, int result) {
        if (source != gatt || operation == null || operation.write != null || !operation.uuid.equals(c.getUuid())) return;
        Operation op = operation; operation = null;
        if (result != BluetoothGatt.GATT_SUCCESS) { close("Controller read failed (" + result + ") · tap Connect"); return; }
        if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            StringBuilder hex = new StringBuilder();
            if (bytes != null) for (byte value : bytes) hex.append(String.format(Locale.US,"%02x",value & 255));
            android.util.Log.d("PhoenixAir","read " + op.uuid + " " + hex);
        }
        try { state.accept(op.uuid, bytes, now()); }
        catch (IllegalArgumentException unsupported) { close("Controller data is unsupported; use WirelessOne"); return; }
        if (op.uuid.equals(WirelessOneProtocol.TARGET) && command != null
                && command.observe(generation, state.targetKpa, op.startedAt)) {
            status = "Target accepted · measured pressure is shown separately";
            command = null;
        } else if (!pending() && (status.startsWith("Reading") || status.startsWith("Connecting"))) status = "Connected";
        changed(); next();
    }

    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int result, int newState) {
            ui.post(() -> {
                if (source != gatt) return;
                if (result != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    close("WirelessOne disconnected · tap Connect"); return;
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try { if (!source.discoverServices()) close("Bluetooth service discovery failed"); }
                    catch (SecurityException denied) { close("Bluetooth permission is required"); }
                }
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt source, int result) {
            ui.post(() -> {
                if (source != gatt) return;
                if (result != BluetoothGatt.GATT_SUCCESS || !readable(WirelessOneProtocol.STATE)
                        || !readable(WirelessOneProtocol.TARGET) || !readable(WirelessOneProtocol.MODE)
                        || !readable(WirelessOneProtocol.FAULTS)) {
                    close("This manifold does not expose the expected WirelessOne fields"); return;
                }
                BluetoothGattCharacteristic target = characteristic(WirelessOneProtocol.TARGET);
                if ((target.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
                    close("This manifold does not support the expected target command"); return;
                }
                if (characteristic(WirelessOneProtocol.TANK) != null && !readable(WirelessOneProtocol.TANK)) {
                    close("Air-tank mode cannot be checked on this manifold"); return;
                }
                state.reset(readable(WirelessOneProtocol.TANK));
                connecting = false; connected = true; status = "Reading controller…";
                ui.removeCallbacks(poll); ui.post(poll); changed();
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic c, byte[] value, int result) {
            byte[] copy = value == null ? null : value.clone(); ui.post(() -> read(source, c, copy, result));
        }
        @Override public void onCharacteristicRead(BluetoothGatt source, BluetoothGattCharacteristic c, int result) {
            byte[] value = c.getValue(); byte[] copy = value == null ? null : value.clone(); ui.post(() -> read(source, c, copy, result));
        }
        @Override public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic c, int result) {
            ui.post(() -> {
                if (source != gatt || operation == null || operation.write == null || !operation.uuid.equals(c.getUuid())) return;
                Operation op = operation; operation = null;
                if (result != BluetoothGatt.GATT_SUCCESS) { close("Target write failed (" + result + "); no retry was sent"); return; }
                if (op.request != command) { close("Target session changed; check WirelessOne"); return; }
                op.request.written(now()); status = "Checking reported target…";
                queue.addFirst(new Operation(WirelessOneProtocol.TARGET));
                changed(); next();
            });
        }
    };

    private static final class Operation {
        final UUID uuid; final byte[] write; final SuspensionCommand request;
        long startedAt;
        Operation(UUID uuid) { this(uuid, null, null); }
        Operation(UUID uuid, byte[] write, SuspensionCommand request) { this.uuid = uuid; this.write = write; this.request = request; }
    }
}
