package com.hmdm.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who is enrolling: the identity rules the agent v1 enrollment uses to recognise a physical phone (so the same phone
 * can retry with its token, and re-enrolls onto its own row instead of a duplicate), without ever confusing two
 * phones that report a placeholder serial.
 */
public final class EnrollmentIdentity {

    private static final Set<String> PLACEHOLDER_SERIALS = new HashSet<>(Arrays.asList(
            "UNKNOWN", "0123456789ABCDEF", "0123456789", "123456789", "1234567890", "NULL", "DEFAULT", "SERIAL"));
    /** The Android emulator's fixed IMEIs (every AVD has the same). */
    private static final Set<String> EMULATOR_IMEIS = new HashSet<>(Arrays.asList(
            "358240051111110", "867400022047199", "355458061189396", "355457084990058"));
    private static final Pattern IMEI = Pattern.compile("^[0-9]{14,16}$");
    private static final Pattern APK_PATH = Pattern.compile("^/(files|update)/[A-Za-z0-9._-]+\\.apk$");
    private static final Pattern UA = Pattern.compile("Android ([0-9][0-9.]*);\\s*(.+?)\\s+Build/");

    private EnrollmentIdentity() {
    }

    /** The serial when it can identify one phone; null for blanks, placeholders and emulators. */
    public static String usableSerial(String serial) {
        if (serial == null) {
            return null;
        }
        String s = serial.trim();
        String up = s.toUpperCase(Locale.ROOT);
        if (s.length() < 6 || s.length() > 64 || PLACEHOLDER_SERIALS.contains(up) || up.startsWith("EMULATOR")
                || up.chars().distinct().count() < 3) {
            return null;
        }
        return s;
    }

    /** The IMEI when it is a plausible, device-specific one. */
    public static String usableImei(String imei) {
        if (imei == null) {
            return null;
        }
        String s = imei.trim();
        if (!IMEI.matcher(s).matches() || s.chars().distinct().count() < 3 || EMULATOR_IMEIS.contains(s)) {
            return null;
        }
        return s;
    }

    /**
     * True when the enroll request comes from the device that already claimed the token: same hardware id, or the
     * same usable serial or IMEI (below Android 12 the hardware id is ANDROID_ID, which a factory reset changes).
     */
    public static boolean sameDevice(String claimedHardwareId, String claimedSerial, String claimedImei,
                                     String hardwareId, String serial, String imei) {
        String hw = blankToNull(hardwareId);
        if (hw != null && hw.equals(blankToNull(claimedHardwareId))) {
            return true;
        }
        String s = usableSerial(serial);
        if (s != null && s.equalsIgnoreCase(usableSerial(claimedSerial))) {
            return true;
        }
        String i = usableImei(imei);
        return i != null && i.equals(usableImei(claimedImei));
    }

    /** {androidRelease, model} from the User-Agent Android sends when it downloads the agent; nulls when unknown. */
    public static String[] parseDownloadUserAgent(String userAgent) {
        String[] out = new String[2];
        if (userAgent != null) {
            Matcher m = UA.matcher(userAgent);
            if (m.find()) {
                out[0] = m.group(1);
                out[1] = cap(m.group(2), 100);
            }
        }
        return out;
    }

    /** Only an APK directly under the published folders: the tracked download never redirects anywhere else. */
    public static boolean isAgentApkPath(String path) {
        return path != null && APK_PATH.matcher(path).matches();
    }

    /** Trimmed and cut to max characters; null when blank. */
    public static String cap(String s, int max) {
        String t = blankToNull(s);
        return t == null ? null : (t.length() > max ? t.substring(0, max) : t);
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
