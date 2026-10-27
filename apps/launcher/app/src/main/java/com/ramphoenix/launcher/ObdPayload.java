package com.ramphoenix.launcher;

import java.util.Locale;

/** Decodes ELM CAF1/H0 replies, including length-prefixed numbered lines. */
final class ObdPayload {
    private ObdPayload() {}

    static byte[] data(String response, String positivePrefix) {
        if (response == null) return null;
        String text = response.replaceAll("\\s", "").toUpperCase(Locale.US);
        if (text.contains(":")) {
            if (!text.matches("[0-9A-F]{3}0:.*")) return null;
            int bytes = Integer.parseInt(text.substring(0, 3), 16);
            String[] lines = text.substring(3).split("(?=[0-9A-F]:)");
            StringBuilder payload = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (!line.matches("[0-9A-F]:[0-9A-F]+")
                        || Character.digit(line.charAt(0), 16) != (i & 15)
                        || ((line.length() - 2) & 1) != 0) return null;
                payload.append(line.substring(2));
            }
            if (payload.length() < bytes * 2) return null;
            text = payload.substring(0, bytes * 2); // Discard final CAN-frame filler bytes.
        }
        if (!text.matches("[0-9A-F]+") || (text.length() & 1) != 0
                || !text.startsWith(positivePrefix)) return null;
        text = text.substring(positivePrefix.length());
        byte[] data = new byte[text.length() / 2];
        for (int i = 0; i < data.length; i++) data[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return data;
    }
}
