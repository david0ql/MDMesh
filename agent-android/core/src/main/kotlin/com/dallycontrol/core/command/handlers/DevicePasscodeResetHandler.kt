package com.dallycontrol.core.command.handlers

import android.os.Build
import com.dallycontrol.core.action.ResetPasswordTokenStore
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
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
                CommandResults.failed(command, "Android rechazó la clave")
            }
        }
        if (pwd.isNotEmpty() && pwd.length < 4) return CommandResults.failed(command, "la clave debe tener al menos 4 caracteres")
        // Devices enrolled by an older agent (or without a reset) may have no token yet: register it now.
        val token = tokenStore.token() ?: tokenStore.ensureToken().takeIf { it.isNotEmpty() }
            ?: return CommandResults.failed(command, "Android no aceptó registrar el permiso para cambiar la clave")
        // A token registered while the phone already had a PIN only becomes active once the phone is unlocked with it.
        if (!handle.dpm.isResetPasswordTokenActive(handle.admin)) {
            return CommandResults.failed(
                command,
                "falta activar el cambio remoto: desbloquea el teléfono una vez con su clave actual y vuelve a intentarlo",
            )
        }
        val ok = handle.dpm.resetPasswordWithToken(handle.admin, pwd, token, 0)
        if (ok) CommandResults.done(command, if (pwd.isEmpty()) "clave quitada" else "clave cambiada")
        else CommandResults.failed(command, "Android rechazó la clave (no cumple la política de contraseñas del equipo: largo o tipo)")
    }.getOrElse { CommandResults.failed(command, it.message ?: "passcode reset failed") }
}
