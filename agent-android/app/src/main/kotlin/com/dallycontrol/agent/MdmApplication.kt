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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        CheckInWorker.schedule(this)   // periodic reconcile (WorkManager floor)
        CheckInWorker.scheduleNow(this) // prompt check-in on every cold start (post-install/reboot)
        WakeKeepAlive.schedule(this)   // doze-proof reconcile heartbeat
        // Claim a battery-optimization exemption (silent as Device Owner on API 34+) so the heartbeat and the
        // WebSocket are not Doze-throttled — there is no FCM in this build. Idempotent; see BatteryExemption.
        runCatching { BatteryExemption.ensure(this) }
        // Safety net for the Device-Owner baseline (a no-op once applied): covers phones provisioned through a path
        // that skipped it - the older QR flow of agents up to 0.9.8, or a maker that dropped a provisioning callback.
        Thread { runCatching { com.dallycontrol.agent.provisioning.ProvisioningBaseline.ensure(this) } }.start()
    }
}
