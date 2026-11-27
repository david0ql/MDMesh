package com.dallycontrol.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Asserts against the real `LOCK_TASK_FEATURE_*` integer values (compile-time constants,
 * inlined by the Kotlin compiler): SYSTEM_INFO=1, NOTIFICATIONS=2, HOME=4, OVERVIEW=8,
 * GLOBAL_ACTIONS=16, KEYGUARD=32.
 */
class KioskFeaturesTest {

    @Test
    fun `Android 11 to 15 add the block-in-task-starts flag, single-app and multi-app alike`() {
        // 64 = LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
        assertEquals(16, lockTaskFeatures(KioskToggles(), sdk = 29))
        assertEquals(16 or 64, lockTaskFeatures(KioskToggles(), sdk = 30))
        assertEquals(16 or 64, lockTaskFeatures(KioskToggles(), sdk = 35))
        assertEquals(64, lockTaskFeatures(KioskToggles(lockButtons = true), sdk = 35))
        val everything =
            KioskToggles(home = true, recents = true, notifications = true, systemInfo = true, keyguard = true)
        assertEquals(63 or 64, lockTaskFeatures(everything, sdk = 35))
        assertEquals("unused on the soft kiosk", 16, lockTaskFeatures(KioskToggles(), sdk = 36))
    }

    @Test
    fun `all null yields global actions only`() {
        assertEquals(16, lockTaskFeatures(KioskToggles()))
    }

    @Test
    fun `enabling toggles adds their flags plus global actions`() {
        val f = lockTaskFeatures(
            KioskToggles(home = true, recents = true, notifications = true, systemInfo = true, keyguard = true),
        )
        // 4 | 8 | 2 | 1 | 32 | 16 (global actions still on, lockButtons not set)
        assertEquals(63, f)
    }

    @Test
    fun `lockButtons removes the power menu`() {
        assertEquals(0, lockTaskFeatures(KioskToggles(lockButtons = true)))
        // with other toggles, global actions is still removed
        assertEquals(4, lockTaskFeatures(KioskToggles(home = true, lockButtons = true)))
    }
}
