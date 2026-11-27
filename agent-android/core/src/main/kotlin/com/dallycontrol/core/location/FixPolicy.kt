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

    /** The fused provider answers within a few seconds or not at all: it never gets more than this. */
    const val FUSED_MAX_WAIT_MS = 8_000L

    /**
     * How long one provider may take out of what is left of the wait. The last one gets everything; fused gets a
     * third (at most [FUSED_MAX_WAIT_MS]) so a silent fused provider cannot use up the time GPS needs; GPS leaves a
     * third for the network fallback.
     */
    fun waitMs(provider: String, isLast: Boolean, leftMs: Long): Long = when {
        isLast -> leftMs
        provider == FUSED -> minOf(leftMs / FUSED_SHARE, FUSED_MAX_WAIT_MS)
        else -> leftMs * 2 / FUSED_SHARE
    }

    private const val FUSED_SHARE = 3

    /** One provider asked during a fresh-fix request: how long it was given, how long it took, whether it answered. */
    data class Attempt(val provider: String, val allowedMs: Long, val tookMs: Long, val answered: Boolean)

    /** The last fresh-fix request, for diagnosis: what the device offers, what was tried and what answered. */
    data class Trace(
        val at: Long,
        val available: List<String>,
        val budgetSec: Int,
        val attempts: List<Attempt>,
    ) {
        val answeredBy: String? get() = attempts.firstOrNull { it.answered }?.provider
        val totalMs: Long get() = attempts.sumOf { it.tookMs }

        /** e.g. "fused 812 ms: sin respuesta; gps 2301 ms: respondió". */
        fun summary(): String = if (attempts.isEmpty()) {
            "sin proveedores activos"
        } else {
            attempts.joinToString("; ") {
                "${it.provider} ${it.tookMs} ms: ${if (it.answered) "respondió" else "sin respuesta"}"
            }
        }
    }

    /** Set by LocationCollector after every fresh-fix request; read by the diagnosis snapshot. */
    @Volatile
    var lastTrace: Trace? = null
}
