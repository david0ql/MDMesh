package com.hmdm.util;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Reusable enrollment codes: 8 characters from an alphabet without look-alikes (no 0/O, 1/I/L), shown as
 * {@code ABCD-EFGH} and accepted however they are typed (case, dashes and spaces ignored). 31^8 ≈ 8.5·10^11 codes.
 */
public final class EnrollmentCodes {
    public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EnrollmentCodes() {}

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** The stored form of what someone typed, or null when it cannot be a code. */
    public static String normalize(String typed) {
        if (typed == null) return null;
        String t = typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (t.length() != LENGTH) return null;
        for (int i = 0; i < t.length(); i++) if (ALPHABET.indexOf(t.charAt(i)) < 0) return null;
        return t;
    }

    public static String display(String code) {
        return code == null || code.length() != LENGTH ? code : code.substring(0, 4) + "-" + code.substring(4);
    }
}
