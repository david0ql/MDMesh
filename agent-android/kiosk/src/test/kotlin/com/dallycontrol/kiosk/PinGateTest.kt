package com.dallycontrol.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGateTest {
    private var clock = 1_000_000L
    private val store = InMemoryPinAttemptStore()
    private val gate = PinGate(store) { clock }

    @Test
    fun `no secret configured never passes, whatever is typed`() {
        assertEquals(PinResult.NoSecret, gate.verify(null, ""))
        assertEquals(PinResult.NoSecret, gate.verify("", ""))
        assertEquals(PinResult.NoSecret, gate.verify("  ", "  "))
    }

    @Test
    fun `the right secret passes and is compared exactly`() {
        assertEquals(PinResult.Ok, gate.verify("s3creta", "s3creta"))
        assertTrue(gate.verify("s3creta", "S3creta") is PinResult.Wrong)
        assertTrue(gate.verify("s3creta", "s3creta ") is PinResult.Wrong)
        assertFalse(PinGate.matches("1234", "12345"))
        assertFalse(PinGate.matches("1234", ""))
    }

    @Test
    fun `two free mistakes, the third starts a 30 second wait during which even the right secret is refused`() {
        assertEquals(PinResult.Wrong(1, 0), gate.verify("1234", "0000"))
        assertEquals(PinResult.Wrong(2, 0), gate.verify("1234", "0001"))
        assertEquals(PinResult.Wrong(3, 30_000), gate.verify("1234", "0002"))
        assertEquals(PinResult.Locked(30_000), gate.verify("1234", "1234"))
        clock += 29_999
        assertEquals(PinResult.Locked(1), gate.verify("1234", "1234"))
        clock += 1
        assertEquals(PinResult.Ok, gate.verify("1234", "1234"))
        assertEquals(0, store.failures)
    }

    @Test
    fun `the wait doubles with each further mistake and is capped at 30 minutes`() {
        assertEquals(0L, PinGate.lockMsAfter(2))
        assertEquals(30_000L, PinGate.lockMsAfter(3))
        assertEquals(60_000L, PinGate.lockMsAfter(4))
        assertEquals(120_000L, PinGate.lockMsAfter(5))
        assertEquals(PinGate.MAX_LOCK_MS, PinGate.lockMsAfter(12))
        assertEquals(PinGate.MAX_LOCK_MS, PinGate.lockMsAfter(1_000))
        repeat(3) { gate.verify("1234", "x") }
        clock += 30_000
        assertEquals(PinResult.Wrong(4, 60_000), gate.verify("1234", "x"))
    }

    @Test
    fun `the wait survives a restart and a clock set back cannot stretch it`() {
        repeat(3) { gate.verify("1234", "x") }
        val afterRestart = PinGate(store) { clock + 10_000 }
        assertEquals(PinResult.Locked(20_000), afterRestart.verify("1234", "1234"))
        val clockSetBack = PinGate(store) { clock - 10 * 24 * 3_600_000L }
        assertEquals(PinGate.MAX_LOCK_MS, clockSetBack.remainingLockMs())
    }
}
