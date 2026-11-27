package com.dallycontrol.core.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which server URL the agent talks to: the provisioned one, or — while that one keeps failing — a backup the
 * server handed out (e.g. the direct domain, without the CDN in front). Persistent, so a restart does not go
 * back to an URL that is blocked on this network.
 *
 * Switches after [FAILOVER_FAILURES] consecutive unreachable-failures spread over at least [FAILOVER_AFTER_MS]
 * (a server restart of a few seconds must not move the fleet), and goes back to try the primary every
 * [RETRY_PRIMARY_MS].
 */
class ServerEndpoints(
    private val primary: () -> String,
    private val store: KeyValueStore,
    private val allowInsecure: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failures = 0
    private var firstFailureAt = 0L

    /** Base URL in use (no trailing slash). */
    @Synchronized
    fun activeUrl(): String {
        val primaryUrl = primary().trimEnd('/')
        val backups = backups()
        val index = index()
        if (index <= 0 || index > backups.size) return primaryUrl
        if (clock() - switchedAt() >= RETRY_PRIMARY_MS) {
            select(0)
            return primaryUrl
        }
        return backups[index - 1]
    }

    @Synchronized
    fun usingBackup(): Boolean = activeUrl() != primary().trimEnd('/')

    /** Backup URLs as stored (already sanitized). */
    @Synchronized
    fun backups(): List<String> =
        store.getString(KEY_BACKUPS).orEmpty().split('\n').filter { it.isNotBlank() }

    /** Replace the backup list with what the server sent; anything not https (or malformed) is dropped. */
    @Synchronized
    fun saveBackups(urls: List<String>) {
        val primaryUrl = primary().trimEnd('/')
        val clean = urls.mapNotNull { sanitize(it) }.filter { it != primaryUrl }.distinct().take(MAX_BACKUPS)
        if (clean == backups()) return
        store.putString(KEY_BACKUPS, clean.joinToString("\n"))
        if (index() > clean.size) select(0)
    }

    /** The active URL could not be reached (with network available). @return true when it switched to another. */
    @Synchronized
    fun onUnreachable(): Boolean {
        val now = clock()
        if (failures == 0) firstFailureAt = now
        failures++
        val backups = backups()
        if (backups.isEmpty()) return false
        if (failures < FAILOVER_FAILURES || now - firstFailureAt < FAILOVER_AFTER_MS) return false
        activeUrl() // settles a pending return to the primary before rotating
        select((index() + 1) % (backups.size + 1))
        return true
    }

    @Synchronized
    fun onReached() {
        failures = 0
    }

    private fun index(): Int = store.getString(KEY_INDEX)?.toIntOrNull() ?: 0
    private fun switchedAt(): Long = store.getString(KEY_SWITCHED_AT)?.toLongOrNull() ?: 0L

    private fun select(index: Int) {
        store.putString(KEY_INDEX, index.toString())
        store.putString(KEY_SWITCHED_AT, clock().toString())
        failures = 0
    }

    private fun sanitize(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        val plain = url.encodedPath == "/" && url.query == null && url.fragment == null &&
            url.username.isEmpty() && url.password.isEmpty()
        return if (plain && (url.isHttps || allowInsecure)) url.toString().trimEnd('/') else null
    }

    companion object {
        const val FAILOVER_FAILURES = 3
        const val FAILOVER_AFTER_MS = 60_000L
        const val RETRY_PRIMARY_MS = 15L * 60_000L
        const val MAX_BACKUPS = 3
        private const val KEY_BACKUPS = "backup_urls"
        private const val KEY_INDEX = "active_index"
        private const val KEY_SWITCHED_AT = "switched_at"
    }
}

/** Minimal persistent string store (SharedPreferences on the device, a map in tests). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

class MemoryKeyValueStore : KeyValueStore {
    private val map = HashMap<String, String>()
    override fun getString(key: String): String? = synchronized(map) { map[key] }
    override fun putString(key: String, value: String?) {
        synchronized(map) { if (value == null) map.remove(key) else map[key] = value }
    }
}
