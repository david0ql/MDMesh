package com.dallycontrol.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixPolicyTest {

    @Test
    fun `fused is preferred when the device has it`() {
        assertEquals(
            listOf("fused", "gps", "network"),
            FixPolicy.providers(listOf("passive", "gps", "network", "fused")) { true },
        )
    }

    @Test
    fun `a device without GMS falls back to gps then network`() {
        assertEquals(listOf("gps", "network"), FixPolicy.providers(listOf("passive", "gps", "network")) { true })
        assertEquals(listOf("network"), FixPolicy.providers(listOf("gps", "network")) { it == "network" })
        assertEquals(emptyList<String>(), FixPolicy.providers(listOf("passive")) { true })
    }

    @Test
    fun `a provider whose status check throws is skipped`() {
        assertEquals(
            listOf("gps"),
            FixPolicy.providers(listOf("fused", "gps")) { if (it == "fused") error("no such provider") else true },
        )
    }

    @Test
    fun `timeout defaults to 25 seconds and is clamped`() {
        assertEquals(25, FixPolicy.clampTimeout(null))
        assertEquals(30, FixPolicy.clampTimeout(30))
        assertEquals(5, FixPolicy.clampTimeout(1))
        assertEquals(60, FixPolicy.clampTimeout(600))
    }

    @Test
    fun `after a wait without fix the next waits are short for a while`() {
        val t0 = 1_000_000L
        assertEquals(25, FixPolicy.timeoutSec(25, t0, 0))
        assertEquals(5, FixPolicy.timeoutSec(25, t0 + 30_000, t0))
        assertEquals(25, FixPolicy.timeoutSec(25, t0 + FixPolicy.BACKOFF_MS, t0))
    }

    @Test
    fun `a very recent fix is reused`() {
        assertTrue(FixPolicy.freshEnough(1_000_000L - 10_000, 1_000_000L))
        assertFalse(FixPolicy.freshEnough(1_000_000L - 90_000, 1_000_000L))
        assertFalse(FixPolicy.freshEnough(null, 1_000_000L))
        // a fix from the future (clock change) is not trusted
        assertFalse(FixPolicy.freshEnough(2_000_000L, 1_000_000L))
    }
}
