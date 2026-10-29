// Migrated from A:\Downloads\tmpscripts\main\gen\PeerIdRules.java -- suite-local helper for IdCheckTest, see tools/selftest/suites/README.md
package com.rustdesk.java.selftest;

public final class PeerIdRules {
    static boolean isValidPeerId(String raw) {
        if (raw == null) return false;
        String id = raw.trim();
        if (id.isEmpty()) return false;
        if (isAllDigits(id)) {
            return id.length() == 9 || id.length() == 10;
        }
        return isIpDestination(id);
    }

    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean isIpDestination(String s) {
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close <= 1) return false;
            if (!isIpv6Literal(s.substring(1, close))) return false;
            return isOptionalPort(s.substring(close + 1));
        }
        int colon = s.indexOf(':');
        if (colon >= 0 && s.indexOf(':', colon + 1) >= 0) return false;
        String host = colon < 0 ? s : s.substring(0, colon);
        if (!isIpv4(host)) return false;
        return colon < 0 || isPort(s.substring(colon));
    }

    /** No port given, or a valid {@code :port}. */
    private static boolean isOptionalPort(String s) {
        return s.isEmpty() || isPort(s);
    }

    private static boolean isPort(String s) {
        if (s.isEmpty() || s.charAt(0) != ':') return false;
        String digits = s.substring(1);
        if (digits.isEmpty() || digits.length() > 5 || !isAllDigits(digits)) return false;
        int port = Integer.parseInt(digits);
        return port >= 1 && port <= 65535;
    }

    private static boolean isIpv4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !isAllDigits(part)) return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /**
     * Structural IPv6 check: eight groups, or fewer with exactly one "::", hex digits only, and at
     * most one trailing dotted-quad. Done by hand rather than through {@link java.net.InetAddress},
     * which can do DNS work on the UI thread.
     */
    private static boolean isIpv6Literal(String s) {
        if (s.isEmpty() || s.indexOf(':') < 0) return false;
        int marker = s.indexOf("::");
        if (marker >= 0 && s.indexOf("::", marker + 1) >= 0) return false;
        int groups = 0;
        if (marker >= 0) {
            if (marker > 0) {
                int n = countIpv6Groups(s.substring(0, marker), false);
                if (n < 0) return false;
                groups += n;
            }
            String tail = s.substring(marker + 2);
            if (!tail.isEmpty()) {
                int n = countIpv6Groups(tail, true);
                if (n < 0) return false;
                groups += n;
            }
            // "::" has to stand for at least one 16-bit group.
            return groups <= 7;
        }
        return countIpv6Groups(s, true) == 8;
    }

    /** 16-bit groups in one colon separated run, or -1 when the run is malformed. */
    private static int countIpv6Groups(String s, boolean allowIpv4Tail) {
        if (s.isEmpty()) return -1;
        String[] parts = s.split(":", -1);
        int groups = 0;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) return -1;
            boolean last = i == parts.length - 1;
            if (allowIpv4Tail && last && part.indexOf('.') >= 0) {
                if (!isIpv4(part)) return -1;
                groups += 2;
                continue;
            }
            if (part.length() > 4) return -1;
            for (int k = 0; k < part.length(); k++) {
                char c = Character.toLowerCase(part.charAt(k));
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                if (!hex) return -1;
            }
            groups++;
        }
        return groups;
    }
}

