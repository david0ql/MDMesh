package com.dallycontrol.core.sync

import com.dallycontrol.core.command.handlers.DeviceWipeHandler
import com.dallycontrol.core.net.ResponseEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultAnnouncerTest {

    private val result = CommandResult(
        commandId = "77", status = CommandStatus.ACCEPTED, detail = "${DeviceWipeHandler.STARTING} flags=0",
        completedAt = "2026-11-27T10:00:00Z",
    )

    @Test
    fun `the result goes out at once on a check-in of its own, with the device's secret`() = runTest {
        val api = FakeMdmApi()
        val ok = ResultAnnouncer(api, FakeIdentity("dev-1", "sek"), FakeCapabilitySource()).announce(result)
        assertTrue(ok)
        assertEquals(1, api.checkInRequests.size)
        assertEquals("dev-1", api.checkInRequests[0].deviceId)
        assertEquals(listOf(result), api.checkInRequests[0].results)
        assertEquals("Bearer sek", api.checkInAuth[0])
    }

    @Test
    fun `it retries a few times and then gives up`() = runTest {
        val api = FakeMdmApi().apply { checkInThrows = java.io.IOException("no route") }
        val announcer = ResultAnnouncer(api, FakeIdentity("dev-1", "sek"), FakeCapabilitySource(), attempts = 3, pauseMs = 10)
        val ok = announcer.announce(result)
        assertFalse(ok)
        assertEquals(3, api.checkInRequests.size)
    }

    @Test
    fun `a rejected first try followed by an accepted one counts`() = runTest {
        val api = FakeMdmApi()
        api.checkInResponses.addLast(ResponseEnvelope(status = "ERROR", message = "busy"))
        val announcer = ResultAnnouncer(api, FakeIdentity("dev-1", "sek"), FakeCapabilitySource(), pauseMs = 10)
        val ok = announcer.announce(result)
        assertTrue(ok)
        assertEquals(2, api.checkInRequests.size)
    }

    @Test
    fun `a device that is not enrolled announces nothing`() = runTest {
        val api = FakeMdmApi()
        assertFalse(ResultAnnouncer(api, FakeIdentity(null, null), FakeCapabilitySource()).announce(result))
        assertTrue(api.checkInRequests.isEmpty())
    }
}
