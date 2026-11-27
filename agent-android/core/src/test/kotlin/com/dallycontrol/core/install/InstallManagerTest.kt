package com.dallycontrol.core.install

import com.dallycontrol.core.command.RetryLaterException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The install rules [InstallManager] runs through [PartsInstaller] — the wait for Android's answer has a limit, a
 * fetch that failed for lack of network is left for later, every part is checked against its SHA-256 — on a plain
 * JVM: the two Android-only steps (download, PackageInstaller session) are the lambdas the manager passes in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstallManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private companion object {
        const val STATUS_SUCCESS = 0
        const val STATUS_FAILURE_STORAGE = 6
        const val SESSION = 77
    }

    private fun apk(name: String, content: String = name): File = tmp.newFile(name).apply { writeText(content) }

    private fun sha(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    // --- timeout ------------------------------------------------------------------------------------------------

    @Test
    fun `the bus gives up after the limit and returns null`() = runTest {
        val bus = InstallResultBus()
        assertNull(bus.awaitOrNull(sessionId = 1, timeoutMs = 5_000))
        assertEquals(5_000, currentTime)
    }

    @Test
    fun `the bus returns a result that arrives before the limit`() = runTest {
        val bus = InstallResultBus()
        val waiting = async { bus.awaitOrNull(sessionId = 2, timeoutMs = 60_000) }
        advanceTimeBy(1_000)
        bus.publish(2, STATUS_SUCCESS, null)
        assertEquals(STATUS_SUCCESS, waiting.await()?.status)
    }

    @Test
    fun `a late result of a session that timed out does not leak into the next wait`() = runTest {
        val bus = InstallResultBus()
        assertNull(bus.awaitOrNull(sessionId = 3, timeoutMs = 1_000))
        bus.publish(3, STATUS_SUCCESS, "late") // dropped
        assertNull(bus.awaitOrNull(sessionId = 3, timeoutMs = 1_000))
    }

    @Test
    fun `an install Android never answers fails with INSTALL_TIMEOUT and abandons its session`() = runTest {
        val file = apk("a.apk")
        var abandoned: Int? = null
        val outcome = PartsInstaller(
            obtain = { file },
            commit = { SESSION },
            resultBus = InstallResultBus(),
            onTimeout = { abandoned = it },
            timeoutMs = { 600_000 },
        ).install(listOf(ApkPart(localPath = file.path)))

        assertTrue(outcome is InstallOutcome.Failure)
        val reason = (outcome as InstallOutcome.Failure).reason
        assertTrue(reason, reason.startsWith(PartsInstaller.TIMEOUT_MARKER))
        assertEquals(SESSION, abandoned)
        assertEquals("waited exactly the limit, not forever", 600_000, currentTime)
    }

    @Test
    fun `a timeout is a success when the app turns out to be installed`() = runTest {
        val file = apk("a.apk")
        val outcome = PartsInstaller(
            obtain = { file },
            commit = { SESSION },
            resultBus = InstallResultBus(),
            alreadyInstalled = { true },
            timeoutMs = { 1_000 },
        ).install(listOf(ApkPart(localPath = file.path)))
        assertEquals(InstallOutcome.Success, outcome)
    }

    @Test
    fun `the limit grows with the size and has a ceiling`() {
        val mb = 1024L * 1024
        assertEquals(10 * 60_000L, PartsInstaller.installTimeoutMs(5 * mb))
        assertEquals(12 * 60_000L, PartsInstaller.installTimeoutMs(120 * mb))
        assertEquals(45 * 60_000L, PartsInstaller.installTimeoutMs(100_000 * mb))
        assertEquals(10 * 60_000L, PartsInstaller.installTimeoutMs(-1))
    }

    // --- result mapping -----------------------------------------------------------------------------------------

    @Test
    fun `the platform result is mapped and a failure always names its status`() = runTest {
        val file = apk("a.apk")
        suspend fun run(status: Int, message: String?): InstallOutcome {
            val bus = InstallResultBus()
            bus.publish(SESSION, status, message)
            return PartsInstaller({ file }, { SESSION }, bus).install(listOf(ApkPart(localPath = file.path)))
        }
        assertEquals(InstallOutcome.Success, run(STATUS_SUCCESS, null))
        assertEquals("FAILURE_STORAGE", (run(STATUS_FAILURE_STORAGE, null) as InstallOutcome.Failure).reason)
        assertEquals(
            "FAILURE_STORAGE: Not enough space",
            (run(STATUS_FAILURE_STORAGE, "Not enough space") as InstallOutcome.Failure).reason,
        )
        assertEquals(
            "INSTALL_FAILED_INSUFFICIENT_STORAGE: no room",
            (run(STATUS_FAILURE_STORAGE, "INSTALL_FAILED_INSUFFICIENT_STORAGE: no room") as InstallOutcome.Failure)
                .reason,
        )
        assertTrue(file.exists()) // a caller-provided APK is never deleted
    }

    // --- retries ------------------------------------------------------------------------------------------------

    @Test
    fun `a fetch that failed for lack of network is left for a later check-in`() = runTest {
        var committed = false
        val installer = PartsInstaller(
            obtain = { throw IOException("Unable to resolve host") },
            commit = { committed = true; SESSION },
            resultBus = InstallResultBus(),
        )
        val thrown = runCatching { installer.install(listOf(ApkPart(url = "https://x/a.apk"))) }.exceptionOrNull()
        assertTrue(thrown is RetryLaterException)
        assertFalse(committed)
    }

    @Test
    fun `a server error is transient and a missing file is a definitive failure`() = runTest {
        suspend fun run(error: Throwable): Result<InstallOutcome> = runCatching {
            PartsInstaller({ throw error }, { SESSION }, InstallResultBus())
                .install(listOf(ApkPart(url = "https://x/a.apk")))
        }
        assertTrue(run(IllegalStateException("HTTP 503 for https://x/a.apk")).exceptionOrNull() is RetryLaterException)
        val gone = run(IllegalStateException("HTTP 404 for https://x/a.apk")).getOrThrow()
        assertEquals("apk fetch failed: HTTP 404 for https://x/a.apk", (gone as InstallOutcome.Failure).reason)
    }

    @Test
    fun `a download that fills the phone is a reported failure, not a silent retry for ever`() = runTest {
        val outcome = PartsInstaller(
            obtain = { throw IOException("write failed: ENOSPC (No space left on device)") },
            commit = { SESSION },
            resultBus = InstallResultBus(),
        ).install(listOf(ApkPart(url = "https://x/a.apk")))
        val reason = (outcome as InstallOutcome.Failure).reason
        assertTrue(reason, reason.startsWith("apk fetch failed:") && reason.contains("ENOSPC"))
    }

    @Test
    fun `a session that cannot be written is a failure, not a crash`() = runTest {
        val file = apk("a.apk")
        val outcome = PartsInstaller(
            obtain = { file },
            commit = { throw IOException("write failed: ENOSPC (No space left on device)") },
            resultBus = InstallResultBus(),
        ).install(listOf(ApkPart(localPath = file.path)))
        val reason = (outcome as InstallOutcome.Failure).reason
        assertTrue(reason, reason.startsWith("install session error:") && reason.contains("ENOSPC"))
    }

    // --- sha256 -------------------------------------------------------------------------------------------------

    @Test
    fun `a part whose sha256 does not match is refused before anything is installed`() = runTest {
        val file = apk("downloaded.apk", "tampered")
        val actual = sha(file)
        var committed = false
        val outcome = PartsInstaller(
            obtain = { file },
            commit = { committed = true; SESSION },
            resultBus = InstallResultBus(),
        ).install(listOf(ApkPart(url = "https://x/a.apk", sha256 = "00".repeat(32))))

        val reason = (outcome as InstallOutcome.Failure).reason
        assertTrue(reason, reason.startsWith("checksum mismatch: expected ${"00".repeat(32)} got $actual"))
        assertFalse("nothing reaches the installer", committed)
    }

    @Test
    fun `a damaged download is deleted so the next try fetches it again`() = runTest {
        val file = apk("downloaded.apk", "tampered")
        PartsInstaller({ file }, { SESSION }, InstallResultBus())
            .install(listOf(ApkPart(url = "https://x/a.apk", sha256 = "00".repeat(32))))
        assertFalse(file.exists())
    }

    @Test
    fun `every part of a bundle is verified, in any letter case, and all go into one session`() = runTest {
        val base = apk("base.apk")
        val split = apk("config.es.apk")
        val bus = InstallResultBus()
        bus.publish(SESSION, STATUS_SUCCESS, null)
        var installed: List<File> = emptyList()
        val files = mapOf("https://x/base.apk" to base, "https://x/es.apk" to split)
        val outcome = PartsInstaller(
            obtain = { files.getValue(it.url!!) },
            commit = { installed = it; SESSION },
            resultBus = bus,
        ).install(
            listOf(
                ApkPart(url = "https://x/base.apk", sha256 = sha(base).uppercase()),
                ApkPart(url = "https://x/es.apk", sha256 = sha(split)),
            ),
        )
        assertEquals(InstallOutcome.Success, outcome)
        assertEquals(listOf(base, split), installed)
        assertFalse("downloads are cleaned up after installing", base.exists() || split.exists())
    }

    @Test
    fun `one bad split fails the whole bundle`() = runTest {
        val base = apk("base.apk")
        val split = apk("config.es.apk")
        val files = mapOf("https://x/base.apk" to base, "https://x/es.apk" to split)
        var committed = false
        val outcome = PartsInstaller(
            obtain = { files.getValue(it.url!!) },
            commit = { committed = true; SESSION },
            resultBus = InstallResultBus(),
        ).install(
            listOf(
                ApkPart(url = "https://x/base.apk", sha256 = sha(base)),
                ApkPart(url = "https://x/es.apk", sha256 = sha(base)),
            ),
        )
        assertTrue((outcome as InstallOutcome.Failure).reason.startsWith("checksum mismatch"))
        assertFalse(committed)
    }
}
