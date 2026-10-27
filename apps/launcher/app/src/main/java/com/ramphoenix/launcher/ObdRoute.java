package com.ramphoenix.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** ELM adapter setup for standard OBD routes and asymmetric OEM CAN reply IDs. */
final class ObdRoute {
    final String spec, tx, rx;
    final boolean extended, explicitReply;

    private ObdRoute(String spec, String tx, String rx, boolean explicitReply) {
        this.spec = spec; this.tx = tx; this.rx = rx; this.explicitReply = explicitReply;
        extended = tx.length() == 8;
    }

    /** Examples: 7E0, 7DF, 18DA40F1, or 740/4C0 (TX/RX with custom flow control). */
    static ObdRoute parse(String value) {
        String spec = value.trim().toUpperCase(Locale.US);
        String[] ids = spec.split("/", -1);
        if (ids.length < 1 || ids.length > 2) throw new IllegalArgumentException("Invalid OBD route");
        String tx = id(ids[0]);
        String rx;
        if (ids.length == 2) {
            rx = id(ids[1]);
            if (rx.length() != tx.length()) throw new IllegalArgumentException("CAN ID widths differ");
        } else if ("7DF".equals(tx) || tx.startsWith("18DB")) {
            rx = null; // Functional requests may be answered by several different modules.
        } else if (tx.length() == 8) {
            rx = tx.substring(0, 4) + tx.substring(6, 8) + tx.substring(4, 6);
        } else {
            rx = id(String.format(Locale.US, "%03X", Integer.parseInt(tx, 16) + 8));
        }
        return new ObdRoute(spec, tx, rx, ids.length == 2);
    }

    private static String id(String value) {
        if (!(value.matches("[0-9A-F]{3}") || value.matches("[0-9A-F]{8}")))
            throw new IllegalArgumentException("CAN ID must have 3 or 8 hex digits");
        long number = Long.parseLong(value, 16);
        if (number > (value.length() == 3 ? 0x7FFL : 0x1FFFFFFFL))
            throw new IllegalArgumentException("CAN ID out of range");
        return value;
    }

    List<String> commandsAfter(String previousSpec) {
        List<String> commands = new ArrayList<>();
        if (spec.equalsIgnoreCase(previousSpec)) return commands;
        ObdRoute previous = previousSpec.isEmpty() ? null : parse(previousSpec);
        // An OEM custom flow-control header must never leak into normal ECM reads.
        commands.add("ATFCSM0");
        if (previous == null || extended != previous.extended) commands.add(extended ? "ATSP7" : "ATSP6");
        if (extended) {
            commands.add("ATCP" + tx.substring(0, 2));
            commands.add("ATSH" + tx.substring(2));
        } else commands.add("ATSH" + tx);
        commands.add("ATCRA" + (rx == null ? "" : rx));
        if (explicitReply) {
            // ELM327/STN flow-control mode 1 requires header and data before mode.
            commands.add("ATFCSH" + tx);
            commands.add("ATFCSD300000");
            commands.add("ATFCSM1");
        }
        return commands;
    }
}
