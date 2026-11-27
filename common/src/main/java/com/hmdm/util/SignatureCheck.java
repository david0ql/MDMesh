package com.hmdm.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compares who signed a new version of an app with who signed the version already in the library. Android only
 * updates an app with an APK signed by the same certificate: with another one the install fails on every phone
 * that has the app (INSTALL_FAILED_UPDATE_INCOMPATIBLE), so the console must warn before the version goes out.
 */
public final class SignatureCheck {

    public enum Verdict {
        /** First version of the package here: nothing to compare with. */
        FIRST,
        /** Same certificate. */
        SAME,
        /** Another certificate: the update will fail on phones that have the previous version. */
        CHANGED,
        /** One of the two signatures could not be read (file gone, external URL, unsigned). */
        UNKNOWN
    }

    public static final class Result {
        public final Verdict verdict;
        public final String newSha256;
        public final String previousSha256;
        /** The version the previous signature was read from (its name), or null. */
        public final String previousVersion;

        Result(Verdict verdict, String newSha256, String previousSha256, String previousVersion) {
            this.verdict = verdict;
            this.newSha256 = newSha256;
            this.previousSha256 = previousSha256;
            this.previousVersion = previousVersion;
        }

        public boolean changed() {
            return verdict == Verdict.CHANGED;
        }

        /** What goes to the console. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", verdict.name().toLowerCase());
            m.put("changed", changed());
            m.put("newSha256", newSha256);
            m.put("previousSha256", previousSha256);
            m.put("previousVersion", previousVersion);
            if (changed()) m.put("message", message());
            return m;
        }

        public String message() {
            return "Este APK está firmado con un certificado distinto al de la versión "
                    + (previousVersion == null ? "anterior" : previousVersion)
                    + " (anterior " + shortSha(previousSha256) + ", nuevo " + shortSha(newSha256) + "). "
                    + "Los equipos que ya tienen la app rechazarán la actualización (INSTALL_FAILED_UPDATE_INCOMPATIBLE) "
                    + "salvo que se desinstale primero, lo que borra sus datos.";
        }
    }

    private SignatureCheck() {}

    /**
     * @param hasPrevious       whether the library already has a version of the package
     * @param previousSha256    its signer (null when unreadable)
     * @param newSha256         the new file's signer (null when unreadable)
     */
    public static Result compare(boolean hasPrevious, String previousSha256, String previousVersion, String newSha256) {
        String n = norm(newSha256);
        String p = norm(previousSha256);
        if (!hasPrevious) return new Result(Verdict.FIRST, n, null, null);
        if (n == null || p == null) return new Result(Verdict.UNKNOWN, n, p, previousVersion);
        return new Result(n.equals(p) ? Verdict.SAME : Verdict.CHANGED, n, p, previousVersion);
    }

    private static String norm(String sha) {
        return sha == null || sha.trim().isEmpty() ? null : sha.trim().toLowerCase();
    }

    static String shortSha(String sha) {
        if (sha == null) return "?";
        return sha.length() <= 12 ? sha : sha.substring(0, 12) + "…";
    }
}
