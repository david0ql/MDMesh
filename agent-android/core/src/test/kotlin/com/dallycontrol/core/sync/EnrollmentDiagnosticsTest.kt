package com.dallycontrol.core.sync

import com.dallycontrol.core.enroll.EnrollFailure
import com.dallycontrol.core.enroll.EnrollIdentitySource
import com.dallycontrol.core.enroll.EnrollJournal
import com.dallycontrol.core.net.MdmApi
import com.dallycontrol.core.net.ResponseEnvelope
import com.dallycontrol.proto.AgentCheckInRequest
import com.dallycontrol.proto.AgentCheckInResponse
import com.dallycontrol.proto.AgentEnrollRequest
import com.dallycontrol.proto.AgentEnrollResponse
import com.dallycontrol.proto.EnrollDiagnostics
import com.dallycontrol.proto.ProtocolJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** What an enroll call carries besides the token (identity, diagnostics) and what it leaves in the journal. */
class EnrollmentDiagnosticsTest {

    private class FakeJournal : EnrollJournal {
        var attempts = 0
        var lastFailure: EnrollFailure? = null
        var succeeded = false
        override fun beginAttempt(): EnrollDiagnostics {
            attempts++
            return EnrollDiagnostics(path = "qr-legacy", attempts = attempts, lastError = lastFailure?.code)
        }
        override fun failed(failure: EnrollFailure) { lastFailure = failure }
        override fun succeeded() { succeeded = true; lastFailure = null }
    }

    /** Fails with [error] until [failures] calls were made, then answers like the real server. */
    private class FlakyApi(private var failures: Int, private val error: Throwable) : MdmApi {
        val requests = mutableListOf<AgentEnrollRequest>()
        override suspend fun enroll(request: AgentEnrollRequest): ResponseEnvelope<AgentEnrollResponse> {
            requests += request
            if (failures-- > 0) throw error
            return ResponseEnvelope(
                status = "OK",
                data = AgentEnrollResponse(deviceId = "srv-1", deviceSecret = "sek-1"),
            )
        }
        override suspend fun enrollReport(request: AgentEnrollRequest) =
            ResponseEnvelope<AgentEnrollResponse>(status = "OK")

        override suspend fun checkIn(authorization: String, request: AgentCheckInRequest) =
            ResponseEnvelope(status = "OK", data = AgentCheckInResponse())
    }

    private fun manager(
        api: MdmApi,
        journal: EnrollJournal,
        identity: EnrollIdentitySource = EnrollIdentitySource { null to null },
    ) =
        EnrollmentManager(
            api, FakeIdentity(), FakeTokenProvider("tok-123"), FakeCapabilitySource(), NoopEventSink,
            HardwareIdSource { "hw-1" }, journal, identity,
        )

    @Test
    fun `the enroll request carries serial, imei and the provisioning path`() = runTest {
        val api = FlakyApi(0, IllegalStateException())
        manager(api, FakeJournal()) { "ZY32FCBNM5" to "356938035643809" }.ensureEnrolled()

        val sent = api.requests.single()
        assertEquals("ZY32FCBNM5", sent.serial)
        assertEquals("356938035643809", sent.imei)
        assertEquals("hw-1", sent.hardwareId)
        assertEquals("qr-legacy", sent.diagnostics?.path)
        assertEquals(1, sent.diagnostics?.attempts)
    }

    @Test
    fun `a failure is written to the journal and reported with the next attempt`() = runTest {
        val journal = FakeJournal()
        val api = FlakyApi(1, UnknownHostException("mdm.example.com"))
        val manager = manager(api, journal)

        val first = runCatching { manager.ensureEnrolled() }
        assertTrue("the first attempt fails", first.exceptionOrNull() is UnknownHostException)
        assertEquals(EnrollFailure.NO_NETWORK, journal.lastFailure?.code)
        assertFalse(journal.succeeded)

        assertEquals("srv-1", manager.ensureEnrolled())
        assertEquals(2, api.requests.size)
        assertEquals("the same token is presented again", "tok-123", api.requests[1].enrollToken)
        assertEquals(EnrollFailure.NO_NETWORK, api.requests[1].diagnostics?.lastError)
        assertEquals(2, api.requests[1].diagnostics?.attempts)
        assertTrue(journal.succeeded)
        assertNull(journal.lastFailure)
    }

    @Test
    fun `a server rejection is journaled with its reason`() = runTest {
        val journal = FakeJournal()
        val api = FakeMdmApi().apply {
            enrollResponse = ResponseEnvelope(status = "ERROR", message = "error.agent.token.expired")
        }
        val result = runCatching { manager(api, journal).ensureEnrolled() }
        assertTrue(result.exceptionOrNull() is EnrollmentException)
        assertEquals(EnrollFailure.TOKEN_EXPIRED, journal.lastFailure?.code)
        assertTrue(journal.lastFailure!!.isTerminal)
    }

    @Test
    fun `waiting for the token is not journaled as a failure`() = runTest {
        val journal = FakeJournal()
        val manager = EnrollmentManager(
            FakeMdmApi(), FakeIdentity(), FakeTokenProvider(null), FakeCapabilitySource(), NoopEventSink,
            HardwareIdSource { "hw-1" }, journal,
        )
        val result = runCatching { manager.ensureEnrolled() }
        assertTrue(result.exceptionOrNull() is EnrollmentException)
        assertNull("no failure to report with the enrollment that follows", journal.lastFailure)
    }

    @Test
    fun `a journal or identity source that throws never blocks enrollment`() = runTest {
        val broken = object : EnrollJournal {
            override fun beginAttempt(): EnrollDiagnostics? = error("disk full")
            override fun failed(failure: EnrollFailure) = error("disk full")
            override fun succeeded() = error("disk full")
        }
        val api = FlakyApi(0, IllegalStateException())
        assertEquals("srv-1", manager(api, broken) { error("no permission") }.ensureEnrolled())
        assertNull(api.requests.single().serial)
        assertNull(api.requests.single().diagnostics)
    }

    @Test
    fun `an agent without the new fields sends the same body as before (older servers and agents stay compatible)`() {
        val json = ProtocolJson.json.encodeToString(
            AgentEnrollRequest(
                enrollToken = "t",
                agent = com.dallycontrol.proto.AgentInfo(version = "0.9.8", packageName = "p"),
                device = com.dallycontrol.proto.DeviceInfo(id = "", androidSdkInt = 30, isDeviceOwner = true),
                capabilities = com.dallycontrol.proto.Capabilities(),
            ),
        )
        assertFalse(json.contains("serial"))
        assertFalse(json.contains("diagnostics"))
        assertFalse(json.contains("imei"))
    }
}
