package com.dallycontrol.core.sync

import com.dallycontrol.core.capability.CapabilitySource
import com.dallycontrol.core.net.MdmApi
import com.dallycontrol.core.store.DeviceIdentity
import com.dallycontrol.proto.AgentCheckInRequest
import com.dallycontrol.proto.CommandResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends one command result to the server right now, on a check-in of its own, instead of leaving it in the buffer
 * for the next cycle. For the result that cannot wait: the announcement of a wipe, sent before the device erases
 * itself. A few short tries, then it gives up — the caller goes on either way.
 */
class ResultAnnouncer(
    private val api: MdmApi,
    private val identity: DeviceIdentity,
    private val capabilitySource: CapabilitySource,
    private val attempts: Int = 3,
    private val timeoutMs: Long = 8_000,
    private val pauseMs: Long = 1_500,
) {
    /** @return true when the server acknowledged the result. */
    suspend fun announce(result: CommandResult): Boolean {
        val deviceId = identity.current()?.takeIf { it.isNotBlank() }
        var told = false
        if (deviceId != null) {
            val authorization = "Bearer ${identity.secret().orEmpty()}"
            val request = AgentCheckInRequest(
                deviceId = deviceId,
                capabilities = capabilitySource.matrix(deviceId).capabilities,
                results = listOf(result),
            )
            var tries = 0
            while (!told && tries < attempts) {
                if (tries > 0) delay(pauseMs)
                tries++
                told = withTimeoutOrNull(timeoutMs) {
                    runCatching { api.checkIn(authorization, request).isOk }.getOrDefault(false)
                } ?: false
            }
        }
        return told
    }
}
