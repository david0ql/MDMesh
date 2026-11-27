package com.dallycontrol.agent.provisioning

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dallycontrol.agent.MainActivity
import com.dallycontrol.agent.R
import com.dallycontrol.core.capability.CapabilitySource
import com.dallycontrol.core.config.ServerConfigStore
import com.dallycontrol.core.enroll.EnrollFailure
import com.dallycontrol.core.enroll.EnrollIdentitySource
import com.dallycontrol.core.enroll.EnrollStatusStore
import com.dallycontrol.core.net.MdmApi
import com.dallycontrol.core.store.DeviceIdStore
import com.dallycontrol.core.store.EnrollTokenStore
import com.dallycontrol.core.sync.HardwareIdSource
import com.dallycontrol.proto.AgentEnrollRequest
import com.dallycontrol.proto.EnrollDiagnostics
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A phone that is Device Owner but has no enrollment token must not stay silent. That state is what a QR enrollment
 * leaves behind when the payload never reaches the agent: on Android 9 and below it is only delivered when the setup
 * wizard finishes the provisioning, so an abandoned wizard - or a maker's wizard that skips that step - produces a
 * managed phone that never shows up in the console. This puts the way out in front of the person: the agent's screen
 * with "scan the console's QR" and "type a folder code", a notification that leads there, and (when the server is
 * known) a note in the console's attempts log.
 */
object EnrollPrompt {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun api(): MdmApi
        fun capabilities(): CapabilitySource
        fun hardwareId(): HardwareIdSource
        fun identity(): EnrollIdentitySource
    }

    private const val TAG = "EnrollPrompt"
    private const val CHANNEL = "mdm_enroll"
    private const val NOTIFICATION_ID = 1002
    private const val PREFS = "mdm_enroll_prompt"
    private const val KEY_REPORTED_AT = "reportedAt"
    private const val REPORT_EVERY_MS = 6L * 60L * 60L * 1000L
    private const val SECOND_MS = 1000L

    /** Seconds after start at which the state is checked: the provisioning callbacks get the first minute. */
    @Suppress("MagicNumber")
    private val CHECKS_SEC = longArrayOf(60, 120, 300, 900)

    /** True when this phone is Device Owner, not enrolled, and has no token to enroll with. */
    fun needed(context: Context): Boolean {
        val app = context.applicationContext
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        return dpm?.isDeviceOwnerApp(app.packageName) == true && runBlocking {
            DeviceIdStore(app).current().isNullOrBlank() && EnrollTokenStore(app).token().isNullOrBlank()
        } && EnrollStatusStore(app).pending() == null
    }

    /** Watch for the state after a start; prompt (and report) each time it is still true. */
    fun watch(context: Context) {
        val app = context.applicationContext
        ProvisioningIntake.scope.launch {
            var last = 0L
            for (at in CHECKS_SEC) {
                delay((at - last) * SECOND_MS)
                last = at
                if (!runCatching { needed(app) }.getOrDefault(false)) continue
                runCatching { show(app) }.onFailure { Log.w(TAG, "could not prompt: ${it.message}") }
                runCatching { report(app) }.onFailure { Log.i(TAG, "could not report: ${it.message}") }
            }
        }
    }

    /** The agent's screen when Android lets a background app open one (before Android 10); always a notification. */
    fun show(app: Context) {
        EnrollStatusStore(app).failed(EnrollFailure(EnrollFailure.NO_TOKEN, "Device Owner without an enrollment token"))
        val open = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, app.getString(R.string.enroll_prompt_channel), NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(app.getString(R.string.enroll_prompt_title))
                .setContentText(app.getString(R.string.enroll_prompt_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(R.string.enroll_prompt_text)))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(app, 0, open, flags))
                .build(),
        )
        runCatching { app.startActivity(open) }
    }

    fun dismiss(context: Context) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        }
    }

    /**
     * Tell the server, when the agent knows which one (the server address was provisioned but the token was not):
     * the console then lists this phone as "Device Owner, not enrolled" with its maker, model and Android release.
     */
    private suspend fun report(app: Context) {
        if (ServerConfigStore(app).provisioned() == null) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_REPORTED_AT, 0L) < REPORT_EVERY_MS) return
        val deps = EntryPointAccessors.fromApplication(app, Deps::class.java)
        val matrix = deps.capabilities().matrix(deviceId = "")
        val (serial, imei) = runCatching { deps.identity().get() }.getOrDefault(null to null)
        val status = EnrollStatusStore(app).snapshot()
        deps.api().enrollReport(
            AgentEnrollRequest(
                enrollToken = "",
                agent = matrix.agent,
                device = matrix.device,
                capabilities = matrix.capabilities,
                hardwareId = runCatching { deps.hardwareId().get() }.getOrNull(),
                serial = serial,
                imei = imei,
                diagnostics = EnrollDiagnostics(
                    path = status.path ?: EnrollStatusStore.PATH_UNKNOWN,
                    attempts = status.attempts,
                ),
            ),
        )
        prefs.edit().putLong(KEY_REPORTED_AT, System.currentTimeMillis()).apply()
    }
}
