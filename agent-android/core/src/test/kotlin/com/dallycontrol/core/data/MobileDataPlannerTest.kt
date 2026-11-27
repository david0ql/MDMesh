package com.dallycontrol.core.data

import com.dallycontrol.proto.ConfigDataLimit
import com.dallycontrol.proto.ConfigMobileData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MobileDataPlannerTest {
    private val agent = "com.dallycontrol.agent"
    private val apps = listOf(
        DataApp(agent, internet = true, system = false, launchable = true),
        DataApp("com.video", internet = true, system = false, launchable = true),
        DataApp("com.chat", internet = true, system = false, launchable = true),
        DataApp("com.offline", internet = false, system = false, launchable = true),
        DataApp("com.android.chrome", internet = true, system = true, launchable = true),
        DataApp("com.android.providers.downloads", internet = true, system = true, launchable = false),
        DataApp("com.google.android.gms", internet = true, system = true, launchable = true),
    )
    private val mb = 1024L * 1024L

    private fun plan(cfg: ConfigMobileData, usage: Map<String, Long> = emptyMap(), device: Long = 0) =
        MobileDataPlanner.plan(cfg, apps, usage, device, protected = setOf(agent))

    @Test fun `open mode without limits blocks nothing`() {
        assertTrue(plan(ConfigMobileData()).blocked.isEmpty())
        assertTrue(MobileDataPlanner.isIdle(ConfigMobileData()))
        assertTrue(MobileDataPlanner.isIdle(null))
        assertFalse(MobileDataPlanner.isIdle(ConfigMobileData(deviceLimitBytes = 1)))
    }

    @Test fun `blocklist blocks only the listed installed apps and never the agent`() {
        val p = plan(ConfigMobileData(mode = "blocklist", apps = listOf("com.video", "com.missing", agent)))
        assertEquals(mapOf("com.video" to MobileDataPlanner.REASON_POLICY), p.blocked)
    }

    @Test fun `allowlist blocks every user-facing app but the listed ones, the essentials and core services`() {
        val cfg =
            ConfigMobileData(mode = "allowlist", apps = listOf("com.chat"), essential = listOf("com.android.chrome"))
        val p = plan(cfg)
        assertEquals(setOf("com.video"), p.blocked.keys)
        assertFalse("an app without the INTERNET permission needs no rule", "com.offline" in p.blocked)
        assertFalse("system services without an icon keep working", "com.android.providers.downloads" in p.blocked)
        assertFalse("Google Play services are never restricted by a list", "com.google.android.gms" in p.blocked)
    }

    @Test fun `an app over its limit is blocked and one at the threshold only warns`() {
        val limits = listOf(ConfigDataLimit("com.video", 100 * mb), ConfigDataLimit("com.chat", 100 * mb))
        val cfg = ConfigMobileData(appLimits = limits)
        val p = plan(cfg, usage = mapOf("com.video" to 100 * mb, "com.chat" to 85 * mb))
        assertEquals(mapOf("com.video" to MobileDataPlanner.REASON_APP_LIMIT), p.blocked)
        assertEquals(
            setOf(
                Crossing("com.video", Crossing.LIMIT, 100 * mb, 100 * mb),
                Crossing("com.chat", Crossing.WARN, 85 * mb, 100 * mb),
            ),
            p.crossings.toSet(),
        )
    }

    @Test fun `the device limit blocks every non-essential app, the allowed ones too`() {
        val cfg = ConfigMobileData(
            mode = "allowlist", apps = listOf("com.chat"), deviceLimitBytes = 500 * mb, essential = listOf("com.chat"),
        )
        val under = plan(cfg, device = 400 * mb)
        assertEquals(listOf(Crossing(Crossing.DEVICE, Crossing.WARN, 400 * mb, 500 * mb)), under.crossings)
        assertEquals(setOf("com.video", "com.android.chrome"), under.blocked.keys)

        val over = plan(cfg.copy(essential = emptyList()), device = 501 * mb)
        assertEquals(setOf("com.video", "com.chat", "com.android.chrome"), over.blocked.keys)
        assertEquals(MobileDataPlanner.REASON_DEVICE_LIMIT, over.blocked["com.chat"])
        assertEquals("a list rule keeps its own reason", MobileDataPlanner.REASON_POLICY, over.blocked["com.video"])
        assertFalse(agent in over.blocked)
    }

    @Test fun `a warning below the threshold is not raised and the threshold is clamped`() {
        val cfg = ConfigMobileData(deviceLimitBytes = 100 * mb, warnPercent = 0)
        assertTrue(plan(cfg, device = 0).crossings.isEmpty())
        assertTrue(plan(cfg.copy(warnPercent = 50), device = 49 * mb).crossings.isEmpty())
        assertEquals(1, plan(cfg.copy(warnPercent = 50), device = 50 * mb).crossings.size)
        val full = plan(cfg.copy(warnPercent = 100), device = 99 * mb)
        assertTrue("100 % = no separate warning", full.crossings.isEmpty())
    }

    @Test fun `each crossing is announced once per period`() {
        val c = listOf(Crossing(Crossing.DEVICE, Crossing.WARN, 1, 2), Crossing("com.video", Crossing.LIMIT, 2, 2))
        val (first, seen) = QuotaLedger.fresh(1000L, c, emptySet())
        assertEquals(c, first)
        val (again, seen2) = QuotaLedger.fresh(1000L, c, seen)
        assertTrue(again.isEmpty())
        assertEquals(seen, seen2)
        val (next, seen3) = QuotaLedger.fresh(2000L, c, seen2)
        assertEquals("a new period announces again", c, next)
        assertTrue("the old period is forgotten", seen3.all { it.startsWith("2000|") })
    }

    @Test fun `monthly period starts on the cut day`() {
        val tz = TimeZone.getTimeZone("America/Bogota")
        fun at(y: Int, m: Int, d: Int, h: Int = 12) =
            Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, h, 0, 0) }.timeInMillis
        assertEquals(at(2026, 11, 15, 0) to at(2026, 12, 15, 0), DataPeriod.bounds(at(2026, 11, 27), "monthly", 15, tz))
        assertEquals(at(2026, 10, 28, 0) to at(2026, 11, 28, 0), DataPeriod.bounds(at(2026, 11, 27), "monthly", 31, tz))
        assertEquals(at(2026, 12, 1, 0) to at(2027, 1, 1, 0), DataPeriod.bounds(at(2026, 12, 31, 23), "monthly", 1, tz))
        assertEquals(at(2026, 12, 20, 0) to at(2027, 1, 20, 0), DataPeriod.bounds(at(2027, 1, 3), "monthly", 20, tz))
        val day = DataPeriod.bounds(at(2026, 11, 27, 23), "daily", 9, tz)
        assertEquals(at(2026, 11, 27, 0) to at(2026, 11, 28, 0), day)
    }

    @Test fun `limit lines read naturally`() {
        assertEquals("Equipo: 410 MB de 500 MB (82 %)", QuotaText.line(null, null, 410 * mb, 500 * mb))
        val line = QuotaText.line("YouTube", "com.video", 1536 * mb, 2048 * mb)
        assertEquals("YouTube (com.video): 1.5 GB de 2 GB (75 %)", line)
        assertEquals("com.video: 0 MB de 1 MB (0 %)", QuotaText.line(null, "com.video", 1000, mb))
    }
}
