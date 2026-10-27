package com.ramphoenix.launcher;

import java.util.UUID;

/** Per-connection, monotonic-time measurements. A desired setting is never a measurement. */
final class SuspensionState {
    static final long FRESH_MS = 6500;
    int actualKpa = -1, targetKpa = -1, flags;
    boolean normal, noFaults, tank, tankPresent;
    long actualAt = -1, targetAt = -1, modeAt = -1, faultsAt = -1, tankAt = -1;

    void reset(boolean hasTank) {
        actualKpa = targetKpa = -1;
        flags = 0; normal = noFaults = tank = false; tankPresent = hasTank;
        actualAt = targetAt = modeAt = faultsAt = tankAt = -1;
    }
    static boolean fresh(long at, long now) { return at >= 0 && now >= at && now - at <= FRESH_MS; }

    void accept(UUID uuid, byte[] bytes, long now) {
        // Invalidate the relevant field first; malformed/new firmware packets cannot retain readiness.
        if (uuid.equals(WirelessOneProtocol.STATE)) {
            actualAt = -1; actualKpa = -1;
            WirelessOneProtocol.length(bytes, 3);
            actualKpa = WirelessOneProtocol.pressure(bytes, 1); flags = bytes[0] & 255; actualAt = now;
        } else if (uuid.equals(WirelessOneProtocol.TARGET)) {
            targetAt = -1; targetKpa = -1;
            targetKpa = WirelessOneProtocol.targetPressure(bytes); targetAt = now;
        } else if (uuid.equals(WirelessOneProtocol.MODE)) {
            modeAt = -1; normal = false;
            WirelessOneProtocol.length(bytes, 1); normal = bytes[0] == 0; modeAt = now;
        } else if (uuid.equals(WirelessOneProtocol.FAULTS)) {
            faultsAt = -1; noFaults = false;
            WirelessOneProtocol.length(bytes, 1);
            boolean clear = true; for (byte value : bytes) clear &= value == 0;
            // Conservative until the installed manifold's diagnostics have been captured.
            noFaults = clear; faultsAt = now;
        } else if (uuid.equals(WirelessOneProtocol.TANK)) {
            tankAt = -1; tank = true;
            WirelessOneProtocol.length(bytes, 1);
            if (bytes[0] != 0 && bytes[0] != 1) throw new IllegalArgumentException("Unknown tank mode");
            tank = bytes[0] == 1; tankAt = now;
        }
    }

    String blocked(long now, boolean connected, boolean trustedApp, int minimum, int maximum) {
        if (!connected) return "Connect to WirelessOne";
        if (!trustedApp) return "Installed WirelessOne app needs compatibility verification";
        if (!WirelessOneProtocol.validLimits(minimum, maximum)) return "Enter the air-spring kit pressure limits in Setup";
        if (!fresh(modeAt, now)) return "Waiting for controller mode";
        if (!normal) return "Controller is in firmware-update mode";
        if (!fresh(faultsAt, now)) return "Waiting for controller diagnostics";
        if (!noFaults) return "Controller reports fault flags; check WirelessOne";
        if (tankPresent && !fresh(tankAt, now)) return "Waiting for air-tank mode";
        if (tankPresent && tank) return "Air-tank mode is enabled; spring presets are unavailable";
        if (!fresh(actualAt, now) || !fresh(targetAt, now)) return "Waiting for fresh pressure readings";
        return null;
    }

    String activity() {
        if ((flags & 1) != 0) return "Inflating";
        if ((flags & 8) != 0) return "Deflating";
        return "Connected";
    }
}
