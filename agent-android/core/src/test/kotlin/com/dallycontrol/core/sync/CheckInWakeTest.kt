package com.dallycontrol.core.sync

import com.dallycontrol.core.command.CommandDispatcher
import com.dallycontrol.core.net.ResponseEnvelope
import com.dallycontrol.core.wake.WakeBridge
import com.dallycontrol.proto.AgentCheckInResponse
import com.dallycontrol.proto.ProtocolJson
import com.dallycontrol.proto.PushConfig
import com.dallycontrol.proto.PushState
import com.dallycontrol.proto.WakeConfig
import com.dallycontrol.proto.WakeReport
import com.dallycontrol.proto.WakeTrigger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake block of a check-in: what the agent tells, what it is told, and compatibility both ways. */
class CheckInWakeTest {

    private class RecordingBridge(var next: WakeReport?) : WakeBridge {
        val configs = mutableListOf<WakeConfig?>()
        override fun report(): WakeReport? = next
        override fun onConfig(config: WakeConfig?) {
            configs += config
        }
    }

    private fun coordinator(api: FakeMdmApi, bridge: WakeBridge?): CheckInCoordinator {
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
            if (bridge != null) it.wake = bridge
        }
    }

    @Test fun `the push token and what woke the agent ride the check-in`() = runTest {
        val api = FakeMdmApi()
        val bridge = RecordingBridge(
            WakeReport(
                provider = PushState.PROVIDER_FCM, token = "tok-1", state = PushState.READY, wokenBy = WakeTrigger.PUSH,
            ),
        )
        coordinator(api, bridge).runOnce()
        val sent = api.checkInRequests.last().wake!!
        assertEquals("tok-1", sent.token)
        assertEquals(WakeTrigger.PUSH, sent.wokenBy)
    }

    @Test fun `the server's schedule and push project reach the bridge, and an older server means none`() = runTest {
        val api = FakeMdmApi().apply {
            checkInResponse = ResponseEnvelope(
                status = "OK",
                data = AgentCheckInResponse(
                    wake = WakeConfig(pollMinutes = 3, push = PushConfig(provider = "fcm", projectId = "p")),
                ),
            )
        }
        val bridge = RecordingBridge(null)
        val coordinator = coordinator(api, bridge)
        coordinator.runOnce()
        assertEquals(3, bridge.configs.single()?.pollMinutes)
        assertEquals("p", bridge.configs.single()?.push?.projectId)

        api.checkInResponse = ResponseEnvelope(status = "OK", data = AgentCheckInResponse())
        coordinator.runOnce()
        assertNull(bridge.configs.last())
    }

    @Test fun `a failing bridge never breaks the check-in`() = runTest {
        val api = FakeMdmApi()
        val broken = object : WakeBridge {
            override fun report(): WakeReport? = error("no play services")
            override fun onConfig(config: WakeConfig?) = error("boom")
        }
        coordinator(api, broken).runOnce()
        assertEquals(1, api.checkInRequests.size)
        assertNull(api.checkInRequests.last().wake)
    }

    @Test fun `without a bridge the request is the one older servers already know`() = runTest {
        val api = FakeMdmApi()
        coordinator(api, null).runOnce()
        val json = ProtocolJson.json.encodeToString(
            com.dallycontrol.proto.AgentCheckInRequest.serializer(), api.checkInRequests.last(),
        )
        assertFalse(json, json.contains("\"wake\""))
    }

    @Test fun `an answer from a newer server with unknown wake fields still parses`() {
        val body = """{"protocolVersion":"1","commands":[],"wake":{"pollMinutes":4,"somethingNew":true,
            "push":{"provider":"fcm","projectId":"p","apiKey":"k","appIds":{"com.x":"1:2:android:3"},"future":1}}}"""
        val parsed = ProtocolJson.json.decodeFromString(AgentCheckInResponse.serializer(), body)
        assertEquals(4, parsed.wake?.pollMinutes)
        assertEquals("1:2:android:3", parsed.wake?.push?.appIdFor("com.x"))
        assertNull(parsed.wake?.push?.appIdFor("com.other"))
        assertTrue(parsed.commands.isEmpty())
    }
}
