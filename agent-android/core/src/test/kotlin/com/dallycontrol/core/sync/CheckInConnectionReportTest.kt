package com.dallycontrol.core.sync

import com.dallycontrol.core.command.CommandDispatcher
import com.dallycontrol.core.net.ConnectionDiagnostics
import com.dallycontrol.core.net.MemoryKeyValueStore
import com.dallycontrol.core.net.ResponseEnvelope
import com.dallycontrol.core.net.ServerEndpoints
import com.dallycontrol.proto.AgentCheckInRequest
import com.dallycontrol.proto.AgentCheckInResponse
import com.dallycontrol.proto.Capabilities
import com.dallycontrol.proto.FailureKind
import com.dallycontrol.proto.ProtocolJson
import com.dallycontrol.proto.StopReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class CheckInConnectionReportTest {
    private val endpoints = ServerEndpoints({ "https://mdm.example.com" }, MemoryKeyValueStore())
    private val connection = ConnectionDiagnostics(MemoryKeyValueStore(), endpoints)

    private fun coordinator(api: FakeMdmApi): CheckInCoordinator {
        val identity = FakeIdentity(initialId = "dev-1", initialSecret = "sek-1")
        return CheckInCoordinator(
            api = api,
            enrollment = EnrollmentManager(
                api, identity, FakeTokenProvider("t"), FakeCapabilitySource(), NoopEventSink,
            ),
            identity = identity,
            capabilitySource = FakeCapabilitySource(),
            dispatcher = CommandDispatcher(emptyList()),
            pending = PendingResults(),
            stateSource = { null },
            telemetrySource = { null },
            eventSink = NoopEventSink,
        ).also {
            it.workContext = kotlin.coroutines.EmptyCoroutineContext
            it.connection = connection
        }
    }

    @Test fun `the last network failure rides the next successful check-in, once`() = runTest {
        val api = FakeMdmApi().apply { checkInThrows = SocketTimeoutException("timeout") }
        val coordinator = coordinator(api)
        runCatching { coordinator.runOnce() }
        connection.recordReconnect()

        api.checkInThrows = null
        coordinator.runOnce()
        val sent = api.checkInRequests.last().connection!!
        assertEquals(FailureKind.TIMEOUT, sent.lastFailure!!.kind)
        assertEquals(ConnectionDiagnostics.CHANNEL_CHECKIN, sent.lastFailure!!.channel)
        assertEquals(1, sent.reconnects)
        assertEquals("mdm.example.com", sent.serverHost)

        coordinator.runOnce()
        val next = api.checkInRequests.last().connection!!
        assertNull(next.lastFailure)
        assertEquals(0, next.reconnects)
        assertTrue(connection.lastSuccessAt > 0)
    }

    @Test fun `a refused check-in is recorded as rejected`() = runTest {
        val api = FakeMdmApi().apply {
            checkInResponse = ResponseEnvelope(status = "ERROR", message = "error.agent.unauthorized")
        }
        runCatching { coordinator(api).runOnce() }
        assertEquals(FailureKind.REJECTED, connection.pendingReport().lastFailure!!.kind)
    }

    @Test fun `backup urls from the server are stored, and an older server leaves them alone`() = runTest {
        val api = FakeMdmApi().apply {
            checkInResponse = ResponseEnvelope(
                status = "OK",
                data = AgentCheckInResponse(backupUrls = listOf("https://direct.example.com", "http://no.example.com")),
            )
        }
        val coordinator = coordinator(api)
        coordinator.runOnce()
        assertEquals(listOf("https://direct.example.com"), endpoints.backups())

        api.checkInResponse = ResponseEnvelope(status = "OK", data = AgentCheckInResponse())
        coordinator.runOnce()
        assertEquals(listOf("https://direct.example.com"), endpoints.backups())

        api.checkInResponse = ResponseEnvelope(status = "OK", data = AgentCheckInResponse(backupUrls = emptyList()))
        coordinator.runOnce()
        assertTrue(endpoints.backups().isEmpty())
    }

    @Test fun `the goodbye report carries the shutdown`() = runTest {
        val api = FakeMdmApi()
        connection.recordStop(StopReason.SHUTDOWN, battery = 40)
        coordinator(api).runGoodbye()
        val stop = api.checkInRequests.last().connection!!.lastStop!!
        assertEquals(StopReason.SHUTDOWN, stop.reason)
        assertNull(stop.resumedAt)
    }

    @Test fun `the wire stays compatible both ways`() {
        // An agent with nothing to say adds no field an older server could choke on beyond the optional block.
        val json = ProtocolJson.json.encodeToString(
            AgentCheckInRequest.serializer(),
            AgentCheckInRequest(deviceId = "d", capabilities = Capabilities()),
        )
        assertFalse(json.contains("connection"))
        // A response from an older server (no backupUrls) and one from a newer server (unknown fields) both parse.
        val old = ProtocolJson.json.decodeFromString(AgentCheckInResponse.serializer(), """{"commands":[]}""")
        assertNull(old.backupUrls)
        val newer = ProtocolJson.json.decodeFromString(
            AgentCheckInResponse.serializer(), """{"commands":[],"backupUrls":["https://a.example.com"],"future":1}""",
        )
        assertEquals(listOf("https://a.example.com"), newer.backupUrls)
    }
}
