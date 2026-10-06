package com.dallycontrol.core.install

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** One APK file of an install — a whole app, or a single split of a bundle. */
data class ApkPart(
    /** Remote APK URL; mutually exclusive-ish with [localPath] (URL preferred). */
    val url: String? = null,
    /** Absolute path to an already-present APK on the device. */
    val localPath: String? = null,
    /** Optional lowercase hex SHA-256 of this part; verified after fetch if present. */
    val sha256: String? = null,
    /** The part's name inside its bundle (`config.arm64_v8a`, `config.es`…); lets [SplitSelector] skip what this phone does not need. */
    val split: String? = null,
)

/** What the caller wants installed. */
data class InstallRequest(
    /** Remote APK URL; mutually exclusive-ish with [localPath] (URL preferred). */
    val url: String? = null,
    /** Absolute path to an already-present APK on the device. */
    val localPath: String? = null,
    /** Target package name; required for [PackageInstaller.SessionParams.setAppPackageName]. */
    val packageName: String,
    /** Requested version code; `null`/`0` means "any" (see [VersionPolicy]). */
    val versionCode: Long? = null,
    /**
     * The version's name. Some apps ship each build under the same version code and change only the name: with the
     * same code installed under another name, the app is installed again (once per name, see [VersionPolicy]).
     */
    val versionName: String? = null,
    /**
     * The console allowed going back to an older version: when the phone has a newer one, it is uninstalled first
     * (Android installs nothing older over a newer one) — the app's data on the phone is lost; the console warned.
     */
    val allowDowngrade: Boolean = false,
    /** Optional lowercase hex SHA-256 of the APK; verified after download if present. */
    val sha256: String? = null,
    /** When true and the install succeeds, launch the app's main activity. */
    val runAfterInstall: Boolean = false,
    /**
     * Split-APK bundle parts (base + config/feature splits). When non-empty this is a multi-APK
     * install and [url]/[localPath]/[sha256] are ignored; every part is written into ONE
     * PackageInstaller session (the only correct way to install splits). Empty = single-APK install.
     */
    val parts: List<ApkPart> = emptyList(),
) {
    /** Normalized part list: the explicit [parts], or the single [url]/[localPath] as one part. */
    val apkParts: List<ApkPart>
        get() = if (parts.isNotEmpty()) parts else listOf(ApkPart(url, localPath, sha256))
}

/** Result of an install/uninstall attempt. */
sealed interface InstallOutcome {
    /** The package is installed at the requested version (or was just installed/removed). */
    data object Success : InstallOutcome

    /** No action needed (e.g. already at the requested version); [reason] explains. */
    data class Skipped(val reason: String) : InstallOutcome

    /** The attempt failed. [status] is a `PackageInstaller.STATUS_*` when one was reported. */
    data class Failure(val status: Int?, val reason: String) : InstallOutcome
}

/**
 * Silent (Device-Owner / privileged) app install + uninstall via [PackageInstaller].
 *
 * Modernized port of Headwind's `InstallUtils` serialized pump:
 *  - OkHttp download to [Context.getCacheDir] instead of `HttpURLConnection`.
 *  - One `suspend` call that commits a session and awaits its result through
 *    [InstallResultBus] (manifest receiver), instead of `AsyncTask` recursion.
 *  - The commit `PendingIntent` is **explicit** (action [InstallResultBus.ACTION],
 *    `setPackage(ctx.packageName)`), `FLAG_IMMUTABLE`, with `requestCode = sessionId`
 *    so each session's broadcast is uniquely addressable.
 *  - `setInstallReason(INSTALL_REASON_POLICY)` and, on API 31+,
 *    `setRequireUserAction(USER_ACTION_NOT_REQUIRED)`.
 *
 * Version gating is delegated to the pure [VersionPolicy]; downgrades are blocked so the
 * download/install loop can't churn forever (the caller must uninstall first).
 *
 * @see InstallResultBus for the action/extra constants the `:app` receiver consumes.
 */
@Singleton
class InstallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resultBus: InstallResultBus,
    private val httpClient: OkHttpClient,
) {

    private val packageInstaller: PackageInstaller
        get() = context.packageManager.packageInstaller

    /**
     * Downloads (or reads) the APK, applies the version policy, then performs a silent
     * install and awaits the platform's result. Returns the mapped [InstallOutcome].
     */
    suspend fun install(req: InstallRequest): InstallOutcome {
        // 1. Version gate (pure) — skip / block before touching the network.
        val installed = installedVersion(req.packageName)
        val triedKey = "name:${req.packageName}"
        val tried = req.versionName != null && names.getString(triedKey, null) == req.versionName
        when (val decision = VersionPolicy.shouldInstall(installed?.first, req.versionCode, installed?.second, req.versionName, tried)) {
            VersionPolicy.Decision.Install -> {
                // Same code under a new name: remember the name now, so a build Android refuses is not retried forever.
                if (installed != null && installed.first == req.versionCode && req.versionName != null) {
                    names.edit().putString(triedKey, req.versionName).apply()
                }
            }
            is VersionPolicy.Decision.Skip -> return InstallOutcome.Skipped(decision.reason)
            VersionPolicy.Decision.DowngradeBlocked -> {
                // Never for the agent itself: uninstalling it would end the management of the phone.
                if (!req.allowDowngrade || req.packageName == context.packageName || req.packageName.startsWith("com.dallycontrol.agent")) {
                    return InstallOutcome.Failure(
                        status = null,
                        reason = "downgrade blocked for ${req.packageName}: uninstall the current version first",
                    )
                }
                // Allowed by the console: take the newer one off, then install the chosen older version below.
                when (val removed = uninstall(req.packageName)) {
                    is InstallOutcome.Failure -> return InstallOutcome.Failure(removed.status, "could not uninstall the newer version: ${removed.reason}")
                    else -> Unit
                }
            }
        }

        // The phone's system refused this very version before (e.g. Xiaomi HyperOS lets only its own store update Google
        // apps it ships): do not download it again on every policy save.
        val blockedKey = "blocked:${req.packageName}"
        val blockedFor = osBlocked.getLong(blockedKey, -1L)
        if (blockedFor >= 0 && blockedFor == (req.versionCode ?: 0L)) {
            return InstallOutcome.Skipped("el sistema del teléfono no deja instalar esta app desde el MDM (${osBlocked.getString("$blockedKey:why", "")})")
        }

        // 2. Obtain every part (single APK, or base + splits of a bundle), verifying each
        // part's optional checksum. All parts install together in one session (step 3).
        // Of a bundle, only the splits for this phone's processor, screen and languages (the rest are never downloaded).
        val parts = runCatching {
            val res = context.resources
            val langs = if (Build.VERSION.SDK_INT >= 24) {
                val l = res.configuration.locales
                (0 until l.size()).map { l[it].language }
            } else {
                @Suppress("DEPRECATION")
                listOf(res.configuration.locale.language)
            }
            SplitSelector.select(req.apkParts, { it.split }, Build.SUPPORTED_ABIS.toList(), res.displayMetrics.densityDpi, langs)
        }.getOrDefault(req.apkParts)
        val fetched = ArrayList<FetchedApk>(parts.size)
        try {
            for (part in parts) {
                val file = runCatching { obtainPart(part) }
                    .getOrElse {
                        rethrowCancel(it)
                        // No internet / DNS / server hiccup: not this install's fault — leave it for a later check-in.
                        if (isTransient(it)) throw com.dallycontrol.core.command.RetryLaterException("apk fetch: ${it.message}")
                        return InstallOutcome.Failure(null, "apk fetch failed: ${it.message}")
                    }
                fetched += FetchedApk(file, downloaded = part.url != null)
                part.sha256?.let { expected ->
                    val actual = sha256Of(file)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        return InstallOutcome.Failure(null, "checksum mismatch: expected $expected got $actual")
                    }
                }
            }

            // 3. Create + write EVERY part into ONE session + commit, awaiting the broadcast result.
            val outcome = runCatching { commitInstall(req.packageName, fetched.map { it.file }) }
                .getOrElse { rethrowCancel(it); return InstallOutcome.Failure(null, "install session error: ${it.message}") }

            if (outcome is InstallOutcome.Failure && OS_BLOCKS.any { outcome.reason.contains(it) }) {
                osBlocked.edit().putLong(blockedKey, req.versionCode ?: 0L)
                    .putString("$blockedKey:why", outcome.reason.substringBefore(':')).apply()
                return InstallOutcome.Skipped("el sistema del teléfono no deja instalar esta app desde el MDM (${outcome.reason.substringBefore(':')})")
            }

            // 4. Optionally launch the app on success.
            if (outcome is InstallOutcome.Success && req.runAfterInstall) {
                launchApp(req.packageName)
            }
            return outcome
        } finally {
            // Only clean up files we downloaded; never delete a caller-provided APK.
            fetched.forEach { if (it.downloaded) it.file.delete() }
        }
    }

    /**
     * A cancelled check-in (the worker stopped by a network change, a SIM inserted, the system) is not a failed install:
     * let it propagate so no result is reported and the server hands the install out again.
     */
    private fun rethrowCancel(t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
    }

    /** A network failure (no connection, DNS, timeout, TLS) or a server error (5xx) — worth trying again later. */
    private fun isTransient(t: Throwable): Boolean =
        t is java.io.IOException || (t is IllegalStateException && t.message?.startsWith("HTTP 5") == true)

    /** A fetched part plus whether we downloaded it (and therefore own its cleanup). */
    private data class FetchedApk(val file: File, val downloaded: Boolean)

    /** Silently uninstalls [packageName], awaiting the platform's result. */
    @SuppressLint("MissingPermission") // Device Owner holds DELETE_PACKAGES (declared in the app)
    suspend fun uninstall(packageName: String): InstallOutcome {
        if (installedVersion(packageName) == null) {
            return InstallOutcome.Skipped("not installed: $packageName")
        }
        // Reuse a stable session id derived from the package so the PendingIntent is unique.
        val sessionId = -(packageName.hashCode() and 0x7fff_ffff) - 1
        return try {
            packageInstaller.uninstall(packageName, resultSender(sessionId).intentSender)
            mapResult(resultBus.await(sessionId))
        } catch (e: Exception) {
            InstallOutcome.Failure(null, "uninstall error: ${e.message}")
        }
    }

    // --- internals -----------------------------------------------------------------

    /**
     * Writes ALL [apks] into a single install session and commits once. One apk installs a whole
     * app; several install a split set (base + config/feature splits) atomically — the platform
     * validates on commit that they share package, version, and signing cert.
     */
    private suspend fun commitInstall(packageName: String, apks: List<File>): InstallOutcome =
        withContext(Dispatchers.IO) {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                setSize(apks.sumOf { it.length() })
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInstallReason(PackageManager.INSTALL_REASON_POLICY) // API 26+ (advisory metadata)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                // Each split needs a distinct name within the session; the platform reads the real
                // split identity from each APK's manifest, so index-based names are fine.
                apks.forEachIndexed { i, apk ->
                    FileInputStream(apk).use { input ->
                        session.openWrite("part_$i.apk", 0, apk.length()).use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                            session.fsync(output)
                        }
                    }
                }
                session.commit(resultSender(sessionId).intentSender)
            }

            mapResult(resultBus.await(sessionId))
        }

    /**
     * Builds the explicit, immutable [PendingIntent] for a session's result broadcast.
     * The intent action is [InstallResultBus.ACTION], scoped to our own package, and
     * carries the session id so the (single) manifest receiver can correlate it.
     */
    private fun resultSender(sessionId: Int): PendingIntent {
        val intent = Intent(InstallResultBus.ACTION)
            .setPackage(context.packageName)
            .putExtra(InstallResultBus.EXTRA_SESSION_ID, sessionId)
        // MUST be MUTABLE: PackageInstaller.commit() fills the result extras (EXTRA_STATUS...)
        // into this PendingIntent. FLAG_IMMUTABLE makes commit() throw
        // "the status receiver should come from a mutable PendingIntent". The intent is
        // explicit (setPackage), so a mutable PendingIntent is not the unsafe-implicit case.
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    private fun mapResult(event: InstallResultBus.InstallResultEvent): InstallOutcome =
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
            else -> InstallOutcome.Failure(event.status, event.message ?: statusName(event.status))
        }

    private suspend fun obtainPart(part: ApkPart): File {
        part.localPath?.let { return File(it) }
        val url = requireNotNull(part.url) { "install part needs either url or localPath" }
        return withContext(Dispatchers.IO) { download(url) }
    }

    /**
     * Big APKs over a shaky mobile link: HTTP/1.1 (an HTTP/2 stream reset — "stream was reset: INTERNAL_ERROR" — killed
     * WhatsApp downloads), and up to [DOWNLOAD_TRIES] attempts that resume where the last one stopped (Range).
     */
    private fun download(url: String): File {
        val dest = File(context.cacheDir, "mdm-install-${System.nanoTime()}.apk")
        var lastError: Throwable? = null
        for (attempt in 1..DOWNLOAD_TRIES) {
            try {
                val have = if (dest.exists()) dest.length() else 0L
                val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                downloadClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code} for $url")
                    val resumed = have > 0 && response.code == 206
                    val body = response.body ?: error("empty response body for $url")
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(dest, resumed).use { input.copyTo(it) }
                    }
                }
                return dest
            } catch (e: Throwable) {
                lastError = e
                if (e is IllegalStateException && e.message?.startsWith("HTTP 4") == true) break // not found / forbidden: no retry
                Thread.sleep(2_000L * attempt)
            }
        }
        dest.delete()
        throw lastError ?: IllegalStateException("download failed")
    }

    private val downloadClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** Versions the phone's own system refused (see [OS_BLOCKS]). */
    private val osBlocked by lazy { context.getSharedPreferences("mdm_install_os_blocked", Context.MODE_PRIVATE) }

    private companion object {
        const val DOWNLOAD_TRIES = 4
        /** Install errors meaning "this phone's system will never let the MDM install this app" (not a transient fault). */
        val OS_BLOCKS = listOf("ISOLATION_VIOLATION", "INSTALL_FAILED_USER_RESTRICTED")
    }

    /** The installed version code and name, or null when the package is not installed. */
    private fun installedVersion(packageName: String): Pair<Long, String?>? = runCatching {
        @Suppress("DEPRECATION")
        val info: PackageInfo = context.packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        code to info.versionName
    }.getOrNull()

    /** The version names already installed over the same version code (one try per name). */
    private val names by lazy { context.getSharedPreferences("mdm_install_names", Context.MODE_PRIVATE) }

    private fun launchApp(packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream: InputStream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun statusName(status: Int): String = when (status) {
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
