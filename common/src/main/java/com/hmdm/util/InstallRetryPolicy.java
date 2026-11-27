package com.hmdm.util;

/**
 * When a failed {@code app.install} is sent again by itself: after a growing wait, a limited number of times. A phone
 * with no space, or one that lost the download, usually recovers alone; one that keeps failing must not be hammered
 * forever (each try can download hundreds of MB over mobile data), so after {@link #MAX_RETRIES} automatic retries
 * it is left as failed until someone presses "Reintentar".
 */
public final class InstallRetryPolicy {

    /** Wait before automatic retry 1, 2, 3… (after the 1st, 2nd, 3rd… consecutive failure). */
    static final long[] DELAYS_MS = {
            10L * 60_000L,       // 10 min
            30L * 60_000L,       // 30 min
            2L * 3600_000L,      // 2 h
            6L * 3600_000L,      // 6 h
            24L * 3600_000L,     // 24 h
    };

    public static final int MAX_RETRIES = DELAYS_MS.length;

    private InstallRetryPolicy() {}

    /**
     * The moment the next automatic retry is due, or null when there will be none: the failure cannot fix itself
     * ([retryable] false) or the retries ran out.
     *
     * @param consecutiveFailures failed attempts in a row for this app version on this phone (1 or more)
     * @param lastFailureAt       when the last of them finished
     */
    public static Long nextRetryAt(int consecutiveFailures, long lastFailureAt, boolean retryable) {
        if (!retryable || consecutiveFailures < 1 || consecutiveFailures > MAX_RETRIES) return null;
        return lastFailureAt + DELAYS_MS[consecutiveFailures - 1];
    }

    /** What to do with a failed install whose retry is due (or that someone asked to retry). */
    public enum Decision {
        /** The phone's policy still installs the app: send what the policy wants now. */
        RESEND_POLICY,
        /** A person ordered it and nothing took it back since: send the same order again. */
        RESEND_SAME,
        /** A policy ordered it and that policy no longer assigns the app to the phone: leave it. */
        SKIP_UNASSIGNED,
        /** The app was taken off the phone on purpose after the order (policy says remove, or an uninstall was sent). */
        SKIP_REMOVED
    }

    /**
     * An install is never brought back against the current intent: a retry only happens while the app is still
     * assigned to the phone, or for an order a person gave that nobody undid.
     *
     * @param policyInstalls   the phone's policy (its app list or an app group) installs the package now
     * @param policyRemoves    the phone's policy removes the package now
     * @param orderedByPolicy  the failed order was queued by a policy, not by a person
     * @param uninstalledSince an uninstall of the package was sent to the phone after the failed order
     */
    public static Decision decide(boolean policyInstalls, boolean policyRemoves, boolean orderedByPolicy,
                                  boolean uninstalledSince) {
        if (policyRemoves) return Decision.SKIP_REMOVED;
        if (policyInstalls) return Decision.RESEND_POLICY;
        if (orderedByPolicy) return Decision.SKIP_UNASSIGNED;
        return uninstalledSince ? Decision.SKIP_REMOVED : Decision.RESEND_SAME;
    }

    public static boolean due(int consecutiveFailures, long lastFailureAt, boolean retryable, long now) {
        Long at = nextRetryAt(consecutiveFailures, lastFailureAt, retryable);
        return at != null && now >= at;
    }
}
