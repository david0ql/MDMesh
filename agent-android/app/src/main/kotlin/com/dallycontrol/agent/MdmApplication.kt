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
    @Inject lateinit var wakeChannel: com.dallycontrol.agent.wake.WakeChannel

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
        // WebSocket are not Doze-throttled — the push channel only exists where the server has a project.
        // Idempotent; see BatteryExemption.
        runCatching { BatteryExemption.ensure(this) }
        // Push wake channel: bring the stored registration back up (a no-op without a project or Google).
        wakeChannel.onStart()
    }
}
