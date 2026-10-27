package com.ramphoenix.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Only pairing choice and owner-confirmed kit limits persist; pressure commands never do. */
final class SuspensionConfig {
    final String address, name, kit;
    final int minimum, maximum;
    SuspensionConfig(Context context) {
        SharedPreferences p = context.getSharedPreferences("air_suspension", Context.MODE_PRIVATE);
        address = p.getString("address", ""); name = p.getString("name", "WirelessOne");
        kit = p.getString("kit", ""); minimum = p.getInt("minimum", -1); maximum = p.getInt("maximum", -1);
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
