package com.dallycontrol.policy.frp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TamperRestrictionsTest {

    @Test
    fun `restriction keys mirror the android UserManager constants`() {
        assertEquals("no_factory_reset", TamperRestrictions.DISALLOW_FACTORY_RESET)
        assertEquals("no_safe_boot", TamperRestrictions.DISALLOW_SAFE_BOOT)
        assertEquals("no_debugging_features", TamperRestrictions.DISALLOW_DEBUGGING_FEATURES)
        assertEquals("no_oem_unlock", TamperRestrictions.DISALLOW_OEM_UNLOCK)
    }

    @Test
    fun `every wanted rule becomes its restriction`() {
        val plan = TamperRestrictions.plan(
            mapOf("factoryReset" to true, "safeBoot" to true, "debugging" to true, "oemUnlock" to false), 34,
        )
        assertEquals(setOf("no_factory_reset", "no_safe_boot", "no_debugging_features"), plan.restrictions)
        assertTrue(plan.unsupported.isEmpty())
    }

    @Test
    fun `oem unlock has its own restriction on android 7 and 8, the reset one from 9, none on 6`() {
        assertEquals(setOf("no_oem_unlock"), TamperRestrictions.plan(mapOf("oemUnlock" to true), 24).restrictions)
        assertEquals(setOf("no_oem_unlock"), TamperRestrictions.plan(mapOf("oemUnlock" to true), 27).restrictions)
        assertEquals(setOf("no_factory_reset"), TamperRestrictions.plan(mapOf("oemUnlock" to true), 28).restrictions)
        assertEquals(setOf("no_factory_reset"), TamperRestrictions.plan(mapOf("oemUnlock" to true), 36).restrictions)
        val old = TamperRestrictions.plan(mapOf("oemUnlock" to true), 23)
        assertTrue(old.restrictions.isEmpty())
        assertEquals(listOf("oemUnlock"), old.unsupported)
    }

    @Test
    fun `the effective rules come from what the device holds`() {
        assertEquals(
            mapOf("factoryReset" to true, "safeBoot" to false, "debugging" to false, "oemUnlock" to true),
            TamperRestrictions.effective(setOf("no_factory_reset"), 34),
        )
        assertEquals(
            mapOf("factoryReset" to false, "safeBoot" to true, "debugging" to false, "oemUnlock" to true),
            TamperRestrictions.effective(setOf("no_safe_boot", "no_oem_unlock"), 26),
        )
    }
}
