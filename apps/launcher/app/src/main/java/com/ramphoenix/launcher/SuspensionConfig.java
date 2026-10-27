package com.ramphoenix.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Pairing, kit limits and local preset definitions persist; pressure commands never do. */
final class SuspensionConfig {
    private final SharedPreferences prefs;
    final String address, name, kit;
    final int minimum, maximum;
    SuspensionConfig(Context context) {
        SharedPreferences p = context.getSharedPreferences("air_suspension", Context.MODE_PRIVATE);
        prefs = p;
        address = p.getString("address", ""); name = p.getString("name", "WirelessOne");
        kit = p.getString("kit", ""); minimum = p.getInt("minimum", -1); maximum = p.getInt("maximum", -1);
    }
    private static void checkPreset(int index) {
        if (index < 0 || index >= WirelessOneProtocol.NAMES.length)
            throw new IllegalArgumentException("Unknown preset");
    }
    String presetName(int index) {
        checkPreset(index);
        return prefs.getString("preset." + index + ".name", WirelessOneProtocol.NAMES[index]);
    }
    int presetPsi(int index) {
        checkPreset(index);
        return prefs.getInt("preset." + index + ".psi", WirelessOneProtocol.presetPsi(index, minimum));
    }
    static void savePreset(Context context, int index, String name, int psi) {
        checkPreset(index);
        String label = name.trim();
        if (label.isEmpty() || label.length() > 40)
            throw new IllegalArgumentException("Enter a preset name of 1–40 characters");
        SuspensionConfig config = new SuspensionConfig(context);
        if (!WirelessOneProtocol.validLimits(config.minimum, config.maximum)
                || psi < config.minimum || psi > config.maximum)
            throw new IllegalArgumentException("Pressure must be within the configured kit limits");
        if (!config.prefs.edit().putString("preset." + index + ".name", label)
                .putInt("preset." + index + ".psi", psi).commit())
            throw new IllegalStateException("Could not save preset");
    }
    static void save(Context context, String address, String name, String kit, int minimum, int maximum) {
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)
                || kit.trim().isEmpty() || !WirelessOneProtocol.validLimits(minimum, maximum))
            throw new IllegalArgumentException("Choose a paired manifold and enter valid kit limits");
        if (!context.getSharedPreferences("air_suspension", Context.MODE_PRIVATE).edit()
                .putString("address", address).putString("name", name).putString("kit", kit.trim())
                .putInt("minimum", minimum).putInt("maximum", maximum).commit())
            throw new IllegalStateException("Could not save suspension setup");
    }
}
