package com.dallycontrol.core.install

import android.content.pm.PackageInstaller
import com.dallycontrol.core.command.RetryLaterException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * The part of an install that does not need Android: get every APK part, check each one against its SHA-256, hand
 * them all to the platform in one go, wait for its answer — but not forever — and clean up. [InstallManager] gives it
 * the two things only the phone can do ([obtain] a part, [commit] the parts into a PackageInstaller session), which
 * keeps the rules here testable on a plain JVM.
 */
internal class PartsInstaller(
    /** Downloads a part (or returns the local file it names). */
    private val obtain: suspend (ApkPart) -> File,
    /** Writes the files into one install session and commits it; returns the session id whose result to wait for. */
    private val commit: suspend (List<File>) -> Int,
    private val resultBus: InstallResultBus,
    /** Called with the session id when Android did not answer in time (abandon the session). */
    private val onTimeout: (Int) -> Unit = {},
    /** True when the package is already at the wanted version (asked after a timeout: the answer may be lost). */
    private val alreadyInstalled: () -> Boolean = { false },
    private val timeoutMs: (totalBytes: Long) -> Long = ::installTimeoutMs,
) {
    /** A fetched part plus whether we downloaded it (and therefore own its cleanup). */
    private data class Fetched(val file: File, val downloaded: Boolean)

    /** A step that failed for good; [reason] is the failure to report. */
    private class StepFailed(val reason: String, cause: Throwable? = null) : Exception(reason, cause)

    /**
     * @throws RetryLaterException when a part could not be fetched for a reason that is not this install's fault (no
     * internet, DNS, a server error): no result is reported and the server hands the install out again.
     */
    suspend fun install(parts: List<ApkPart>): InstallOutcome {
        val fetched = ArrayList<Fetched>(parts.size)
        return try {
            for (part in parts) {
                val file = fetch(part)
                fetched += Fetched(file, downloaded = part.url != null && part.localPath == null)
                verifyChecksum(part, file)
            }
            val files = fetched.map { it.file }
            awaitResult(commitSession(files), files)
        } catch (e: StepFailed) {
            InstallOutcome.Failure(null, e.reason)
        } finally {
            // Only clean up files we downloaded; never delete a caller-provided APK.
            fetched.forEach { if (it.downloaded) it.file.delete() }
        }
    }

    // Anything can go wrong while downloading (a debug build once ran out of memory on a big APK): whatever it is,
    // it becomes a result the console can show instead of a crashed check-in. (RetryLaterException takes only a
    // message, which carries the cause's.)
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun fetch(part: ApkPart): File = try {
        obtain(part)
    } catch (e: CancellationException) {
        throw e // a cancelled check-in is not a failed install
    } catch (e: Throwable) {
        if (isTransient(e)) throw RetryLaterException("apk fetch: ${e.message}")
        throw StepFailed("apk fetch failed: ${e.message}", e)
    }

    private fun verifyChecksum(part: ApkPart, file: File) {
        val expected = part.sha256?.takeIf { it.isNotBlank() } ?: return
        val actual = sha256Of(file)
        if (!actual.equals(expected, ignoreCase = true)) {
            throw StepFailed("checksum mismatch: expected $expected got $actual")
        }
    }

    // The platform throws several unrelated types here (IOException, SecurityException, IllegalStateException…).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun commitSession(files: List<File>): Int = try {
        commit(files)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw StepFailed("install session error: ${e.message}", e)
    }

    private suspend fun awaitResult(sessionId: Int, files: List<File>): InstallOutcome {
        val limit = timeoutMs(files.sumOf { it.length() })
        val event = resultBus.awaitOrNull(sessionId, limit)
        return if (event != null) mapResult(event) else timedOut(sessionId, limit)
    }

    private fun timedOut(sessionId: Int, limit: Long): InstallOutcome {
        runCatching { onTimeout(sessionId) }
        // Android may have finished and only its answer got lost (the process was frozen, the broadcast dropped).
        val installed = runCatching(alreadyInstalled).getOrDefault(false)
        return if (installed) InstallOutcome.Success else InstallOutcome.Failure(null, timeoutReason(limit))
    }

    companion object {
        /** Marker the server reads to explain the failure and to retry it later. */
        const val TIMEOUT_MARKER = "INSTALL_TIMEOUT"

        private const val MINUTE_MS = 60_000L
        private const val BASE_TIMEOUT_MS = 10 * MINUTE_MS
        private const val EXTRA_MS_PER_STEP = MINUTE_MS
        private const val MAX_TIMEOUT_MS = 45 * MINUTE_MS
        private const val SIZE_STEP_BYTES = 50L * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024
        private const val CAUSE_DEPTH = 4

        /** How long Android gets to answer: 10 minutes, plus one more per 50 MB to verify and optimise, at most 45. */
        fun installTimeoutMs(totalBytes: Long): Long {
            val extra = (totalBytes.coerceAtLeast(0) / SIZE_STEP_BYTES) * EXTRA_MS_PER_STEP
            return (BASE_TIMEOUT_MS + extra).coerceAtMost(MAX_TIMEOUT_MS)
        }

        fun timeoutReason(limitMs: Long): String {
            val minutes = limitMs / MINUTE_MS
            return "$TIMEOUT_MARKER: Android no respondió a la instalación en $minutes min; se reintentará más tarde"
        }

        /** A network failure (no connection, DNS, timeout, TLS) or a server error (5xx) — worth trying again later. */
        fun isTransient(t: Throwable): Boolean {
            val serverError = t is IllegalStateException && t.message?.startsWith("HTTP 5") == true
            return !isOutOfSpace(t) && (t is IOException || serverError)
        }

        /**
         * The phone ran out of room while saving the download. Trying again later by itself would fail the same way —
         * silently, for ever — so it is reported as a failure the console can show ("sin espacio").
         */
        fun isOutOfSpace(t: Throwable): Boolean {
            var e: Throwable? = t
            var found = false
            repeat(CAUSE_DEPTH) {
                val m = e?.message.orEmpty()
                found = found || m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)
                e = e?.cause
            }
            return found
        }

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun mapResult(event: InstallResultBus.InstallResultEvent): InstallOutcome =
            when (event.status) {
                PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Success
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    // A true Device Owner should never get this; surface it clearly rather
                    // than launch the confirm dialog (which the explicit/immutable PendingIntent
                    // and Intent-Redirection mitigation deliberately avoid).
                    InstallOutcome.Failure(
                        event.status,
                        "unexpected STATUS_PENDING_USER_ACTION — silent install requires Device Owner / privilege",
                    )
                else -> InstallOutcome.Failure(event.status, withStatusName(event.status, event.message))
            }

        /**
         * The platform's message, always carrying the status name: some systems answer a bare sentence ("Not enough
         * space") with no INSTALL_FAILED_* code, and the console explains failures by those names.
         */
        fun withStatusName(status: Int, message: String?): String {
            val name = statusName(status)
            return when {
                message.isNullOrBlank() -> name
                message.contains("INSTALL_") || message.contains("FAILURE_") -> message
                else -> "$name: $message"
            }
        }

        fun statusName(status: Int): String = when (status) {
            PackageInstaller.STATUS_FAILURE -> "FAILURE_UNKNOWN"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "FAILURE_BLOCKED"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "FAILURE_ABORTED"
            PackageInstaller.STATUS_FAILURE_INVALID -> "FAILURE_INVALID"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "FAILURE_CONFLICT"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "FAILURE_STORAGE"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FAILURE_INCOMPATIBLE"
            else -> "STATUS_$status"
        }
    }
}
