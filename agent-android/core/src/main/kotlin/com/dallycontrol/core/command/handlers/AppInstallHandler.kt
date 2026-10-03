package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.core.install.ApkPart
import com.dallycontrol.core.install.InstallManager
import com.dallycontrol.core.install.InstallOutcome
import com.dallycontrol.core.install.InstallRequest
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `app.install` — silently install (or upgrade) an app as Device Owner. Payload:
 * `{ url, packageName, versionCode?, sha256?, runAfterInstall?, parts? }`. A `parts` list
 * (`[{url, sha256?}]`) installs a split-APK bundle (base + splits) in one session; without it
 * the single `url`/`localPath` is used. Version gating, downgrade-blocking, and the install
 * itself are delegated to [InstallManager].
 */
class AppInstallHandler(
    private val installManager: InstallManager,
) : CommandHandler {

    override val type: String = "app.install"

    @Serializable
    private data class PartPayload(
        val url: String? = null,
        val localPath: String? = null,
        val sha256: String? = null,
        /** The part's name inside its bundle (config.arm64_v8a, config.es…); the phone keeps only the splits it needs. */
        val split: String? = null,
    )

    @Serializable
    private data class Payload(
        val url: String? = null,
        val localPath: String? = null,
        val packageName: String,
        val versionCode: Long? = null,
        val versionName: String? = null,
        val sha256: String? = null,
        val runAfterInstall: Boolean = false,
        val parts: List<PartPayload> = emptyList(),
    )

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload
            ?: return CommandResults.failed(command, "app.install requires a payload")
        val p = runCatching {
            ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), payload)
        }.getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }

        val outcome = installManager.install(
            InstallRequest(
                url = p.url,
                localPath = p.localPath,
                packageName = p.packageName,
                versionCode = p.versionCode,
                versionName = p.versionName,
                sha256 = p.sha256,
                runAfterInstall = p.runAfterInstall,
                parts = p.parts.map { ApkPart(url = it.url, localPath = it.localPath, sha256 = it.sha256, split = it.split) },
            ),
        )
        return when (outcome) {
            InstallOutcome.Success -> CommandResults.done(command)
            is InstallOutcome.Skipped -> CommandResults.done(command, "skipped: ${outcome.reason}")
            is InstallOutcome.Failure -> CommandResults.failed(command, outcome.reason)
        }
    }
}
