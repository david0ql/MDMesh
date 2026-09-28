package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `device.lockscreenMessage` — set/clear the Device-Owner lock-screen info string. */
class DeviceLockscreenMessageHandler(
    private val handle: DpmHandle,
) : CommandHandler {

    override val type: String = DeviceAction.LOCKSCREEN_MESSAGE

    @Serializable
    private data class Payload(val message: String? = null)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return CommandResults.unsupported(command, "lock-screen message needs API 24+")
        }
        val msg = command.payload
            ?.let { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }
            ?.message
        handle.dpm.setDeviceOwnerLockScreenInfo(handle.admin, msg?.ifBlank { null })
        CommandResults.done(command)
    }.getOrElse { CommandResults.failed(command, it.message ?: "lockscreen message failed") }
}
