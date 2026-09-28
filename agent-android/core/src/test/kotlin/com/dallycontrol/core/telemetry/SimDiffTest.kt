package com.dallycontrol.core.telemetry

import com.dallycontrol.proto.EventType
import com.dallycontrol.proto.SimSlotDto
import com.dallycontrol.proto.SimStateDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimDiffTest {
    private fun ready(carrier: String, number: String, sub: Int = 1): SimStateDto {
        val slots = listOf(SimSlotDto(slot = 0, carrier = carrier, number = number, subscriptionId = sub))
        return SimStateDto(SimDiff.READY, slots, SimDiff.fingerprint(slots))
    }
    private val absent = SimStateDto(SimDiff.ABSENT)
    private val unknown = SimStateDto(SimDiff.UNKNOWN)

    @Test fun `first observation is only the baseline`() {
        assertTrue(SimDiff.events(null, ready("Claro", "+573001112233")).isEmpty())
    }

    @Test fun `removed, inserted and swapped cards`() {
        val claro = ready("Claro", "+573001112233")
        assertEquals(listOf(EventType.SIM_REMOVED), SimDiff.events(claro, absent).map { it.type })
        assertEquals("was Claro +573001112233", SimDiff.events(claro, absent).single().detail)
        assertEquals(listOf(EventType.SIM_INSERTED), SimDiff.events(absent, claro).map { it.type })
        val movistar = ready("Movistar", "+573159998877", sub = 2)
        assertEquals(listOf(EventType.SIM_CHANGED), SimDiff.events(claro, movistar).map { it.type })
        assertTrue(SimDiff.events(claro, ready("Claro", "+573001112233")).isEmpty())
    }

    @Test fun `a new number on the same card is a change`() {
        assertEquals(listOf(EventType.SIM_CHANGED), SimDiff.events(ready("Claro", "+573001112233"), ready("Claro", "+573004445566")).map { it.type })
    }

    @Test fun `modem noise is ignored and never becomes the baseline`() {
        val claro = ready("Claro", "+573001112233")
        assertTrue(SimDiff.events(claro, unknown).isEmpty())
        assertTrue(SimDiff.events(unknown, claro).isEmpty())
        assertFalse(SimDiff.isBaseline(unknown))
        assertTrue(SimDiff.isBaseline(absent))
    }

    @Test fun `fingerprint is order independent and changes with the card`() {
        val a = SimSlotDto(slot = 0, carrier = "Claro", subscriptionId = 1)
        val b = SimSlotDto(slot = 1, carrier = "Tigo", subscriptionId = 2)
        assertEquals(SimDiff.fingerprint(listOf(a, b)), SimDiff.fingerprint(listOf(b, a)))
        assertNotEquals(SimDiff.fingerprint(listOf(a)), SimDiff.fingerprint(listOf(a.copy(subscriptionId = 3))))
    }
}
