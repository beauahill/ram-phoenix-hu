package com.ramphoenix.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicit, bounded read-only requests for identifying OEM data without a second SPP client. */
final class ObdProbe {
    static final class Request {
        final String route, hex;
        Request(String route, String hex) { this.route = route; this.hex = hex; }
    }

    /** Example: 7E0:21D2,212D,220168;740/4C0:22A0A5. Validate everything before sending. */
    static List<Request> parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) return List.of();
        List<Request> result = new ArrayList<>();
        for (String group : spec.toUpperCase(Locale.US).split(";", -1)) {
            String[] parts = group.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException("route:request required");
            String route = ObdRoute.parse(parts[0]).spec;
            for (String value : parts[1].split(",", -1)) {
                String hex = value.trim();
                if (!hex.matches("(?:01[0-9A-F]{2}|21[0-9A-F]{2}|22[0-9A-F]{4})"))
                    throw new IllegalArgumentException("only explicit 01/21/22 reads are allowed");
                if (result.size() >= 12) throw new IllegalArgumentException("at most 12 requests");
                result.add(new Request(route, hex));
            }
        }
        return List.copyOf(result);
    }
}
