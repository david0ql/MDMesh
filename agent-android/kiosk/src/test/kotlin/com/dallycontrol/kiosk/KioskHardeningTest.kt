package com.dallycontrol.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskHardeningTest {

    @Test
    fun `lock task up to Android 15, soft kiosk from Android 16`() {
        assertFalse(KioskHardening.softKiosk(28))
        assertFalse(KioskHardening.softKiosk(35))
        assertTrue(KioskHardening.softKiosk(36))
        assertTrue(KioskHardening.softKiosk(37))
    }

    @Test
    fun `in-task starts are blocked by lock task exactly where Settings cannot be suspended - Android 14 and 15`() {
        assertFalse(KioskHardening.blockInTaskStarts(28))
        assertFalse(KioskHardening.blockInTaskStarts(33))
        assertTrue(KioskHardening.blockInTaskStarts(34))
        assertTrue(KioskHardening.blockInTaskStarts(35))
        assertFalse("no lock task on the soft kiosk", KioskHardening.blockInTaskStarts(36))
    }

    @Test
    fun `soft kiosk closes the status bar unless the policy explicitly allows notifications`() {
        assertTrue(KioskHardening.statusBarDisabled(null))
        assertTrue(KioskHardening.statusBarDisabled(false))
        assertFalse(KioskHardening.statusBarDisabled(true))
    }

    @Test
    fun `every kiosk blocks safe mode and new users, newer Android also user switching and crash dialogs`() {
        assertEquals(setOf("no_safe_boot", "no_add_user"), KioskHardening.restrictions(26))
        assertEquals(
            setOf("no_safe_boot", "no_add_user", "no_user_switch", "no_system_error_dialogs"),
            KioskHardening.restrictions(28),
        )
        assertEquals(KioskHardening.restrictions(28), KioskHardening.restrictions(36))
    }

    private val launchable =
        listOf("com.work.app", "com.android.settings", "com.android.chrome", "com.android.vending", "com.oem.launcher")
    private val settings = listOf("com.android.settings", "com.android.settings.intelligence")
    private val protected = listOf("com.dallycontrol.agent", "com.oem.launcher")

    @Test
    fun `single-app lock task - only the Settings packages are suspended`() {
        val allowed = listOf("com.work.app")
        val plan = KioskHardening.suspendPlan(soft = false, launchable, settings, allowed, protected)
        assertEquals(setOf("com.android.settings", "com.android.settings.intelligence"), plan)
    }

    @Test
    fun `multi-app soft kiosk - everything outside the kiosk is suspended, allowed and protected apps are not`() {
        val plan = KioskHardening.suspendPlan(
            soft = true, launchable, settings, allowed = listOf("com.work.app", "com.android.chrome"), protected,
        )
        assertEquals(setOf("com.android.settings", "com.android.settings.intelligence", "com.android.vending"), plan)
    }

    @Test
    fun `a policy that allows Settings itself keeps it usable`() {
        val allowed = listOf("com.android.settings")
        val plan = KioskHardening.suspendPlan(soft = true, launchable, settings, allowed, protected)
        assertFalse("com.android.settings" in plan)
        assertTrue("com.android.settings.intelligence" in plan)
    }
}
