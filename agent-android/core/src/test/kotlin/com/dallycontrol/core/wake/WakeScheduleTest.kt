package com.dallycontrol.core.wake

import com.dallycontrol.core.net.MemoryKeyValueStore
import com.dallycontrol.proto.PushConfig
import com.dallycontrol.proto.WakeConfig
import com.dallycontrol.proto.WakeTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeScheduleTest {
    private val min = 60_000L

    @Test
    fun `without a policy the base pace is five minutes`() {
        val plan = WakeSchedule.plan(null, lastActivityAt = 0, now = 1_000 * min)
        assertEquals(5 * min, plan.delayMs)
        assertFalse(plan.fast)
    }

    @Test
    fun `activity on the phone opens a fast window that closes on its own`() {
        val t0 = 1_000 * min
        assertEquals(WakeSchedule.Plan(2 * min, fast = true), WakeSchedule.plan(null, t0, t0))
        assertEquals(WakeSchedule.Plan(2 * min, fast = true), WakeSchedule.plan(null, t0, t0 + 9 * min))
        assertEquals(WakeSchedule.Plan(5 * min, fast = false), WakeSchedule.plan(null, t0, t0 + 10 * min))
    }

    @Test
    fun `the policy sets both paces and the window`() {
        val cfg = WakeConfig(pollMinutes = 3, fastPollMinutes = 1, fastWindowMinutes = 30)
        val t0 = 500 * min
        assertEquals(WakeSchedule.Plan(1 * min, fast = true), WakeSchedule.plan(cfg, t0, t0 + 29 * min))
        assertEquals(WakeSchedule.Plan(3 * min, fast = false), WakeSchedule.plan(cfg, t0, t0 + 31 * min))
        // Window 0 switches the fast pace off.
        assertFalse(WakeSchedule.plan(WakeConfig(fastWindowMinutes = 0), t0, t0).fast)
    }

    @Test
    fun `values out of range are pulled back in and the fast pace is never slower than the base`() {
        assertEquals(15, WakeSchedule.pollMinutes(WakeConfig(pollMinutes = 600)))
        assertEquals(1, WakeSchedule.pollMinutes(WakeConfig(pollMinutes = 0)))
        assertEquals(1, WakeSchedule.fastMinutes(WakeConfig(pollMinutes = 1, fastPollMinutes = 4)))
        assertEquals(60, WakeSchedule.windowMinutes(WakeConfig(fastWindowMinutes = 999)))
    }

    @Test
    fun `a clock that jumped back does not leave the fast pace on forever`() {
        val plan = WakeSchedule.plan(null, lastActivityAt = 2_000 * min, now = 1_000 * min)
        assertFalse(plan.fast)
    }

    @Test
    fun `state survives a restart and the first trigger wins until reported`() {
        val disk = MemoryKeyValueStore()
        var now = 100 * min
        val state = WakeState(disk) { now }
        assertNull(state.config)
        state.config = WakeConfig(pollMinutes = 2, push = PushConfig(provider = "fcm", projectId = "p", appIds = mapOf("*" to "1:2:android:3")))
        state.pushToken = "tok"
        state.noteActivity()

        val reborn = WakeState(disk) { now }
        assertEquals(2, reborn.config?.pollMinutes)
        assertEquals("1:2:android:3", reborn.config?.push?.appIdFor("com.any"))
        assertEquals("tok", reborn.pushToken)
        assertTrue(reborn.plan().fast)
        now += 11 * min
        assertEquals(WakeSchedule.Plan(2 * min, fast = false), reborn.plan())

        reborn.markTrigger(WakeTrigger.PUSH)
        reborn.markTrigger(WakeTrigger.ALARM)
        assertEquals(WakeTrigger.PUSH, reborn.takeTrigger())
        assertNull(reborn.takeTrigger())

        reborn.config = null
        assertNull(WakeState(disk) { now }.config)
    }
}
