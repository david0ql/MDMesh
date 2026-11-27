package com.dallycontrol.core.transport

/**
 * Decides, once a minute, whether the connection needs a push. Two rungs:
 *
 *  - [Action.CHECK_IN] after [HEARTBEAT_MS] without a successful check-in: the console counts a device as
 *    connected by its last report, and the alarm/WorkManager floors (5–15 min) leave no margin against its
 *    20-minute window when one of them is deferred.
 *  - [Action.RESTART] after [STALE_MS]: check-ins are being tried and keep failing although there is network —
 *    something held open is dead (a pooled connection, the socket). Drop it all and connect afresh, at most once
 *    every [RESTART_EVERY_MS].
 *
 * Without network there is nothing to push: the network callback reconnects when it returns.
 */
class ConnectionWatchdog(
    private val heartbeatMs: Long = HEARTBEAT_MS,
    private val staleMs: Long = STALE_MS,
    private val restartEveryMs: Long = RESTART_EVERY_MS,
) {
    enum class Action { NONE, CHECK_IN, RESTART }

    private var lastRestartAt = 0L
    private var startedAt = 0L

    @Synchronized
    fun decide(now: Long, lastSuccessAt: Long, networkAvailable: Boolean): Action {
        if (startedAt == 0L) startedAt = now
        if (!networkAvailable) return Action.NONE
        // Never succeeded (not enrolled yet, or a fresh install): measure from when we started watching.
        val since = now - if (lastSuccessAt == 0L) startedAt else lastSuccessAt
        return when {
            since >= staleMs && now - lastRestartAt >= restartEveryMs -> {
                lastRestartAt = now
                Action.RESTART
            }
            since >= heartbeatMs -> Action.CHECK_IN
            else -> Action.NONE
        }
    }

    companion object {
        const val TICK_MS = 60_000L
        const val HEARTBEAT_MS = 5L * 60_000L
        const val STALE_MS = 8L * 60_000L
        const val RESTART_EVERY_MS = 3L * 60_000L
    }
}

/**
 * Tells a real change of the default network (Wi-Fi to mobile data, or back from none) from the callback's first,
 * informative call — only a change means the open socket rides a dead path and must be reopened.
 */
class NetworkChangeTracker {
    private var current: Long? = null
    private var seenAny = false

    /** @return true when connections must be reopened. */
    @Synchronized
    fun onAvailable(networkId: Long): Boolean {
        val changed = seenAny && current != networkId
        current = networkId
        seenAny = true
        return changed
    }

    /** @return true when no network is left (a hand-over to another network already announced is not a loss). */
    @Synchronized
    fun onLost(networkId: Long): Boolean {
        if (current != networkId) return false
        current = null
        return true
    }
}
