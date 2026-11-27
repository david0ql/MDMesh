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

    public static boolean due(int consecutiveFailures, long lastFailureAt, boolean retryable, long now) {
        Long at = nextRetryAt(consecutiveFailures, lastFailureAt, retryable);
        return at != null && now >= at;
    }
}
