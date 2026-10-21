package com.ramphoenix.camera;

import android.util.Log;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Thin reader/writer for the /sys/lsecdcamctl/ control plane.
 * These nodes are world rw (0666) on this _nosec board, so no root/IPC needed.
 */
public final class Sysfs {
    private static final String TAG = "PhoenixCam";
    private Sysfs() {}

    /** Reads the first integer token from a sysfs node, or {@code def} on any failure. */
    public static int readInt(String path, int def) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            if (line == null) return def;
            line = line.trim();
            if (line.isEmpty()) return def;
            // nodes sometimes report "1" or "val=1"; grab the trailing int
            int i = line.length() - 1;
            while (i >= 0 && Character.isDigit(line.charAt(i))) i--;
            return Integer.parseInt(line.substring(i + 1));
        } catch (Exception e) {
            return def;
        }
    }

    /** Writes an int to a sysfs node. Returns true on success. */
    public static boolean writeInt(String path, int val) {
        try (FileWriter w = new FileWriter(path)) {
            w.write(Integer.toString(val));
            w.flush();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "sysfs write failed " + path + " = " + val + ": " + e.getMessage());
            return false;
        }
    }

    public static boolean isReverseEngaged() {
        return readInt(HwConfig.NODE_REVERSE, 0) == 1;
    }
}
