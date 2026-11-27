package com.dallycontrol.agent.support

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `remote.session.start { sessionId }` — join the support session the console opened (ADR 0012). A phone whose
 * live socket is up has usually joined already from the wake; this is the path for one that was asleep.
 * A session that is over by the time the phone checks in is not an error: there is nothing left to join.
 */
class RemoteSessionStartHandler(private val sessions: SupportSessionManager) : CommandHandler {
    override val type: String = TYPE

    @Serializable
    private data class Payload(val sessionId: String)

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val p = command.payload
            ?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
            ?: return CommandResults.failed(command, "remote.session.start requires { sessionId }")
        val error = sessions.join(p.sessionId) ?: return CommandResults.done(command, "joined the support session")
        return if (error.startsWith("session refused")) {
            CommandResults.done(command, "the support session had already ended")
        } else {
            CommandResults.failed(command, error)
        }
    }

    companion object {
        const val TYPE = "remote.session.start"
    }
}

/** `remote.session.stop` — leave the support session. */
class RemoteSessionStopHandler(private val sessions: SupportSessionManager) : CommandHandler {
    override val type: String = "remote.session.stop"

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        sessions.leave("console")
        return CommandResults.done(command)
    }
}
