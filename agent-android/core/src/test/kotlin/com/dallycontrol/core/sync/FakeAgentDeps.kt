package com.dallycontrol.core.sync

import com.dallycontrol.core.capability.CapabilitySource
import com.dallycontrol.core.net.MdmApi
import com.dallycontrol.core.net.ResponseEnvelope
import com.dallycontrol.core.store.DeviceIdentity
import com.dallycontrol.core.store.EnrollTokenProvider
import com.dallycontrol.proto.AgentCheckInRequest
import com.dallycontrol.proto.AgentCheckInResponse
import com.dallycontrol.proto.AgentEnrollRequest
import com.dallycontrol.proto.AgentEnrollResponse
import com.dallycontrol.proto.AgentInfo
import com.dallycontrol.proto.Capabilities
import com.dallycontrol.proto.CapabilityMatrix
import com.dallycontrol.proto.DeviceInfo
import kotlinx.coroutines.CompletableDeferred

/** Shared fakes for the sync/enroll unit tests. Pure JVM — no Android. */

/** No-op event sink for tests that don't assert on events. */
object NoopEventSink : com.dallycontrol.core.telemetry.EventSink {
    override fun record(type: String, detail: String?) {}
    override fun drain(): List<com.dallycontrol.proto.TelemetryEventDto> = emptyList()
    override fun restore(events: List<com.dallycontrol.proto.TelemetryEventDto>) {}
}

class FakeIdentity(
    initialId: String? = null,
    initialSecret: String? = null,
) : DeviceIdentity {
    private var idValue: String? = initialId
    private var secretValue: String? = initialSecret
    var saveCount = 0
        private set

    override suspend fun current(): String? = idValue
    override suspend fun secret(): String? = secretValue
    override suspend fun saveCredentials(id: String, secret: String?) {
        idValue = id
        secretValue = secret
        saveCount++
    }
}

class FakeTokenProvider(private val token: String?) : EnrollTokenProvider {
    override suspend fun token(): String? = token
}

class FakeCapabilitySource(
    private val capabilities: Capabilities = Capabilities(policy = listOf("wifi")),
) : CapabilitySource {
    override fun matrix(deviceId: String): CapabilityMatrix = CapabilityMatrix(
        agent = AgentInfo(version = "0.1.0", packageName = "com.dallycontrol.agent"),
        device = DeviceInfo(id = deviceId, androidSdkInt = 34, isDeviceOwner = true),
        capabilities = capabilities,
    )
}

/** Records calls and returns scripted responses (or throws). */
class FakeMdmApi : MdmApi {
    var enrollResponse: ResponseEnvelope<AgentEnrollResponse> =
        ResponseEnvelope(status = "OK", data = AgentEnrollResponse(deviceId = "srv-1", deviceSecret = "sek-1"))
    var checkInResponse: ResponseEnvelope<AgentCheckInResponse> =
        ResponseEnvelope(status = "OK", data = AgentCheckInResponse())
    var checkInThrows: Throwable? = null

    /** Scripted per-call responses, consumed in order; [checkInResponse] answers once exhausted. */
    val checkInResponses = ArrayDeque<ResponseEnvelope<AgentCheckInResponse>>()

    /** When set, calls suspend until the gate completes — lets tests hold a request in flight. */
    var enrollGate: CompletableDeferred<Unit>? = null
    var checkInGate: CompletableDeferred<Unit>? = null

    val enrollRequests = mutableListOf<AgentEnrollRequest>()
    val checkInRequests = mutableListOf<AgentCheckInRequest>()
    val checkInAuth = mutableListOf<String>()

    override suspend fun enroll(request: AgentEnrollRequest): ResponseEnvelope<AgentEnrollResponse> {
        enrollRequests += request
        enrollGate?.await()
        return enrollResponse
    }

    override suspend fun checkIn(
        authorization: String,
        request: AgentCheckInRequest,
    ): ResponseEnvelope<AgentCheckInResponse> {
        checkInAuth += authorization
        checkInRequests += request
        checkInGate?.await()
        checkInThrows?.let { throw it }
        return checkInResponses.removeFirstOrNull() ?: checkInResponse
    }
}
