package com.hmdm.util;

import java.util.Locale;

/**
 * Automatic device names (a device still without one gets it from its first report, never overwritten):
 * {@code serial} (the default), {@code imei}, {@code model-serial} ("moto g31 · ZY22H…"), or {@code none}.
 * Falls back along serial → IMEI → nothing when the preferred identifier is not available.
 */
public final class DeviceNaming {
    public static final String SERIAL = "serial";
    public static final String IMEI = "imei";
    public static final String MODEL_SERIAL = "model-serial";
    public static final String NONE = "none";

    private DeviceNaming() {}

    public static String normalizeRule(String rule) {
        if (rule == null) return null;
        String r = rule.trim().toLowerCase(Locale.ROOT);
        return SERIAL.equals(r) || IMEI.equals(r) || MODEL_SERIAL.equals(r) || NONE.equals(r) ? r : null;
    }

    /** @return the name, or null when the rule is "none" or nothing usable was reported. */
    public static String name(String rule, String serial, String imei, String model) {
        String r = normalizeRule(rule);
        if (r == null) r = SERIAL;
        String s = clean(serial);
        String i = clean(imei);
        String m = clean(model);
        switch (r) {
            case NONE:
                return null;
            case IMEI:
                return i != null ? i : s;
            case MODEL_SERIAL: {
                String id = s != null ? s : i;
                if (id == null) return null;
                return m != null ? m + " · " + id : id;
            }
            default:
                return s != null ? s : i;
        }
    }

    private static String clean(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty() || "unknown".equalsIgnoreCase(t) || t.length() > 100) return null;
        return t;
    }
}
