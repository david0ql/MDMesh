/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 */

package com.hmdm.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * <p>Helpers for the optional "account email" associated with an enrollment / device / folder. It is a
 * metadata/identification field only — it never creates any account on the phone.</p>
 */
public final class AccountEmails {

    /** Matches the DB column width: VARCHAR(255). */
    public static final int MAX_LENGTH = 255;

    /** Pragmatic address check (local@domain.tld); not a full RFC 5322 validator. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private AccountEmails() {
    }

    /**
     * Normalise a user-supplied email: trimmed and lower-cased, or null when blank. Returns null for a blank
     * input so callers clear the field; a non-blank value must still pass {@link #isValid(String)}.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String e = raw.trim().toLowerCase(Locale.ROOT);
        return e.isEmpty() ? null : e;
    }

    /** True for a well-formed, in-range address (apply to the normalised value). */
    public static boolean isValid(String normalized) {
        return normalized != null && normalized.length() <= MAX_LENGTH && EMAIL.matcher(normalized).matches();
    }
}
