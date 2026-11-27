package com.dallycontrol.agent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.dallycontrol.agent.service.BatteryExemption
import com.dallycontrol.agent.service.WakeKeepAlive
import com.dallycontrol.core.sync.CheckInWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt application root. Also supplies the Hilt-aware [HiltWorkerFactory] so
 * WorkManager can construct injected workers ([CheckInWorker]), and schedules the
 * periodic check-in on startup.
 */
@HiltAndroidApp
class MdmApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var connection: com.dallycontrol.core.net.ConnectionDiagnostics
    @Inject lateinit var mobileData: dagger.Lazy<com.dallycontrol.agent.data.MobileDataEnforcer>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Before anything reports: was the process killed, or did the phone lose power, since the last sign of life?
        runCatching {
            connection.onProcessStart(
                android.provider.Settings.Global.getInt(
                    contentResolver, android.provider.Settings.Global.BOOT_COUNT,
                    com.dallycontrol.core.net.ConnectionDiagnostics.NO_BOOT_COUNT,
                ),
            )
        }
        CheckInWorker.schedule(this)   // periodic reconcile (WorkManager floor)
        CheckInWorker.scheduleNow(this) // prompt check-in on every cold start (post-install/reboot)
        WakeKeepAlive.schedule(this)   // doze-proof reconcile heartbeat
        // Claim a battery-optimization exemption (silent as Device Owner on API 34+) so the heartbeat and the
        // WebSocket are not Doze-throttled — there is no FCM in this build. Idempotent; see BatteryExemption.
        runCatching { BatteryExemption.ensure(this) }
        // Safety net for the Device-Owner baseline (a no-op once applied): covers phones provisioned through a path
        // that skipped it - the older QR flow of agents up to 0.9.8, or a maker that dropped a provisioning callback.
        Thread { runCatching { com.dallycontrol.agent.provisioning.ProvisioningBaseline.ensure(this) } }.start()
        // Same idea for the enrollment payload: a copy kept at GET_PROVISIONING_MODE is used if nothing delivered it.
        runCatching { com.dallycontrol.agent.provisioning.ProvisioningIntake.adoptPendingSoon(this) }
        // And if this phone is Device Owner with nothing to enroll with, say so instead of staying silent.
        runCatching { com.dallycontrol.agent.provisioning.EnrollPrompt.watch(this) }
        // Mobile-data policy: resume the limits' clock and the network watch of the local VPN (no-op when not managed).
        runCatching { mobileData.get().start() }
    }
}
