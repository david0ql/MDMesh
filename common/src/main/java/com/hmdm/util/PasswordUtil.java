package com.hmdm.util;

import java.security.SecureRandom;
import java.util.Random;

public class PasswordUtil {
    public static final int PASS_STRENGTH_NONE = 0;
    public static final int PASS_STRENGTH_ALPHADIGIT = 1;
    public static final int PASS_STRENGTH_SPECIAL = 2;

    // SecureRandom: this generator feeds passwords, authTokens (session/JWT binding) and password-reset tokens.
    private static final Random random = new SecureRandom();

    private static final String PASS_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_-.,!#$%()=+;*/";
    private static int DIGIT_START = 0;
    private static int DIGIT_END = 9;
    private static int ALPHA_LOWER_START = 10;
    private static int ALPHA_LOWER_END = 35;
    private static int ALPHA_CAPS_START = 36;
    private static int ALPHA_CAPS_END = 61;
    private static int ALPHA_CHAR_START = 62;
    private static int ALPHA_CHAR_END = 76;

    private static final String PASS_SALT = "5YdSYHyg2U";

    public static String getHashFromRaw(String password) {
        String md5 = CryptoUtil.getMD5String(password);
        return getHashFromMd5(md5);
    }

    // --- Password storage -------------------------------------------------------------------------------------------
    // The console sends MD5(password) (upper-case hex); that is what gets stored hashed. DallyControl stores
    // PBKDF2-HMAC-SHA256 with a random per-user salt: "pbkdf2$<iterations>$<salt b64>$<hash b64>". The inherited Headwind
    // format, SHA-1(md5 + one global salt), is still verified so existing users can log in, and is replaced by the new
    // format at their next successful login (see LocalAuth).

    private static final String PBKDF2_PREFIX = "pbkdf2$";
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int PBKDF2_SALT_BYTES = 16;
    private static final int PBKDF2_KEY_BITS = 256;

    /** Hash a password (given as the console's MD5 hex) for storage. */
    public static String getHashFromMd5(String md5) {
        byte[] salt = new byte[PBKDF2_SALT_BYTES];
        new java.security.SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(normalizeMd5(md5), salt, PBKDF2_ITERATIONS);
        java.util.Base64.Encoder b64 = java.util.Base64.getEncoder().withoutPadding();
        return PBKDF2_PREFIX + PBKDF2_ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    /** Whether the entered password (MD5 hex) matches the stored hash, in either format. Constant-time compare. */
    public static boolean passwordMatch(String enteredPass, String dbPass) {
        if (enteredPass == null || dbPass == null || dbPass.isEmpty()) {
            return false;
        }
        if (dbPass.startsWith(PBKDF2_PREFIX)) {
            String[] parts = dbPass.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = java.util.Base64.getDecoder().decode(parts[2]);
                byte[] expected = java.util.Base64.getDecoder().decode(parts[3]);
                byte[] actual = pbkdf2(normalizeMd5(enteredPass), salt, iterations);
                return java.security.MessageDigest.isEqual(expected, actual);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        String legacy = CryptoUtil.getSHA1String(enteredPass + PASS_SALT);
        return legacy != null && java.security.MessageDigest.isEqual(
                legacy.toLowerCase().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                dbPass.toLowerCase().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** True when the stored hash is the inherited weak format and should be upgraded. */
    public static boolean isLegacyHash(String dbPass) {
        return dbPass != null && !dbPass.startsWith(PBKDF2_PREFIX);
    }

    private static String normalizeMd5(String md5) {
        return md5 == null ? "" : md5.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static byte[] pbkdf2(String secret, byte[] salt, int iterations) {
        try {
            javax.crypto.spec.PBEKeySpec spec =
                    new javax.crypto.spec.PBEKeySpec(secret.toCharArray(), salt, iterations, PBKDF2_KEY_BITS);
            return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 unavailable", e);
        }
    }

    public static boolean checkPassword(String password, int length, int strength) {
        if (password.length() < length) {
            return false;
        }

        if (strength == PASS_STRENGTH_NONE) {
            return true;
        }

        boolean hasDigits = false;
        boolean hasLower = false;
        boolean hasCaps = false;
        boolean hasSpecial = false;

        for (int n = 0; n < password.length(); n++) {
            int i = PASS_CHARS.indexOf(password.charAt(n));
            if (i == -1) {
                hasSpecial = true;
            } else if (i >= DIGIT_START && i <= DIGIT_END) {
                hasDigits = true;
            } else if (i >= ALPHA_LOWER_START && i <= ALPHA_LOWER_END) {
                hasLower = true;
            } else if (i >= ALPHA_CAPS_START && i <= ALPHA_CAPS_END) {
                hasCaps = true;
            } else if (i >= ALPHA_CHAR_START && i <= ALPHA_CHAR_END) {
                hasSpecial = true;
            }
        }
        if (strength == PASS_STRENGTH_ALPHADIGIT) {
            return hasDigits && hasLower && hasCaps;
        }
        if (strength == PASS_STRENGTH_SPECIAL) {
            return hasDigits && hasLower && hasCaps && hasSpecial;
        }
        // Reserved
        return false;
    }

    public static String generatePassword(int length, int strength) {
        int realLength = length < 8 ? 8 : length;

        int charIntervalEnd = DIGIT_END;
        switch (strength) {
            case PASS_STRENGTH_NONE:
                charIntervalEnd = DIGIT_END;
                break;
            case PASS_STRENGTH_ALPHADIGIT:
                charIntervalEnd = ALPHA_CAPS_END;
                break;
            case PASS_STRENGTH_SPECIAL:
                charIntervalEnd = ALPHA_CHAR_END;
                break;
        }

        StringBuilder b = new StringBuilder();
        for (int n = 0; n < realLength - 3; n++) {
            int index = random.nextInt(charIntervalEnd + 1);
            b.append(PASS_CHARS.charAt(index));
        }

        String password = b.toString();
        if (!checkPassword(password, length, strength)) {
            // Let's update password to match rules
            int index = random.nextInt(DIGIT_END - DIGIT_START + 1);
            b.append(PASS_CHARS.charAt(index));
            index = random.nextInt(ALPHA_LOWER_END - ALPHA_LOWER_START + 1);
            b.append(PASS_CHARS.charAt(ALPHA_LOWER_START + index));
            index = random.nextInt(ALPHA_CAPS_END - ALPHA_CAPS_START + 1);
            b.append(PASS_CHARS.charAt(ALPHA_CAPS_START + index));
            if (strength == PASS_STRENGTH_SPECIAL) {
                index = random.nextInt(ALPHA_CHAR_END - ALPHA_CHAR_START + 1);
                b.append(PASS_CHARS.charAt(ALPHA_CHAR_START + index));
            }
        } else {
            for (int n = 0; n < 3; n++) {
                int index = random.nextInt(charIntervalEnd + 1);
                b.append(PASS_CHARS.charAt(index));
            }
        }
        password = b.toString();
        return password;
    }

    public static String generateToken() {
        StringBuilder b = new StringBuilder();
        for (int n = 0; n < 20; n++) {
            b.append(PASS_CHARS.charAt(random.nextInt(ALPHA_CAPS_END)));
        }
        return b.toString();
    }

}
