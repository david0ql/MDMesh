package com.dallycontrol.core.location

/**
 * The decisions behind an active location fix, kept free of Android types so they can be unit tested:
 * which provider to ask, how long to wait, and when a recent fix makes waiting pointless.
 */
object FixPolicy {
    /** Android's fused provider (LocationManager.FUSED_PROVIDER, public since API 31; present on GMS devices). */
    const val FUSED = "fused"
    const val GPS = "gps"
    const val NETWORK = "network"

    const val DEFAULT_TIMEOUT_SEC = 25
    const val MIN_TIMEOUT_SEC = 5
    const val MAX_TIMEOUT_SEC = 60

    /** After a wait that produced nothing (indoors, no sky), the next waits are short until this much time passed. */
    const val BACKOFF_MS = 2 * 60_000L
    const val SHORT_TIMEOUT_SEC = 5

    /** A fix this recent is returned as is instead of asking for another one. */
    const val FRESH_ENOUGH_MS = 30_000L

    fun clampTimeout(seconds: Int?): Int =
        (seconds ?: DEFAULT_TIMEOUT_SEC).coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)

    /**
     * Providers to ask, best first: fused (Google Play services blends GPS, Wi-Fi and cell) when the device has it
     * and it is enabled, then GPS, then network. A device without GMS simply has no fused provider and uses GPS.
     */
    fun providers(available: Collection<String>, enabled: (String) -> Boolean): List<String> =
        listOf(FUSED, GPS, NETWORK).filter { it in available && runCatching { enabled(it) }.getOrDefault(false) }

    /** How long to wait for this request: the configured time, or a short one right after a wait that failed. */
    fun timeoutSec(configured: Int, now: Long, lastTimeoutAt: Long): Int =
        if (lastTimeoutAt > 0 && now - lastTimeoutAt in 0 until BACKOFF_MS) {
            minOf(configured, SHORT_TIMEOUT_SEC)
        } else {
            configured
        }

    fun freshEnough(fixTime: Long?, now: Long): Boolean =
        fixTime != null && now - fixTime in 0 until FRESH_ENOUGH_MS
}
