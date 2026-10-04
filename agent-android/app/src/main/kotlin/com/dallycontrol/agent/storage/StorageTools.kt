package com.dallycontrol.agent.storage

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume

/**
 * Free space from the console: what takes the space (apps and the largest files) and cleaning it.
 *
 * Android gates both: per-app sizes need "usage access" and deleting other apps' files (Android 11+) needs "all files
 * access". A Device Owner cannot switch either on by itself; `scripts/adb-enroll.sh` does, or [StorageAccessHandler]
 * opens the setting for the person holding the phone. Without them the scan still reports totals and the media it can
 * read, and says what is missing.
 */
class StorageTools(private val context: Context, private val handle: DpmHandle) {

    @Serializable
    data class AppUse(val packageName: String, val label: String, val appBytes: Long, val dataBytes: Long, val cacheBytes: Long, val system: Boolean)

    @Serializable
    data class FileUse(val id: Long, val name: String, val path: String?, val bytes: Long, val mime: String?, val modifiedAt: Long)

    @Serializable
    data class Scan(
        val totalBytes: Long,
        val freeBytes: Long,
        val usageAccess: Boolean,
        val filesAccess: Boolean,
        val apps: List<AppUse>,
        val files: List<FileUse>,
        val note: String? = null,
    )

    fun usageAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Deleting other apps' files: all-files access on Android 11+, the storage permission before. */
    fun filesAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** The read-media permissions are runtime ones: the Device Owner grants them to itself. */
    private fun grantMediaRead() {
        if (Build.VERSION.SDK_INT < 23) return
        val perms = if (Build.VERSION.SDK_INT >= 33) listOf(
            android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_AUDIO,
        ) else listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE) +
            (if (Build.VERSION.SDK_INT <= 29) listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyList())
        perms.forEach { p ->
            if (context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) runCatching {
                handle.dpm.setPermissionGrantState(handle.admin, context.packageName, p, android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
    }

    fun scan(limit: Int = 25): Scan {
        grantMediaRead()
        val stat = StatFs(Environment.getDataDirectory().path)
        val usage = usageAccess()
        val apps = if (usage && Build.VERSION.SDK_INT >= 26) appSizes(limit) else emptyList()
        val files = runCatching { largestFiles(limit) }.getOrDefault(emptyList())
        val note = listOfNotNull(
            if (!usage) "Sin acceso de uso: no se ve cuánto ocupa cada app." else null,
            if (!filesAccess()) "Sin acceso a todos los archivos: los archivos se listan pero no se pueden borrar." else null,
        ).joinToString(" ").ifEmpty { null }
        return Scan(stat.totalBytes, stat.availableBytes, usage, filesAccess(), apps, files, note)
    }

    @Suppress("NewApi")
    private fun appSizes(limit: Int): List<AppUse> {
        val ssm = context.getSystemService(StorageStatsManager::class.java) ?: return emptyList()
        val pm = context.packageManager
        val user = Process.myUserHandle()
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            runCatching {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, user)
                AppUse(
                    ai.packageName, pm.getApplicationLabel(ai).toString(), s.appBytes, s.dataBytes, s.cacheBytes,
                    (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }.getOrNull()
        }.sortedByDescending { it.appBytes + it.dataBytes }.take(limit)
    }

    private fun filesUri(): Uri = if (Build.VERSION.SDK_INT >= 29) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Files.getContentUri("external")

    private fun largestFiles(limit: Int): List<FileUse> {
        val cols = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED)
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA
        cols += pathCol
        val out = mutableListOf<FileUse>()
        context.contentResolver.query(
            filesUri(), cols.toTypedArray(), "${MediaStore.MediaColumns.SIZE} > ?", arrayOf("1048576"),
            "${MediaStore.MediaColumns.SIZE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                out += FileUse(
                    id = c.getLong(0), name = c.getString(1) ?: "?", bytes = c.getLong(2), mime = c.getString(3),
                    modifiedAt = c.getLong(4) * 1000, path = c.getString(5),
                )
            }
        }
        return out
    }

    /** Delete media by MediaStore id; returns how many went and why the rest did not. */
    fun deleteFiles(ids: List<Long>): Pair<Int, String?> {
        var ok = 0
        var why: String? = null
        ids.forEach { id ->
            runCatching { context.contentResolver.delete(ContentUris.withAppendedId(filesUri(), id), null, null) }
                .onSuccess { if (it > 0) ok++ }
                .onFailure { why = if (!filesAccess()) "falta el acceso a todos los archivos" else it.message }
        }
        return ok to why
    }

    /** Wipe an app's data (Android 9+, Device Owner); refuses this agent. */
    suspend fun clearAppData(pkg: String): String? {
        if (pkg == context.packageName) return "no se puede borrar el agente"
        if (Build.VERSION.SDK_INT < 28) return "requiere Android 9 o superior"
        return withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<String?> { cont ->
                handle.dpm.clearApplicationUserData(handle.admin, pkg, context.mainExecutor) { _, ok ->
                    if (cont.isActive) cont.resume(if (ok) null else "Android no lo permitió")
                }
            }
        } ?: "sin respuesta"
    }
}

/** `device.storageScan` — totals, the apps and files that take the most space (JSON in the result detail). */
class StorageScanHandler(private val tools: StorageTools) : CommandHandler {
    override val type: String = DeviceAction.STORAGE_SCAN
    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val scan = withContext(Dispatchers.IO) { tools.scan() }
        CommandResults.done(command, ProtocolJson.json.encodeToString(StorageTools.Scan.serializer(), scan))
    }.getOrElse { CommandResults.failed(command, it.message ?: "scan failed") }
}

/** `device.storageClean` — payload `{ "clearData": ["pkg"], "deleteFiles": [mediaId] }`. */
class StorageCleanHandler(private val tools: StorageTools) : CommandHandler {
    override val type: String = DeviceAction.STORAGE_CLEAN

    @Serializable
    private data class Payload(val clearData: List<String> = emptyList(), val deleteFiles: List<Long> = emptyList())

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val p = command.payload?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
            ?: return CommandResults.failed(command, "invalid payload")
        if (p.clearData.size > 50 || p.deleteFiles.size > 200) return CommandResults.failed(command, "too many items")
        val problems = mutableListOf<String>()
        var cleared = 0
        p.clearData.forEach { pkg ->
            val err = tools.clearAppData(pkg)
            if (err == null) cleared++ else problems += "$pkg: $err"
        }
        val (deleted, why) = withContext(Dispatchers.IO) { tools.deleteFiles(p.deleteFiles) }
        if (deleted < p.deleteFiles.size) problems += "${p.deleteFiles.size - deleted} archivos sin borrar${why?.let { ": $it" } ?: ""}"
        val summary = "apps limpiadas: $cleared, archivos borrados: $deleted" + if (problems.isEmpty()) "" else " — ${problems.joinToString("; ")}"
        return if (problems.isEmpty() || cleared + deleted > 0) CommandResults.done(command, summary) else CommandResults.failed(command, summary)
    }
}

/**
 * `device.storageAccess` — payload `{ "kind": "usage" | "files" | "notifications" }`: opens that setting on the phone for
 * the person holding it (a Device Owner cannot turn these on). In kiosk, Settings is let through for at most 5 minutes.
 */
class StorageAccessHandler(private val context: Context, private val handle: DpmHandle, private val tools: StorageTools) : CommandHandler {
    override val type: String = DeviceAction.STORAGE_ACCESS
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Serializable
    private data class Payload(val kind: String = "usage")

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val kind = command.payload?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }?.kind ?: "usage"
        val granted = { granted(kind) }
        if (granted()) return CommandResults.done(command, "already granted")
        if (kind == "files" && Build.VERSION.SDK_INT < 30) return CommandResults.done(command, "not needed before Android 11")
        val listener = com.dallycontrol.agent.diag.NotificationWatch.component(context)
        val intent = when (kind) {
            "files" -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            "notifications" -> if (Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
            } else Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            else -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }
        val am = context.getSystemService(ActivityManager::class.java)
        if (am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE && Build.VERSION.SDK_INT >= 26) runCatching {
            val current = handle.dpm.getLockTaskPackages(handle.admin)
            if (SETTINGS !in current) {
                handle.dpm.setLockTaskPackages(handle.admin, current + SETTINGS)
                scope.launch {
                    val until = System.currentTimeMillis() + 5 * 60_000L
                    while (System.currentTimeMillis() < until && !granted()) delay(2_000)
                    runCatching { handle.dpm.setLockTaskPackages(handle.admin, handle.dpm.getLockTaskPackages(handle.admin).filter { it != SETTINGS }.toTypedArray()) }
                }
            }
        }
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.fold(
            onSuccess = { CommandResults.done(command, "setting opened on the phone: turn on DallyControl") },
            onFailure = { CommandResults.failed(command, it.message ?: "could not open settings") },
        )
    }

    private fun granted(kind: String): Boolean = when (kind) {
        "files" -> tools.filesAccess()
        "notifications" -> com.dallycontrol.agent.diag.NotificationWatch.connected() || (Build.VERSION.SDK_INT >= 27 && runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)
                .isNotificationListenerAccessGranted(com.dallycontrol.agent.diag.NotificationWatch.component(context))
        }.getOrDefault(false))
        else -> tools.usageAccess()
    }

    private companion object { const val SETTINGS = "com.android.settings" }
}

/** Clearing every app's cache: Android 11+ offers it as a confirmation the person accepts; it needs all-files access. */
object CacheCleaner {
    /** @return null when Android's confirmation opened, else why not. */
    fun open(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return "needs Android 11 or newer"
        if (!Environment.isExternalStorageManager()) return "all-files access is off for the agent"
        // Android's confirmation must be asked for "with a result": let its screen through the kiosk for a moment and
        // open it from the agent's own bridge screen.
        val target = context.packageManager.resolveActivity(Intent(StorageManager.ACTION_CLEAR_APP_CACHE), 0)?.activityInfo?.packageName
            ?: return "this phone has no such screen"
        com.dallycontrol.agent.kiosk.TimedAllow.allow(context, target, 2 * 60_000L)
        return runCatching {
            context.startActivity(Intent(context, CacheClearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }.getOrElse { it.message ?: "could not open" }
    }
}

/** `device.clearCache` — opens Android's "clear cached files of all apps?" confirmation on the phone. */
class ClearCacheHandler(private val context: Context) : CommandHandler {
    override val type: String = DeviceAction.CLEAR_CACHE
    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val err = CacheCleaner.open(context)
        return if (err == null) CommandResults.done(command, "confirmation opened on the phone") else CommandResults.failed(command, err)
    }
}
