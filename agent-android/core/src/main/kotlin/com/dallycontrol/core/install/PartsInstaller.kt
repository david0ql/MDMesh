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
    /** True when the package is already at the wanted version (asked after a timeout: the answer may just have been lost). */
    private val alreadyInstalled: () -> Boolean = { false },
    private val timeoutMs: (totalBytes: Long) -> Long = ::installTimeoutMs,
) {
    /** A fetched part plus whether we downloaded it (and therefore own its cleanup). */
    private data class Fetched(val file: File, val downloaded: Boolean)

    /**
     * @throws RetryLaterException when a part could not be fetched for a reason that is not this install's fault (no
     * internet, DNS, a server error): no result is reported and the server hands the install out again.
     */
    suspend fun install(parts: List<ApkPart>): InstallOutcome {
        val fetched = ArrayList<Fetched>(parts.size)
        try {
            for (part in parts) {
                val file = try {
                    obtain(part)
                } catch (e: CancellationException) {
                    throw e // a cancelled check-in is not a failed install
                } catch (e: Throwable) {
                    if (isTransient(e)) throw RetryLaterException("apk fetch: ${e.message}")
                    return InstallOutcome.Failure(null, "apk fetch failed: ${e.message}")
                }
                fetched += Fetched(file, downloaded = part.url != null && part.localPath == null)
                part.sha256?.takeIf { it.isNotBlank() }?.let { expected ->
                    val actual = sha256Of(file)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        return InstallOutcome.Failure(null, "checksum mismatch: expected $expected got $actual")
                    }
                }
            }
            val files = fetched.map { it.file }
            val sessionId = try {
                commit(files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return InstallOutcome.Failure(null, "install session error: ${e.message}")
            }
            val limit = timeoutMs(files.sumOf { it.length() })
            val event = resultBus.awaitOrNull(sessionId, limit)
            if (event == null) {
                runCatching { onTimeout(sessionId) }
                // Android may have finished and only its answer got lost (the process was frozen, the broadcast dropped).
                if (runCatching(alreadyInstalled).getOrDefault(false)) return InstallOutcome.Success
                return InstallOutcome.Failure(null, timeoutReason(limit))
            }
            return mapResult(event)
        } finally {
            // Only clean up files we downloaded; never delete a caller-provided APK.
            fetched.forEach { if (it.downloaded) it.file.delete() }
        }
    }

    companion object {
        /** Marker the server reads to explain the failure and to retry it later. */
        const val TIMEOUT_MARKER = "INSTALL_TIMEOUT"

        private const val BASE_TIMEOUT_MS = 10 * 60_000L
        private const val EXTRA_MS_PER_50MB = 60_000L
        private const val MAX_TIMEOUT_MS = 45 * 60_000L

        /** How long Android gets to answer: 10 minutes, plus one more per 50 MB to verify and optimise, at most 45. */
        fun installTimeoutMs(totalBytes: Long): Long {
            val extra = (totalBytes.coerceAtLeast(0) / (50L * 1024 * 1024)) * EXTRA_MS_PER_50MB
            return (BASE_TIMEOUT_MS + extra).coerceAtMost(MAX_TIMEOUT_MS)
        }

        fun timeoutReason(limitMs: Long): String =
            "$TIMEOUT_MARKER: Android no respondió a la instalación en ${limitMs / 60_000} min; se reintentará más tarde"

        /** A network failure (no connection, DNS, timeout, TLS) or a server error (5xx) — worth trying again later. */
        fun isTransient(t: Throwable): Boolean =
            t is IOException || (t is IllegalStateException && t.message?.startsWith("HTTP 5") == true)

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(64 * 1024)
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
