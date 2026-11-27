package com.dallycontrol.agent.data

import android.Manifest
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import com.dallycontrol.core.data.DataApp

/**
 * What the mobile-data rules need to know about the phone: the mobile consumption since a moment, read from the
 * system's own counters (`NetworkStatsManager`, the ones Settings shows; a Device Owner may read them), and the
 * installed apps. One query covers the whole period, so nothing is accumulated here and nothing is counted twice.
 */
class MobileDataUsage(private val context: Context) {
    private var appCache: Pair<Set<String>, List<DataApp>>? = null

    /** Mobile bytes (received + sent) per uid in `[start, end]`. */
    fun byUid(start: Long, end: Long): Map<Int, Long> = runCatching {
        val perUid = HashMap<Int, Long>()
        @Suppress("DEPRECATION")
        stats()?.querySummary(ConnectivityManager.TYPE_MOBILE, null, start, end)?.use { summary ->
            val b = NetworkStats.Bucket()
            while (summary.hasNextBucket()) {
                summary.getNextBucket(b)
                perUid[b.uid] = (perUid[b.uid] ?: 0) + b.rxBytes + b.txBytes
            }
        }
        perUid
    }.getOrDefault(emptyMap())

    /** The device's whole mobile consumption (includes tethering and removed apps); null if it cannot be read. */
    fun device(start: Long, end: Long): Long? = runCatching {
        @Suppress("DEPRECATION")
        val total = stats()?.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end)
        total?.let { it.rxBytes + it.txBytes }
    }.getOrNull()

    private fun stats(): NetworkStatsManager? =
        context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager

    /** Apps that share a uid share its consumption: each of them is charged the uid's total. */
    fun byPackage(perUid: Map<Int, Long>): Map<String, Long> {
        val pm = context.packageManager
        val out = HashMap<String, Long>()
        for ((uid, n) in perUid) {
            val system = uid == Process.SYSTEM_UID || uid < Process.FIRST_APPLICATION_UID
            val pkgs = if (system) arrayOf("android") else pm.getPackagesForUid(uid).orEmpty()
            if (n > 0) pkgs.forEach { out[it] = (out[it] ?: 0) + n }
        }
        return out
    }

    /** Every installed app with what the rules look at; cached until the set of packages changes. */
    fun installedApps(): List<DataApp> {
        val pm = context.packageManager
        val infos = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val names = infos.mapTo(HashSet()) { it.packageName }
        val cached = appCache?.takeIf { it.first == names }?.second
        return cached ?: infos.map { ai ->
            DataApp(
                pkg = ai.packageName,
                internet = pm.checkPermission(Manifest.permission.INTERNET, ai.packageName) == GRANTED,
                system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                launchable = runCatching { pm.getLaunchIntentForPackage(ai.packageName) != null }.getOrDefault(false),
            )
        }.also { appCache = names to it }
    }

    fun label(pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /** An installed package that cannot use the network: what the local VPN holds on Wi-Fi so it stays connected. */
    fun idlePackage(protected: Set<String>): String? {
        val offline = installedApps().filter { !it.internet && it.pkg !in protected }
        return IDLE_CANDIDATES.firstOrNull { c -> offline.any { it.pkg == c } }
            ?: offline.filter { it.system }.minByOrNull { it.pkg }?.pkg
    }

    private companion object {
        const val GRANTED = PackageManager.PERMISSION_GRANTED

        /** Present on AOSP builds and without the INTERNET permission. */
        val IDLE_CANDIDATES =
            listOf("com.android.htmlviewer", "com.android.bookmarkprovider", "com.android.printspooler")
    }
}
