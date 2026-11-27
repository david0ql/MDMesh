package com.dallycontrol.core.lost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LostModeTest {

    private class Device : LostModeDevice {
        val calls = ArrayList<String>()
        var info: String? = "old"
        var tracking = 15
        var mode = "passive"
        var maxFailed = 0
        var wiped = 0
        var failLock = false
        override fun setLockScreenInfo(text: String?) { info = text }
        override fun lockNow() { if (failLock) throw SecurityException("no admin"); calls.add("lock") }
        override fun ring(durationMs: Long) { calls.add("ring($durationMs)") }
        override fun stopRing() { calls.add("stopRing") }
        override fun trackingMinutes() = tracking
        override fun setTrackingMinutes(minutes: Int) { tracking = minutes }
        override fun locationMode() = mode
        override fun setLocationMode(mode: String) { this.mode = mode }
        override fun setMaxFailedUnlocksForWipe(attempts: Int) { maxFailed = attempts }
        override fun wipeKeepingProtection() { wiped++ }
    }

    private var disk: String? = null
    private var clock = 1_000_000L
    private val device = Device()
    private fun lost() = LostMode(device, { disk }, { disk = it }, { clock })

    private val day = 24 * 3600_000L

    @Test
    fun `starting lost mode locks, shows who to call, rings and tracks every minute`() {
        val errors = lost().apply(
            LostModePayload(message = "Equipo de ACME. Devuélvalo.", phone = "+57 300 1234567", ringSeconds = 60, wipeAfterFailedUnlocks = 10),
        )
        assertTrue(errors.isEmpty())
        assertEquals("Equipo de ACME. Devuélvalo.\nTel: +57 300 1234567", device.info)
        assertEquals(listOf("lock", "ring(60000)"), device.calls)
        assertEquals(1, device.tracking)
        assertEquals("active", device.mode)
        assertEquals(10, device.maxFailed)
        assertTrue(lost().isActive())
        assertEquals(10, lost().status()!!.wipeAfterFailedUnlocks)
    }

    @Test
    fun `ending lost mode puts back what the policy had`() {
        lost().apply(LostModePayload(message = "x", trackMinutes = 2, wipeAfterFailedUnlocks = 5))
        assertEquals(2, device.tracking)
        lost().apply(LostModePayload(enabled = false))
        assertNull(device.info)
        assertEquals(15, device.tracking)
        assertEquals("passive", device.mode)
        assertEquals(0, device.maxFailed)
        assertFalse(lost().isActive())
        assertNull(lost().status())
    }

    @Test
    fun `it survives a restart and a second order keeps the original values to restore`() {
        lost().apply(LostModePayload(message = "a"))
        // A new process: same disk.
        lost().apply(LostModePayload(message = "b", trackMinutes = 5))
        assertEquals(5, device.tracking)
        lost().apply(LostModePayload(enabled = false))
        assertEquals(15, device.tracking)
        assertEquals("passive", device.mode)
    }

    @Test
    fun `a policy applied while lost does not slow the tracking and is restored afterwards`() {
        val lost = lost()
        assertEquals(30, lost.trackingFromPolicy(30)) // not lost: the policy's value passes through
        assertEquals("passive", lost.locationModeFromPolicy("passive"))
        lost.apply(LostModePayload(message = "x"))
        assertEquals(1, lost.trackingFromPolicy(30))
        assertEquals("active", lost.locationModeFromPolicy("passive"))
        lost.apply(LostModePayload(enabled = false))
        assertEquals(30, device.tracking)
    }

    @Test
    fun `a step that fails is named and the rest still runs`() {
        device.failLock = true
        val errors = lost().apply(LostModePayload(message = "x", ringSeconds = 10))
        assertEquals(1, errors.size)
        assertTrue(errors[0].startsWith("lock: SecurityException"))
        assertEquals(listOf("ring(10000)"), device.calls)
        assertEquals("x", device.info)
    }

    @Test
    fun `silent for the configured days wipes, a report in between starts the count again`() {
        val lost = lost()
        lost.apply(LostModePayload(message = "x", wipeIfSilentDays = 3))
        clock += 2 * day
        assertFalse(lost.wipeIfSilentTooLong())
        lost.contact() // reached the server
        clock += 2 * day
        assertFalse(lost.wipeIfSilentTooLong())
        clock += day
        assertTrue(lost.wipeIfSilentTooLong())
        assertEquals(1, device.wiped)
    }

    @Test
    fun `no silent wipe without the option or outside lost mode`() {
        val lost = lost()
        clock += 100 * day
        assertFalse(lost.wipeIfSilentTooLong())
        lost.apply(LostModePayload(message = "x"))
        clock += 100 * day
        assertFalse(lost.wipeIfSilentTooLong())
        assertEquals(0, device.wiped)
    }

    @Test
    fun `the lock screen text is the message and the phone, or nothing`() {
        assertEquals("Tel: 123", LostMode.lockScreenText(LostModePayload(phone = " 123 ")))
        assertEquals("Hola", LostMode.lockScreenText(LostModePayload(message = "Hola")))
        assertNull(LostMode.lockScreenText(LostModePayload()))
    }
}
