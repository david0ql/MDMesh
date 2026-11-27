package com.dallycontrol.kiosk

import java.security.MessageDigest

/** Where a [PinGate] keeps its counters; they must survive a restart (else a reboot would reset the wait). */
interface PinAttemptStore {
    var failures: Int

    /** Wall-clock millis until which no attempt is accepted (0 = not locked). */
    var lockedUntil: Long
}

/** In-memory [PinAttemptStore] (tests). */
class InMemoryPinAttemptStore(override var failures: Int = 0, override var lockedUntil: Long = 0L) : PinAttemptStore

sealed interface PinResult {
    data object Ok : PinResult

    /** Wrong secret. [failures] in a row so far; [lockedMs] > 0 when this failure started a wait. */
    data class Wrong(val failures: Int, val lockedMs: Long) : PinResult

    /** Still waiting after earlier failures: the typed value was not even compared. */
    data class Locked(val remainingMs: Long) : PinResult

    /** There is no secret to compare against: nothing typed on the device may pass. */
    data object NoSecret : PinResult
}

/**
 * Checks a typed secret (the kiosk exit password, the anti-theft PIN) safely:
 *  - never passes without a secret configured;
 *  - compares in constant time (digests, so neither length nor content leaks through timing);
 *  - two free mistakes, then a growing wait after each further failure: 30 s, 1 min, 2 min … up to 30 min.
 *
 * The wait is kept in wall-clock time and persisted, so restarting the phone does not clear it; a clock set back
 * cannot stretch it beyond [MAX_LOCK_MS].
 */
class PinGate(private val store: PinAttemptStore, private val now: () -> Long = System::currentTimeMillis) {

    fun remainingLockMs(): Long {
        val until = store.lockedUntil
        if (until <= 0L) return 0L
        val left = until - now()
        if (left > MAX_LOCK_MS) { // the clock went backwards: never wait longer than the longest wait
            store.lockedUntil = now() + MAX_LOCK_MS
            return MAX_LOCK_MS
        }
        return if (left > 0L) left else 0L
    }

    fun verify(expected: String?, typed: String): PinResult {
        if (expected.isNullOrBlank()) return PinResult.NoSecret
        val left = remainingLockMs()
        if (left > 0L) return PinResult.Locked(left)
        if (matches(expected, typed)) {
            store.failures = 0
            store.lockedUntil = 0L
            return PinResult.Ok
        }
        val failures = store.failures + 1
        store.failures = failures
        val lock = lockMsAfter(failures)
        store.lockedUntil = if (lock > 0L) now() + lock else 0L
        return PinResult.Wrong(failures, lock)
    }

    companion object {
        const val FREE_ATTEMPTS = 3
        const val BASE_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 30 * 60_000L
        private const val MAX_DOUBLINGS = 10

        /** The wait a run of [failures] wrong attempts earns: none for the first two, then 30 s doubling. */
        fun lockMsAfter(failures: Int): Long {
            if (failures < FREE_ATTEMPTS) return 0L
            val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(MAX_DOUBLINGS)
            return (BASE_LOCK_MS shl doublings).coerceAtMost(MAX_LOCK_MS)
        }

        /** Constant-time equality of two secrets (compared as SHA-256 digests). */
        fun matches(expected: String, typed: String): Boolean {
            val a = MessageDigest.getInstance("SHA-256").digest(expected.toByteArray(Charsets.UTF_8))
            val b = MessageDigest.getInstance("SHA-256").digest(typed.toByteArray(Charsets.UTF_8))
            return MessageDigest.isEqual(a, b)
        }
    }
}
