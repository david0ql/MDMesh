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
import com.dallycontrol.policy.wifi.DpmHandle
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
 *  - Google accounts: one of a domain the policy does not allow is removed as soon as it is added;
 *  - loud sounds (alarms, ringtones) that keep playing: recorded with their likely source;
 *  - the location trail: a fresh fix every ConfigTracking interval, buffered and sent right away;
 *  - incoming calls in kiosk: lock task hides heads-up notifications, so a ringing call would be invisible and
 *    could not be answered; when the kiosk allows the dialer, its in-call screen is brought to the front instead
 *    (the notification shade stays closed).
 */
@Singleton
class DeviceWatch @Inject constructor(
    private val trail: TrailStore,
    private val location: LocationCollector,
    private val sim: SimMonitor,
    private val appPolicy: AppPolicyEnforcer,
    private val eventLog: EventLog,
    private val dpm: DpmHandle,
    private val vnc: com.dallycontrol.agent.remote.DroidVncController,
    private val deviceRules: com.dallycontrol.agent.policy.DeviceRules,
    private val sounds: com.dallycontrol.agent.diag.SoundWatch,
) {
    private var trailJob: Job? = null
    private var registered: Context? = null

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            // Remote support installed or updated from the console (no USB): prepare it at once, and report the new
            // remote capability right away.
            if (pkg == com.dallycontrol.agent.remote.DroidVncController.PACKAGE && intent.action == Intent.ACTION_PACKAGE_ADDED) {
                runCatching { vnc.prepare() }.onFailure { Log.w(TAG, "droidVNC-NG prepare", it) }
                CheckInWorker.scheduleNow(context)
            }
            // The update trail: an app replaced by a newer version is an update (with the version it went to).
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                if (intent.action == Intent.ACTION_PACKAGE_ADDED) {
                    val before = lastVersions[pkg]
                    val now = versionOf(context, pkg)
                    runCatching { eventLog.record(EventType.APP_UPDATED, "$pkg ${before?.let { "$it -> " } ?: ""}$now") }
                    lastVersions[pkg] = now
                    CheckInWorker.scheduleNow(context)
                } else if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
                    lastVersions[pkg] = versionOf(context, pkg) // about to be replaced: remember the old version
                }
                return
            }
            when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    runCatching { eventLog.record(EventType.APP_INSTALLED, "$pkg ${versionOf(context, pkg)}") }
                    runCatching { appPolicy.reenforce() }.onFailure { Log.w(TAG, "app policy", it) }
                }
                Intent.ACTION_PACKAGE_REMOVED -> runCatching { eventLog.record(EventType.APP_UNINSTALLED, pkg) }
            }
            CheckInWorker.scheduleNow(context)
        }
    }

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching { com.dallycontrol.agent.announce.Announcements.showNextMandatory(context) }
        }
    }

    /** Versions seen just before a replacement (PACKAGE_REMOVED with REPLACING arrives first). */
    private val lastVersions = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Suppress("DEPRECATION")
    private fun versionOf(context: Context, pkg: String): String = runCatching {
        val i = context.packageManager.getPackageInfo(pkg, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else i.versionCode.toLong()
        "${i.versionName ?: "?"} ($code)"
    }.getOrDefault("?")

    private val simReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching { sim.check() }
            CheckInWorker.scheduleNow(context)
        }
    }

    private val calls = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)
            if (state != android.telephony.TelephonyManager.EXTRA_STATE_RINGING &&
                state != android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK) return
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            if (am?.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) return // heads-up works
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager ?: return
            val dialer = runCatching { telecom.defaultDialerPackage }.getOrNull() ?: return
            if (Build.VERSION.SDK_INT >= 23 && !dpm.dpm.isLockTaskPermitted(dialer)) return // the kiosk does not allow calls
            showInCall(telecom)
            // The broadcast can beat the dialer's call UI: while the call still rings, ask again shortly after.
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            for (delayMs in IN_CALL_RETRIES_MS) {
                main.postDelayed({ if (runCatching { telecom.isInCall }.getOrDefault(false)) showInCall(telecom) }, delayMs)
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // READ_PHONE_STATE is granted by the provisioning baseline
    private fun showInCall(telecom: android.telecom.TelecomManager) {
        runCatching { telecom.showInCallScreen(false) }.onFailure { Log.w(TAG, "showInCallScreen", it) }
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
            ContextCompat.registerReceiver(
                context, calls, IntentFilter(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            // A mandatory announcement not confirmed yet comes back each time the phone is unlocked.
            ContextCompat.registerReceiver(context, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
            registered = context
            // A Google account of another domain goes the moment it is added, not at the next check-in.
            deviceRules.watchAccounts()
            // Alarms / ringtones that keep sounding: recorded with their likely source (see SoundWatch).
            sounds.start()
            runCatching { com.dallycontrol.agent.announce.Announcements.showNextMandatory(context) }
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
            runCatching { c.unregisterReceiver(calls) }
            runCatching { c.unregisterReceiver(unlocked) }
        }
        deviceRules.unwatchAccounts()
        sounds.stop()
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
        val IN_CALL_RETRIES_MS = longArrayOf(1_500L, 4_000L)
    }
}
