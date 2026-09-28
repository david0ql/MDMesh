package com.mdmesh.agent.remote

import android.net.Uri
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `remote.vnc.start` — begin a remote view/control session through droidVNC-NG (ADR 0010).
 * Payload: `{ sessionId, password?, viewOnly?, host?, port? }`, `sessionId` 8-18 decimal digits.
 * The device dials the repeater at
 * [host]:[port] (default: this agent's own server host, port 5500) presenting [sessionId]; the console's
 * noVNC joins with the same id. [password] is the per-session VNC password the console generated.
 */
class RemoteVncStartHandler(
    private val vnc: DroidVncController,
    private val serverConfig: ServerConfigStore,
) : CommandHandler {

    override val type: String = TYPE

    @Serializable
    private data class Payload(
        val sessionId: String,
        val password: String? = null,
        val viewOnly: Boolean = false,
        val host: String? = null,
        val port: Int? = null,
    )

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val p = command.payload
            ?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
        val host = p?.host?.takeIf { it.isNotBlank() } ?: Uri.parse(serverConfig.baseUrl()).host
        val port = p?.port ?: DroidVncController.DEFAULT_REPEATER_PORT
        val error = when {
            p == null -> "remote.vnc.start requires { sessionId }"
            !SESSION_ID.matches(p.sessionId) -> "invalid sessionId"
            host == null -> "no repeater host"
            else -> vnc.startSession(p.sessionId, p.password, p.viewOnly, host, port)
        }
        return if (error == null) {
            CommandResults.done(command, "dialing repeater $host:$port")
        } else {
            CommandResults.failed(command, error)
        }
    }

    companion object {
        const val TYPE = "remote.vnc.start"
        /** The Mode-II repeater only pairs positive decimal ids that fit a signed 64-bit long. */
        private val SESSION_ID = Regex("[1-9][0-9]{7,17}")
    }
}

/** `remote.vnc.stop` — end the session and stop droidVNC-NG. */
class RemoteVncStopHandler(private val vnc: DroidVncController) : CommandHandler {
    override val type: String = "remote.vnc.stop"

    override suspend fun handle(command: CommandEnvelope): CommandResult =
        vnc.stopSession()?.let { CommandResults.failed(command, it) } ?: CommandResults.done(command)
}
