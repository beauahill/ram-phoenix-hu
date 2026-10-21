package com.ramphoenix.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Placeholder for boot-time wiring. The reverse trigger is currently handled by
 * MainActivity's ReverseMonitor while it runs; a later milestone can promote the
 * monitor into a foreground service started here so reverse-at-boot works before
 * the activity is ever opened (stock path: SyuJniNative cmd 32 GET_BOOT_REVERSE_STATUS).
 */
public class ReverseReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Log.i("PhoenixCam", "boot received: " + (intent != null ? intent.getAction() : "null"));
        // intentionally minimal for milestone 1
    }
}
