package com.dallycontrol.agent.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.dallycontrol.agent.service.CheckInService
import com.dallycontrol.core.config.ServerConfigStore
import com.dallycontrol.core.store.DeviceIdStore
import com.dallycontrol.core.store.EnrollTokenStore
import com.dallycontrol.core.sync.CheckInWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * USB/ADB enrollment: the QR bundle's job (server URL + single-use enroll token) for a device that
 * was made Device Owner with `adb shell dpm set-device-owner`. This is the enrollment path the
 * Play-Protect DPC allowlist does not block, so it must work on release builds too.
 *
 * ```
 * adb shell am broadcast -a com.dallycontrol.agent.ADB_PROVISION \
 *     -n <pkg>/com.dallycontrol.agent.provisioning.AdbProvisionReceiver \
 *     --es server_url https://mdm.example.com --es enroll_token <token> [--ez force true]
 * ```
 *
 * Only the shell (or system) can send it: the manifest guards it with `android.permission.DUMP`,
 * which third-party apps cannot hold. It also refuses to re-point an already-enrolled agent unless
 * `force` is set, so it can never silently move a managed device to another server.
 */
class AdbProvisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val url = intent.getStringExtra(EXTRA_SERVER_URL)?.trim().orEmpty()
        val token = intent.getStringExtra(EXTRA_ENROLL_TOKEN)?.trim().orEmpty()
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val refusal = when {
            !url.startsWith("https://") && !url.startsWith("http://") ->
                "error: server_url must be an http(s) URL"
            !dpm.isDeviceOwnerApp(context.packageName) ->
                "error: not Device Owner (run dpm set-device-owner first)"
            else -> null
        }
        if (refusal != null) {
            finish(this, Activity.RESULT_CANCELED, refusal)
        } else {
            provision(context, url, token, force)
        }
    }

    private fun provision(context: Context, url: String, token: String, force: Boolean) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var code = Activity.RESULT_OK
            var message = "ok: provisioned $url"
            try {
                val ids = DeviceIdStore(app)
                val enrolled = ids.current()?.isNotBlank() == true
                if (enrolled && !force) {
                    code = Activity.RESULT_CANCELED
                    message = "error: already enrolled (pass --ez force true to re-enroll)"
                } else {
                    if (enrolled) ids.clear()
                    ProvisioningBaseline.apply(app)
                    ServerConfigStore(app).save(url)
                    if (token.isNotBlank()) EnrollTokenStore(app).save(token)
                    CheckInWorker.schedule(app)
                    CheckInWorker.scheduleNow(app)
                    // Device Owner is exempt from the background FGS-start restriction.
                    runCatching {
                        ContextCompat.startForegroundService(app, Intent(app, CheckInService::class.java))
                    }.onFailure { Log.w(TAG, "could not start CheckInService", it) }
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                code = Activity.RESULT_CANCELED
                message = "error: ${e.message}"
            } finally {
                Log.i(TAG, message)
                pending.resultCode = code
                pending.resultData = message
                pending.finish()
            }
        }
    }

    private fun finish(receiver: BroadcastReceiver, code: Int, message: String) {
        Log.i(TAG, message)
        receiver.resultCode = code
        receiver.resultData = message
    }

    companion object {
        const val ACTION = "com.dallycontrol.agent.ADB_PROVISION"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_ENROLL_TOKEN = "enroll_token"
        const val EXTRA_FORCE = "force"
        private const val TAG = "AdbProvision"
    }
}
