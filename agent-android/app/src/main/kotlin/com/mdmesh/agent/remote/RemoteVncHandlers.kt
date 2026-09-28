package com.mdmesh.agent.remote

import android.net.Uri
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.remote.RepeaterTunnel
import com.mdmesh.core.store.DeviceIdentity
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `remote.vnc.start` — begin a remote view/control session through droidVNC-NG (ADR 0010).
 * Payload: `{ sessionId, password?, viewOnly?, transport?, host?, port? }`, `sessionId` 8-18 decimal digits.
 * The device dials the repeater presenting [sessionId]; the console's noVNC joins with the same id.
 * [password] is the per-session VNC password the server generated.
 *
 * - `transport: "wss"` (what the server sends when we advertise [DroidVncController.TRANSPORT_WSS]):
 *   droidVNC-NG dials a loopback port and [RepeaterTunnel] carries the stream to the repeater inside a
 *   WebSocket over the server's HTTPS origin — encrypted end to end between phone and server.
 * - otherwise (older servers): droidVNC-NG dials [host]:[port] directly (default: this agent's own server
 *   host, port 5500), unencrypted apart from the VNC password exchange.
 */
class RemoteVncStartHandler(
    private val vnc: DroidVncController,
    private val serverConfig: ServerConfigStore,
    private val tunnel: RepeaterTunnel,
    private val identity: DeviceIdentity,
) : CommandHandler {

    override val type: String = TYPE

    @Serializable
    private data class Payload(
        val sessionId: String,
        val password: String? = null,
        val viewOnly: Boolean = false,
        val transport: String? = null,
        val host: String? = null,
        val port: Int? = null,
    )

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val p = command.payload
            ?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
        if (p == null) return CommandResults.failed(command, "remote.vnc.start requires { sessionId }")
        if (!SESSION_ID.matches(p.sessionId)) return CommandResults.failed(command, "invalid sessionId")
        return if (p.transport == TRANSPORT_WSS) startTunnelled(command, p) else startDirect(command, p)
    }

    private suspend fun startTunnelled(command: CommandEnvelope, p: Payload): CommandResult {
        val deviceId = identity.current()
        val secret = identity.secret()
        if (deviceId == null || secret == null) return CommandResults.failed(command, "not enrolled")
        val url = serverConfig.baseUrl().trimEnd('/') + RepeaterTunnel.PATH
        val port = runCatching { tunnel.open(url, deviceId, secret) }
            .getOrElse { return CommandResults.failed(command, it.message ?: "tunnel failed") }
        val error = vnc.startSession(p.sessionId, p.password, p.viewOnly, RepeaterTunnel.LOOPBACK_HOST, port)
        if (error != null) {
            tunnel.close()
            return CommandResults.failed(command, error)
        }
        return CommandResults.done(command, "connected to the repeater through the encrypted tunnel")
    }

    private suspend fun startDirect(command: CommandEnvelope, p: Payload): CommandResult {
        val host = p.host?.takeIf { it.isNotBlank() } ?: Uri.parse(serverConfig.baseUrl()).host
            ?: return CommandResults.failed(command, "no repeater host")
        val port = p.port ?: DroidVncController.DEFAULT_REPEATER_PORT
        tunnel.close()
        val error = vnc.startSession(p.sessionId, p.password, p.viewOnly, host, port)
        return if (error == null) {
            CommandResults.done(command, "dialing repeater $host:$port (unencrypted)")
        } else {
            CommandResults.failed(command, error)
        }
    }

    companion object {
        const val TYPE = "remote.vnc.start"
        private const val TRANSPORT_WSS = "wss"
        /** The Mode-II repeater only pairs positive decimal ids that fit a signed 64-bit long. */
        private val SESSION_ID = Regex("[1-9][0-9]{7,17}")
    }
}

/** `remote.vnc.stop` — end the session, close the tunnel and stop droidVNC-NG. */
class RemoteVncStopHandler(
    private val vnc: DroidVncController,
    private val tunnel: RepeaterTunnel,
) : CommandHandler {
    override val type: String = "remote.vnc.stop"

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        tunnel.close()
        return vnc.stopSession()?.let { CommandResults.failed(command, it) } ?: CommandResults.done(command)
    }
}
