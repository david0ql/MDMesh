package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.core.install.ApkPart
import com.dallycontrol.core.install.InstallManager
import com.dallycontrol.core.install.InstallOutcome
import com.dallycontrol.core.install.InstallRequest
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `device.appInstallForce` — re-push an app the agent had stopped retrying. It first clears the cached
 * "already tried this version-name" and "OS blocked this version" markers for the package
 * ([InstallManager.clearInstallCache]), then runs the install ONCE through the normal [InstallManager.install]
 * path (version gating, downgrade rules and OEM-block caching all still apply to this fresh attempt).
 *
 * Payload is identical to `app.install`: `{ url, packageName, versionCode?, versionName?, sha256?,
 * runAfterInstall?, allowDowngrade?, parts? }`. This is a one-shot override, not a loop: if the OS blocks the
 * version again it re-latches the cache, so a repeated force is needed only after the underlying cause is fixed.
 */
class AppInstallForceHandler(
    private val installManager: InstallManager,
) : CommandHandler {

    override val type: String = DeviceAction.APP_INSTALL_FORCE

    @Serializable
    private data class PartPayload(
        val url: String? = null,
        val localPath: String? = null,
        val sha256: String? = null,
        val split: String? = null,
    )

    @Serializable
    private data class Payload(
        val url: String? = null,
        val localPath: String? = null,
        val packageName: String,
        val versionCode: Long? = null,
        val versionName: String? = null,
        val allowDowngrade: Boolean = false,
        val sha256: String? = null,
        val runAfterInstall: Boolean = false,
        val parts: List<PartPayload> = emptyList(),
    )

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload
            ?: return CommandResults.failed(command, "${DeviceAction.APP_INSTALL_FORCE} requires a payload")
        val p = runCatching {
            ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), payload)
        }.getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }

        // Drop the "do not retry" markers so this attempt starts clean, then install once.
        installManager.clearInstallCache(p.packageName)

        val outcome = installManager.install(
            InstallRequest(
                url = p.url,
                localPath = p.localPath,
                packageName = p.packageName,
                versionCode = p.versionCode,
                versionName = p.versionName,
                allowDowngrade = p.allowDowngrade,
                sha256 = p.sha256,
                runAfterInstall = p.runAfterInstall,
                parts = p.parts.map { ApkPart(url = it.url, localPath = it.localPath, sha256 = it.sha256, split = it.split) },
            ),
        )
        return when (outcome) {
            InstallOutcome.Success -> CommandResults.done(command, "forced install ok")
            is InstallOutcome.Skipped -> CommandResults.done(command, "skipped: ${outcome.reason}")
            is InstallOutcome.Failure -> CommandResults.failed(command, outcome.reason)
        }
    }
}
