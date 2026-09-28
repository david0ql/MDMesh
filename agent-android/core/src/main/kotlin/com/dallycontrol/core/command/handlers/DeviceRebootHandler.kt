package com.dallycontrol.core.command.handlers

import android.os.Build
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult

/**
 * `device.reboot` — reboot the device (Device Owner, API 24+). The device restarts, so the
 * result may not be delivered before reboot; we ack optimistically. `reboot()` throws if a
 * call is in progress — caught and reported.
 */
class DeviceRebootHandler(
    private val handle: DpmHandle,
) : CommandHandler {

    override val type: String = "device.reboot"

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return CommandResults.unsupported(command, "reboot requires API 24+")
        }
        if (!handle.dpm.isDeviceOwnerApp(handle.admin.packageName)) {
            return CommandResults.unsupported(command, "reboot requires Device Owner")
        }
        return runCatching {
            handle.dpm.reboot(handle.admin)
            CommandResults.done(command, "rebooting")
        }.getOrElse { CommandResults.failed(command, it.message ?: "reboot failed") }
    }
}
