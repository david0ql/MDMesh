package com.dallycontrol.agent.remote

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `remote.inputSetup` — remote CONTROL (not just view) needs droidVNC-NG's accessibility service, which a Device
 * Owner cannot switch on by itself. This opens the accessibility settings on the phone for the person holding it
 * (one switch, once). In kiosk, system Settings is let through for at most [WINDOW_MS] and locked away again as soon as
 * the service is on.
 */
class RemoteInputSetupHandler(
    private val context: Context,
    private val handle: DpmHandle,
    private val vnc: DroidVncController,
) : CommandHandler {
    override val type: String = TYPE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        if (!vnc.isInstalled()) return CommandResults.failed(command, "remote support is not installed")
        if (vnc.isInputServiceEnabled()) return CommandResults.done(command, "control already enabled")
        val am = context.getSystemService(ActivityManager::class.java)
        val inKiosk = am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        if (inKiosk && Build.VERSION.SDK_INT >= 26) {
            runCatching {
                val current = handle.dpm.getLockTaskPackages(handle.admin)
                if (SETTINGS !in current) {
                    handle.dpm.setLockTaskPackages(handle.admin, current + SETTINGS)
                    scope.launch { relockWhenDone() }
                }
            }
        }
        val opened = runCatching {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return opened.fold(
            onSuccess = { CommandResults.done(command, "accessibility settings opened on the phone: turn on droidVNC-NG") },
            onFailure = { CommandResults.failed(command, it.message ?: "could not open settings") },
        )
    }

    private suspend fun relockWhenDone() {
        val until = System.currentTimeMillis() + WINDOW_MS
        while (System.currentTimeMillis() < until && !vnc.isInputServiceEnabled()) delay(2_000)
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            val current = handle.dpm.getLockTaskPackages(handle.admin)
            handle.dpm.setLockTaskPackages(handle.admin, current.filter { it != SETTINGS }.toTypedArray())
        }
        // Back to the kiosk home (the agent's HOME) so the phone is not left on a Settings screen.
        runCatching {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        runCatching { vnc.prepare() }
    }

    companion object {
        const val TYPE = "remote.inputSetup"
        const val SETTINGS = "com.android.settings"
        const val WINDOW_MS = 5 * 60_000L
    }
}
