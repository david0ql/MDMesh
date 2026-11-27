package com.hmdm.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * <p>A phone that was factory-reset comes back as a brand-new agent: new secret, and on older Android versions a new
 * hardware id too. Its serial number and IMEI do not change, so they are what brings it back to its own row — and
 * with it its folder, policy and history — instead of creating a second device.</p>
 *
 * <p>Taking over a row is only safe when that row is not a different, live phone that happens to report the same
 * identifier (cheap devices share serials such as {@code 0123456789ABCDEF}; emulators share both). So junk
 * identifiers never match, and a row is only taken over when it has been silent for a while or was marked as wiped.</p>
 *
 * <p>Pure: no I/O.</p>
 */
public final class ReenrollMatch {

    /** A row that reported this recently belongs to a phone that is still running: never taken over. */
    public static final long SILENCE_MS = 2 * 60_000L;

    private static final Set<String> JUNK_SERIALS = new HashSet<String>(Arrays.asList(
            "unknown", "0123456789abcdef", "0123456789", "123456789", "1234567890", "1234567890abcdef",
            "abcdef0123456789", "null", "none", "default", "android", "serial"));

    private ReenrollMatch() {
    }

    /** The serial as usable for matching, or null: blank, too short, a known placeholder, one repeated character, an emulator. */
    public static String usableSerial(String serial) {
        if (serial == null) return null;
        String s = serial.trim();
        if (s.length() < 6 || s.length() > 100) return null;
        String low = s.toLowerCase(Locale.ROOT);
        if (JUNK_SERIALS.contains(low) || low.startsWith("emulator") || low.startsWith("generic")) return null;
        if (low.chars().distinct().count() < 3) return null;
        return s;
    }

    /** The IMEI as usable for matching, or null: not 14-16 digits, all one digit, or the emulator's. */
    public static String usableImei(String imei) {
        if (imei == null) return null;
        String s = imei.trim();
        if (!s.matches("^[0-9]{14,16}$")) return null;
        if (s.chars().distinct().count() < 3) return null;
        if (s.startsWith("35824005111111") || s.startsWith("00000000")) return null; // Android emulator
        return s;
    }

    /**
     * Whether the enrolling phone may take over an existing row that carries its serial or IMEI.
     *
     * @param lastUpdate when that row last reported (null = never)
     * @param wipedAt    when it was marked as wiped (null = not marked)
     */
    public static boolean mayTakeOver(Long lastUpdate, Long wipedAt, long now) {
        if (wipedAt != null) return true;
        return lastUpdate == null || now - lastUpdate >= SILENCE_MS;
    }
}
