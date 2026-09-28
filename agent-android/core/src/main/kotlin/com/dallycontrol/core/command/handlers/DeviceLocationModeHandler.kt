package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.core.location.LocationModeStore
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `device.locationMode` — switch location capture between battery-saving "passive" and "active". */
class DeviceLocationModeHandler(
    private val store: LocationModeStore,
) : CommandHandler {

    override val type: String = DeviceAction.LOCATION_MODE

    @Serializable
    private data class Payload(val mode: String)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val p = command.payload?.let { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }
            ?: return CommandResults.failed(command, "device.locationMode requires { mode }")
        store.set(p.mode)
        CommandResults.done(command, "location mode = ${store.get()}")
    }.getOrElse { CommandResults.failed(command, it.message ?: "location mode failed") }
}
