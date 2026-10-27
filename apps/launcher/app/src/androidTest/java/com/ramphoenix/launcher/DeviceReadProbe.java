package com.ramphoenix.launcher;

import android.app.Instrumentation;
import android.bluetooth.*;
import android.content.Context;
import android.os.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Installed-device diagnostics. No shell commands or GATT writes are implemented here. */
final class DeviceReadProbe {
    private final Instrumentation runner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CountDownLatch done = new CountDownLatch(1);
    private final Bundle result = new Bundle();
    private final ArrayDeque<BluetoothGattCharacteristic> reads = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private Throwable failure;
    DeviceReadProbe(Instrumentation runner) { this.runner = runner; }

    void run(String operation) {
        int code = 0;
        long start = SystemClock.elapsedRealtime();
        try {
            if (operation.equals("device-adb") || operation.equals("device-adb-setup")) {
                // Handshake only. Explicit setup may display Android's own local-key approval.
                try (LocalAdb ignored = new LocalAdb(runner.getTargetContext(), operation.endsWith("setup"))) {
                    result.putString("result", "App-owned local ADB key authenticated; no commands sent");
                }
            } else if (operation.equals("device-air")) {
                main.post(() -> {
                    try { connectAir(); } catch (Throwable e) { fail(e); }
                });
                if (!done.await(25, TimeUnit.SECONDS)) throw new IllegalStateException("Read-only GATT check timed out");
                if (failure != null) throw failure;
                result.putString("result", "Paired manifold fields read; no characteristics or descriptors written");
            } else throw new IllegalArgumentException("Unknown diagnostic operation");
            code = -1;
        } catch (Throwable e) {
            StringWriter trace = new StringWriter(); e.printStackTrace(new PrintWriter(trace));
            result.putString("failure", trace.toString());
        } finally {
            runner.runOnMainSync(() -> {
                if (gatt != null) { try { gatt.disconnect(); } finally { gatt.close(); gatt = null; } }
            });
            result.putLong("elapsedMs", SystemClock.elapsedRealtime() - start);
        }
        runner.finish(code, result);
    }

    @SuppressWarnings("MissingPermission")
    private void connectAir() {
        BluetoothAdapter adapter = runner.getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth is off");
        BluetoothDevice selected = null;
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            String name = device.getName();
            if (name != null && name.toLowerCase(Locale.US).startsWith("wirelessone")) {
                if (selected != null) throw new IllegalStateException("Multiple paired manifolds; select one explicitly");
                selected = device;
            }
        }
        if (selected == null) throw new IllegalStateException("No paired WirelessOne manifold");
        result.putString("manifold", selected.getName());
        gatt = selected.connectGatt(runner.getTargetContext(), false, callback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) throw new IllegalStateException("Could not start GATT connection");
    }

    private void fail(Throwable e) { failure = e; done.countDown(); }
    private void onMain(Runnable action) {
        main.post(() -> { if (done.getCount() != 0) try { action.run(); } catch (Throwable e) { fail(e); } });
    }
    private void add(BluetoothGatt serviceConnection, UUID service, UUID characteristic, boolean required) {
        BluetoothGattService found = serviceConnection.getService(service);
        BluetoothGattCharacteristic field = found == null ? null : found.getCharacteristic(characteristic);
        if (field == null) {
            if (required) throw new IllegalStateException("Missing field " + characteristic);
            result.putString(characteristic.toString(), "not present"); return;
        }
        if ((field.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0)
            throw new IllegalStateException("Field is not readable: " + characteristic);
        reads.add(field);
    }
    private void next() {
        if (reads.isEmpty()) { done.countDown(); return; }
        if (!gatt.readCharacteristic(reads.peek())) throw new IllegalStateException("GATT read could not start");
    }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int status, int state) {
            onMain(() -> {
                if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED)
                    throw new IllegalStateException("GATT disconnected/status " + status);
                if (state == BluetoothProfile.STATE_CONNECTED && !connection.discoverServices())
                    throw new IllegalStateException("Service discovery could not start");
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt connection, int status) {
            onMain(() -> {
                if (status != BluetoothGatt.GATT_SUCCESS) throw new IllegalStateException("Service discovery status " + status);
                add(connection, WirelessOneProtocol.BOOT, WirelessOneProtocol.MODE, true);
                add(connection, WirelessOneProtocol.CONTROL, WirelessOneProtocol.STATE, true);
                add(connection, WirelessOneProtocol.CONTROL, WirelessOneProtocol.FAULTS, true);
                add(connection, WirelessOneProtocol.SETTINGS, WirelessOneProtocol.TARGET, true);
                add(connection, WirelessOneProtocol.SETTINGS, WirelessOneProtocol.TANK, false);
                for (int field : new int[]{0x2a29, 0x2a24, 0x2a26})
                    add(connection, WirelessOneProtocol.uuid(0x180a), WirelessOneProtocol.uuid(field), false);
                next();
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic field, byte[] value, int status) {
            byte[] copy = value == null ? null : value.clone();
            onMain(() -> accept(field, copy, status));
        }
        @Override @SuppressWarnings("deprecation")
        public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic field, int status) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] copy = field.getValue() == null ? null : field.getValue().clone();
                onMain(() -> accept(field, copy, status));
            }
        }
    };
    private void accept(BluetoothGattCharacteristic field, byte[] value, int status) {
        if (reads.peek() == null || !reads.peek().getUuid().equals(field.getUuid()))
            throw new IllegalStateException("Unexpected read callback");
        if (status != BluetoothGatt.GATT_SUCCESS || value == null)
            throw new IllegalStateException("Read failed " + field.getUuid() + " status " + status);
        StringBuilder hex = new StringBuilder();
        for (byte b : value) hex.append(String.format(Locale.US, "%02x", b & 255));
        result.putString(field.getUuid().toString(), hex.toString());
        if (field.getUuid().equals(WirelessOneProtocol.STATE)) {
            WirelessOneProtocol.length(value, 3);
            result.putDouble("measuredPsi", WirelessOneProtocol.psi(WirelessOneProtocol.pressure(value, 1)));
        }
        if (field.getUuid().equals(WirelessOneProtocol.TARGET))
            result.putDouble("reportedTargetPsi", WirelessOneProtocol.psi(WirelessOneProtocol.targetPressure(value)));
        reads.remove(); next();
    }
}
