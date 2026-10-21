package com.ramphoenix.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Boot wiring: the moment the unit finishes booting, start {@link ReverseService}
 * so reverse-gear brings up the camera even before the app has ever been opened
 * (the stock boot-reverse path is SyuJniNative cmd 32 GET_BOOT_REVERSE_STATUS;
 * we achieve the same by having the service poll the reverse flag from boot).
 */
public class ReverseReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i("PhoenixCam", "boot received: " + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            ReverseService.ensureRunning(context);
        }
    }
}
