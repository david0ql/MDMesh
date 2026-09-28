package com.dallycontrol.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dallycontrol.core.config.AppPolicyEnforcer
import com.dallycontrol.core.location.LocationCollector
import com.dallycontrol.core.location.TrailStore
import com.dallycontrol.core.sync.CheckInWorker
import com.dallycontrol.core.telemetry.EventLog
import com.dallycontrol.core.telemetry.SimMonitor
import com.dallycontrol.proto.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the foreground service watches between check-ins:
 *  - apps installed/removed (registered at runtime: since Android 8 a manifest receiver no longer gets these),
 *    re-enforcing the app policy on every install;
 *  - the SIM card (removed / inserted / swapped → event + immediate check-in);
 *  - the location trail: a fresh fix every ConfigTracking interval, buffered and sent right away.
 */
@Singleton
class DeviceWatch @Inject constructor(
    private val trail: TrailStore,
    private val location: LocationCollector,
    private val sim: SimMonitor,
    private val appPolicy: AppPolicyEnforcer,
    private val eventLog: EventLog,
) {
    private var trailJob: Job? = null
    private var registered: Context? = null

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    runCatching { eventLog.record(EventType.APP_INSTALLED, pkg) }
                    runCatching { appPolicy.reenforce() }.onFailure { Log.w(TAG, "app policy", it) }
                }
                Intent.ACTION_PACKAGE_REMOVED -> runCatching { eventLog.record(EventType.APP_UNINSTALLED, pkg) }
            }
            CheckInWorker.scheduleNow(context)
        }
    }

    private val simReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching { sim.check() }
            CheckInWorker.scheduleNow(context)
        }
    }

    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        if (registered == null) {
            // API < 26 still delivers these to the manifest PackageEventReceiver; registering here too would double them.
            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.registerReceiver(
                    context, packages,
                    IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
                    },
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
            ContextCompat.registerReceiver(context, simReceiver, IntentFilter(SIM_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            registered = context
            scope.launch(Dispatchers.IO) {
                runCatching { appPolicy.reenforce() }
                runCatching { sim.check() }
            }
        }
        if (trailJob?.isActive != true) trailJob = scope.launch { trailLoop(context) }
    }

    @Synchronized
    fun stop() {
        registered?.let { c ->
            runCatching { c.unregisterReceiver(packages) }
            runCatching { c.unregisterReceiver(simReceiver) }
        }
        registered = null
        trailJob?.cancel()
    }

    private suspend fun trailLoop(context: Context) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val minutes = trail.intervalMinutes()
            if (minutes <= 0) {
                delay(IDLE_RECHECK_MS)
                continue
            }
            val fix = withContext(Dispatchers.IO) { runCatching { location.collect(forceFresh = true) }.getOrNull() }
            if (fix != null) {
                trail.add(fix)
                CheckInWorker.scheduleNow(context)
            }
            delay(minutes * 60_000L)
        }
    }

    private companion object {
        const val TAG = "DeviceWatch"
        /** Hidden framework action (TelephonyIntents.ACTION_SIM_STATE_CHANGED), still broadcast to receivers. */
        const val SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"
        const val IDLE_RECHECK_MS = 60_000L
    }
}
