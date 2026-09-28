package com.mdmesh.core.command.handlers

import android.os.Build
import com.mdmesh.core.action.ResetPasswordTokenStore
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `device.passcodeReset` — set or clear the device passcode via the DO reset token (API 26+). */
class DevicePasscodeResetHandler(
    private val handle: DpmHandle,
    private val tokenStore: ResetPasswordTokenStore,
) : CommandHandler {

    override val type: String = DeviceAction.PASSCODE_RESET

    @Serializable
    private data class Payload(val newPassword: String? = null)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val pwd = command.payload
            ?.let { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }
            ?.newPassword ?: ""
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // API 23-25 have no token flow; the legacy call is still allowed for a Device Owner.
            @Suppress("DEPRECATION")
            val legacyOk = handle.dpm.resetPassword(pwd, 0)
            return if (legacyOk) {
                CommandResults.done(command)
            } else {
                CommandResults.failed(command, "resetPassword rejected")
            }
        }
        val token = tokenStore.token()
            ?: return CommandResults.failed(command, "no reset-password token provisioned")
        val ok = handle.dpm.resetPasswordWithToken(handle.admin, pwd, token, 0)
        if (ok) CommandResults.done(command) else CommandResults.failed(command, "resetPasswordWithToken rejected")
    }.getOrElse { CommandResults.failed(command, it.message ?: "passcode reset failed") }
}
