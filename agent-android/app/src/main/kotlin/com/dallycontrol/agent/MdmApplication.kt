package com.dallycontrol.agent

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
    }
}
