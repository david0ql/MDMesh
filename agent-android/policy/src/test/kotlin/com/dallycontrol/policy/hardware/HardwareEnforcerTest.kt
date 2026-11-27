package com.dallycontrol.policy.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareEnforcerTest {

    private class FakeBackend(
        override var sdk: Int = 34,
        var owner: Boolean = true,
        var usbHal: Boolean = true,
    ) : HardwareBackend {
        val restrictions = linkedSetOf<String>()
        var camera = false
        var usbData = true
        var autoTime = false
        var refuse: Set<String> = emptySet()
        var platformRefuses: Set<String> = emptySet()
        var ignore: Set<String> = emptySet()
        override fun isDeviceOwner() = owner
        override fun setRestriction(key: String, on: Boolean) {
            if (key in refuse) throw SecurityException("no $key")
            check(key !in platformRefuses) { "Unsupported user restriction $key" }
            if (key in ignore) return
            if (key == "no_debugging_features" && on) adb = false // what Android does
            if (on) restrictions += key else restrictions -= key
        }
        override fun hasRestriction(key: String): Boolean? = key in restrictions
        override fun setCameraDisabled(disabled: Boolean) { camera = disabled }
        override fun isCameraDisabled() = camera
        override fun canDisableUsbData() = sdk >= 31 && usbHal
        override fun setUsbDataEnabled(enabled: Boolean) {
            check(usbHal) { "USB data signaling cannot be disabled." }
            usbData = enabled
        }
        override fun isUsbDataEnabled() = usbData
        override fun setAutoTimeRequired(required: Boolean) { autoTime = required }
        override fun isAutoTimeRequired() = autoTime
        var adb = false
        override fun isAdbEnabled() = adb
        override fun enableAdb() { adb = true }
    }

    private class Memory : OwnedTokens {
        var tokens: Set<String> = emptySet()
        override fun load() = tokens
        override fun save(tokens: Set<String>) { this.tokens = tokens }
    }

    private val all = HardwareCatalog.RULES.associate { it.key to "block" }

    @Test fun `everything blocked on a modern phone is applied and read back`() {
        val b = FakeBackend(sdk = 35)
        val out = HardwareEnforcer(b, Memory()).apply(all, listOf("no_sms"), emptySet())
        assertEquals(HardwareCatalog.RULES.map { "hardware.${it.key}" } + "restrictions.no_sms", out.keys.toList())
        assertTrue(out.toString(), out.values.all { it == "applied" })
        assertTrue(b.camera)
        assertFalse(b.usbData)
        assertFalse("Android 9+ pins the clock with the restriction, not with auto-time", b.autoTime)
        assertTrue(
            b.restrictions.containsAll(
                listOf(
                    "no_debugging_features", "no_physical_media", "no_bluetooth_sharing", "no_config_bluetooth",
                    "no_outgoing_beam", "no_near_field_communication_radio", "no_unmute_microphone", "no_airplane_mode",
                    "no_wifi_direct", "no_printing", "no_network_reset", "no_config_date_time",
                    "no_install_unknown_sources", "no_install_unknown_sources_globally", "no_config_location",
                    "no_share_location", "no_content_capture", "no_content_suggestions", "no_assist_content", "no_sms",
                ),
            ),
        )
    }

    @Test fun `an old phone reports which rules it cannot honour and since which Android`() {
        val b = FakeBackend(sdk = 27)
        val out = HardwareEnforcer(b, Memory()).apply(all, listOf("no_cellular_2g", "no_sms"), emptySet())
        assertEquals("unsupported: sdk-31", out["hardware.usbData"])
        assertEquals("unsupported: sdk-35", out["hardware.nfc"])
        assertEquals("unsupported: sdk-28", out["hardware.airplaneMode"])
        assertEquals("unsupported: sdk-33", out["hardware.wifiDirect"])
        assertEquals("unsupported: sdk-29", out["hardware.contentCapture"])
        assertEquals("unsupported: sdk-34", out["restrictions.no_cellular_2g"])
        assertEquals("applied", out["restrictions.no_sms"])
        assertEquals("applied", out["hardware.usbDebug"])
        assertEquals("before Android 9 the clock is pinned with auto-time", "applied", out["hardware.dateTime"])
        assertTrue(b.autoTime)
        assertFalse("no_config_date_time" in b.restrictions)
        assertEquals("applied", out["hardware.unknownSources"])
        assertFalse("the global variant is Android 10+", "no_install_unknown_sources_globally" in b.restrictions)
    }

    @Test fun `usb data is reported as not supported by the device when its hal cannot cut it`() {
        val b = FakeBackend(sdk = 34, usbHal = false)
        val out = HardwareEnforcer(b, Memory()).apply(mapOf("usbData" to "block"), emptyList(), emptySet())
        assertEquals("unsupported: device", out["hardware.usbData"])
        assertTrue(b.usbData)
        // Allowing it there is a no-op that must not fail.
        val allowed = HardwareEnforcer(b, Memory()).apply(mapOf("usbData" to "allow"), emptyList(), emptySet())
        assertEquals("applied", allowed["hardware.usbData"])
    }

    @Test fun `a rule that leaves the policy is undone, but only what this section set`() {
        val b = FakeBackend()
        b.restrictions += "no_config_bluetooth" // set by someone else before
        val mem = Memory()
        val e = HardwareEnforcer(b, mem)
        e.apply(mapOf("usbDebug" to "block", "camera" to "block", "usbData" to "block"), listOf("no_sms"), emptySet())
        assertEquals(setOf("no_debugging_features", "@camera", "@usbData", "no_sms"), mem.tokens)

        val out = e.apply(emptyMap(), emptyList(), emptySet())
        assertTrue(out.isEmpty())
        assertEquals(setOf("no_config_bluetooth"), b.restrictions.toSet())
        assertFalse(b.camera)
        assertTrue("USB data comes back when the rule is removed", b.usbData)
        assertTrue(mem.tokens.isEmpty())
    }

    @Test fun `lifting the debugging block gives usb debugging back only if it was on before`() {
        val b = FakeBackend().apply { adb = true }
        val mem = Memory()
        val e = HardwareEnforcer(b, mem)
        e.apply(mapOf("usbDebug" to "block"), emptyList(), emptySet())
        assertFalse(b.adb)
        e.apply(mapOf("usbDebug" to "block"), emptyList(), emptySet()) // a re-apply must not forget it was on
        assertEquals(setOf("no_debugging_features", "@adbWasOn"), mem.tokens)
        assertEquals("applied", e.apply(mapOf("usbDebug" to "allow"), emptyList(), emptySet())["hardware.usbDebug"])
        assertTrue("the technician's cable works again", b.adb)
        assertTrue(mem.tokens.isEmpty())

        val off = FakeBackend()
        val e2 = HardwareEnforcer(off, Memory())
        e2.apply(mapOf("usbDebug" to "block"), emptyList(), emptySet())
        e2.apply(emptyMap(), emptyList(), emptySet())
        assertFalse("it was off: it stays off", off.adb)
        assertFalse("no_debugging_features" in off.restrictions)
    }

    @Test fun `allow clears a restriction even when this section did not set it`() {
        val b = FakeBackend()
        b.restrictions += "no_debugging_features"
        b.camera = true
        val out = HardwareEnforcer(b, Memory())
            .apply(mapOf("usbDebug" to "allow", "camera" to "allow"), emptyList(), emptySet())
        assertEquals(mapOf("hardware.usbDebug" to "applied", "hardware.camera" to "applied"), out)
        assertTrue(b.restrictions.isEmpty())
        assertFalse(b.camera)
    }

    @Test fun `a restriction another section keeps on is not cleared`() {
        val b = FakeBackend()
        b.restrictions += listOf("no_physical_media", "no_install_unknown_sources")
        val mem = Memory().apply { tokens = setOf("no_install_unknown_sources") }
        val held = HardwareCatalog.heldByOtherSections(mapOf("usbStorage" to false), null, null, "block")
        val out = HardwareEnforcer(b, mem).apply(mapOf("physicalMedia" to "allow"), emptyList(), held)
        assertEquals("unsupported: held", out["hardware.physicalMedia"])
        assertEquals(setOf("no_physical_media", "no_install_unknown_sources"), b.restrictions.toSet())
        assertTrue("no longer this section's to undo", mem.tokens.isEmpty())
    }

    @Test fun `a refused or ignored restriction is reported as failed`() {
        val b = FakeBackend().apply { refuse = setOf("no_printing"); ignore = setOf("no_airplane_mode") }
        val mem = Memory()
        val out = HardwareEnforcer(b, mem)
            .apply(mapOf("printing" to "block", "airplaneMode" to "block", "usbDebug" to "block"), emptyList(), setOf())
        assertEquals("failed: no no_printing", out["hardware.printing"])
        assertEquals("failed: not-effective", out["hardware.airplaneMode"])
        assertEquals("applied", out["hardware.usbDebug"])
        assertFalse("no_printing" in mem.tokens)
    }

    @Test fun `a restriction this android build does not let an admin set is unsupported, not a failure`() {
        val b = FakeBackend(sdk = 35).apply { platformRefuses = setOf("no_near_field_communication_radio") }
        val out = HardwareEnforcer(b, Memory())
            .apply(mapOf("nfc" to "block", "camera" to "block"), emptyList(), emptySet())
        assertEquals("unsupported: platform", out["hardware.nfc"])
        assertEquals("applied", out["hardware.camera"])
    }

    @Test fun `unknown rules, values and restrictions are refused`() {
        val b = FakeBackend()
        val out = HardwareEnforcer(b, Memory()).apply(
            mapOf("teleport" to "block", "camera" to "maybe"), listOf("no_factory_reset", "rm -rf"), emptySet(),
        )
        assertTrue(out.values.all { it == "unsupported: unknown" })
        assertEquals(4, out.size)
        assertTrue(b.restrictions.isEmpty())
        assertFalse(b.camera)
    }

    @Test fun `without device owner nothing is touched`() {
        val b = FakeBackend(owner = false)
        val out = HardwareEnforcer(b, Memory()).apply(mapOf("camera" to "block"), listOf("no_sms"), emptySet())
        val why = "unsupported: not-device-owner"
        assertEquals(mapOf("hardware.camera" to why, "restrictions.no_sms" to why), out)
        assertFalse(b.camera)
    }

    @Test fun `a free restriction wins over an allow of the same key and every catalog key is unique`() {
        assertEquals(HardwareCatalog.RULES.size, HardwareCatalog.RULES.map { it.key }.toSet().size)
        val ruleTokens = HardwareCatalog.RULES.flatMap { r -> r.tokens.map { it.key } }
        assertTrue("free list and rules are disjoint", HardwareCatalog.FREE_RESTRICTIONS.keys.none { it in ruleTokens })
        HardwareCatalog.RULES.forEach { assertNotNull(HardwareCatalog.rule(it.key)) }
    }
}
