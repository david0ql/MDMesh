package com.dallycontrol.core.data

import com.dallycontrol.proto.ConfigMobileData
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** An installed package as the mobile-data rules see it. */
data class DataApp(val pkg: String, val internet: Boolean, val system: Boolean, val launchable: Boolean)

/** A limit that reached its warning threshold ([WARN]) or was spent ([LIMIT]). [key]: [DEVICE] or the package. */
data class Crossing(val key: String, val level: String, val used: Long, val limit: Long) {
    companion object {
        const val DEVICE = "device"
        const val WARN = "warn"
        const val LIMIT = "limit"
    }
}

/** [blocked]: package -> reason. [crossings]: every limit at or over its threshold right now (not deduplicated). */
data class DataPlan(val blocked: Map<String, String>, val crossings: List<Crossing>)

/**
 * Decides which apps lose mobile data, from the policy, what is installed and what was used in the period. Pure, so
 * the rules are tested without a device:
 *  - `blocklist`: the listed apps; `allowlist`: every user-facing app except the listed ones;
 *  - an app at or over its own limit; and, once the device limit is spent, every user-facing app except the
 *    essentials.
 * "User-facing" = uses the network and is either installed by the user/MDM or a system app with a launcher icon.
 * System services without an icon and Google Play services are only restricted when the policy lists them by name.
 * [protected] packages (the agent, remote support) are never restricted.
 */
object MobileDataPlanner {
    const val REASON_POLICY = "policy"
    const val REASON_APP_LIMIT = "appLimit"
    const val REASON_DEVICE_LIMIT = "deviceLimit"
    private const val FULL = 100

    /** Never restricted by a list or by the device limit: cutting them breaks sign-in, push and location everywhere. */
    val CORE_SERVICES = setOf("com.google.android.gms", "com.google.android.gsf")

    fun plan(
        cfg: ConfigMobileData,
        installed: List<DataApp>,
        usage: Map<String, Long>,
        deviceBytes: Long,
        protected: Set<String>,
    ): DataPlan {
        val candidates = installed.filter { it.internet && it.pkg !in protected }
        val known = candidates.mapTo(HashSet()) { it.pkg }
        val userFacing = candidates.filter { (!it.system || it.launchable) && it.pkg !in CORE_SERVICES }.map { it.pkg }
        val essential = cfg.essential.toSet()
        val blocked = LinkedHashMap<String, String>()
        listed(cfg, known, userFacing).forEach { blocked[it] = REASON_POLICY }
        val warnPct = cfg.warnPercent.coerceIn(1, FULL)
        val crossings = ArrayList<Crossing>()
        for (l in cfg.appLimits.filter { it.pkg in known }) {
            val c = crossing(l.pkg, usage[l.pkg] ?: 0, l.bytes, warnPct) ?: continue
            crossings += c
            if (c.level == Crossing.LIMIT) blocked.putIfAbsent(l.pkg, REASON_APP_LIMIT)
        }
        crossing(Crossing.DEVICE, deviceBytes, cfg.deviceLimitBytes ?: 0, warnPct)?.let { c ->
            crossings += c
            if (c.level == Crossing.LIMIT) {
                userFacing.filterNot { it in essential }.forEach { blocked.putIfAbsent(it, REASON_DEVICE_LIMIT) }
            }
        }
        return DataPlan(blocked, crossings)
    }

    /** The apps the policy's list keeps off mobile data. */
    private fun listed(cfg: ConfigMobileData, known: Set<String>, userFacing: List<String>): List<String> =
        when (cfg.mode) {
            "blocklist" -> cfg.apps.filter { it in known }
            "allowlist" -> (cfg.apps.toSet() + cfg.essential).let { allowed -> userFacing.filterNot { it in allowed } }
            else -> emptyList()
        }

    private fun crossing(key: String, used: Long, limit: Long, warnPct: Int): Crossing? = when {
        limit <= 0 -> null
        used >= limit -> Crossing(key, Crossing.LIMIT, used, limit)
        warnPct < FULL && used > 0 && used * FULL >= limit * warnPct -> Crossing(key, Crossing.WARN, used, limit)
        else -> null
    }

    /** True when the section asks for nothing: no list and no limit. */
    fun isIdle(cfg: ConfigMobileData?): Boolean =
        cfg == null || (cfg.mode != "blocklist" && cfg.mode != "allowlist" && !hasLimits(cfg))

    fun hasLimits(cfg: ConfigMobileData?): Boolean =
        cfg != null && ((cfg.deviceLimitBytes ?: 0) > 0 || cfg.appLimits.any { it.bytes > 0 })
}

/** The counting period of the limits. */
object DataPeriod {
    private const val LAST_CUT_DAY = 28

    /** `[start, end)` of the period that contains [now]: the local day, or the month starting on [cutDay] (1..28). */
    fun bounds(now: Long, period: String, cutDay: Int, zone: TimeZone = TimeZone.getDefault()): Pair<Long, Long> {
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val unit = if (period == "daily") Calendar.DAY_OF_MONTH else Calendar.MONTH
        if (unit == Calendar.MONTH) {
            val cut = cutDay.coerceIn(1, LAST_CUT_DAY)
            if (c.get(Calendar.DAY_OF_MONTH) < cut) c.add(Calendar.MONTH, -1)
            c.set(Calendar.DAY_OF_MONTH, cut)
        }
        val start = c.timeInMillis
        c.add(unit, 1)
        return start to c.timeInMillis
    }
}

/** Remembers which crossings were already announced in the current period, so each is one event (and one alert). */
object QuotaLedger {
    /** @return the crossings not announced yet, and the ledger to store (entries of other periods dropped). */
    fun fresh(periodStart: Long, crossings: List<Crossing>, seen: Set<String>): Pair<List<Crossing>, Set<String>> {
        val prefix = "$periodStart|"
        val kept = seen.filterTo(LinkedHashSet()) { it.startsWith(prefix) }
        val out = ArrayList<Crossing>()
        for (c in crossings) if (kept.add("$prefix${c.key}|${c.level}")) out += c
        return out to kept
    }
}

/** The text of a limit event, shown as is in the console's alerts and e-mails (Spanish). */
object QuotaText {
    private const val KB = 1024.0
    private const val PERCENT = 100

    fun bytes(n: Long): String {
        val mb = n / (KB * KB)
        return if (mb >= KB) trim(mb / KB) + " GB" else trim(Math.floor(mb)) + " MB"
    }

    private fun trim(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

    /** `label (pkg): used de limit (n %)`; [pkg] null = the whole device. */
    fun line(label: String?, pkg: String?, used: Long, limit: Long): String {
        val who = when {
            pkg == null -> "Equipo"
            label.isNullOrBlank() || label == pkg -> pkg
            else -> "$label ($pkg)"
        }
        val pct = if (limit > 0) used * PERCENT / limit else 0
        return "$who: ${bytes(used)} de ${bytes(limit)} ($pct %)"
    }
}
