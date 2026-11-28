package com.dallycontrol.agent.wake

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.dallycontrol.agent.service.CheckInService
import com.dallycontrol.agent.service.WakeKeepAlive
import com.dallycontrol.core.net.PrefsKeyValueStore
import com.dallycontrol.core.sync.CheckInCoordinator
import com.dallycontrol.core.transport.TransportManager
import com.dallycontrol.core.wake.WakeBridge
import com.dallycontrol.core.wake.WakeSchedule
import com.dallycontrol.core.wake.WakeState
import com.dallycontrol.proto.PushState
import com.dallycontrol.proto.WakeConfig
import com.dallycontrol.proto.WakeReport
import com.dallycontrol.proto.WakeTrigger
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One [WakeState] per process: the alarm, the receivers and the check-in cycle must see the same schedule. */
object WakeStates {
    @Volatile private var instance: WakeState? = null

    fun get(context: Context): WakeState = instance ?: synchronized(this) {
        instance ?: WakeState(PrefsKeyValueStore(context.applicationContext, "mdm_wake")).also { instance = it }
    }
}

/**
 * Everything that wakes the agent besides the live socket:
 *
 *  - a **push message** (Firebase Cloud Messaging, when the server has a project and the phone has Google
 *    services): [onPush] checks in at once and reopens the socket;
 *  - **activity on the phone** (screen on, unlock, charger, network back): [onEvent] checks in at once and switches
 *    the backup alarm to its fast pace for a while (see [WakeSchedule]);
 *  - the **backup alarm** itself ([WakeKeepAlive]), whose pace the device's policy sets.
 *
 * It is also the check-in's [WakeBridge]: it reports the push token and the phone's own limits, and applies the
 * schedule and push project the server answers with.
 */
@Singleton
class WakeChannel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: Lazy<CheckInCoordinator>,
    private val transport: Lazy<TransportManager>,
) : WakeBridge {

    val state: WakeState = WakeStates.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastEventCheckInAt = 0L

    /** Process start: bring the push registration back up from what is stored (token refreshes need it). */
    fun onStart() {
        runCatching { PushRegistrar.ensure(context, state, state.config?.push) }
            .onFailure { Log.w(TAG, "push registration could not be restored", it) }
    }

    override fun report(): WakeReport = WakeReport(
        provider = PushState.PROVIDER_FCM,
        token = state.pushToken,
        state = state.pushState ?: PushState.UNCONFIGURED,
        error = state.pushError,
        batteryExempt = batteryExempt(context),
        exactAlarms = exactAlarmsAllowed(context),
        bgRestricted = backgroundRestricted(context),
        pollMinutes = WakeSchedule.pollMinutes(state.config),
        wokenBy = state.takeTrigger(),
    )

    override fun onConfig(config: WakeConfig?) {
        if (config != state.config) {
            state.config = config
            WakeKeepAlive.schedule(context) // the pace may have changed
        }
        PushRegistrar.ensure(context, state, config?.push)
    }

    /**
     * The push wake arrived. Check in now (the command is waiting on the server) and bring the foreground service
     * and its socket back: a high-priority message is one of the few moments Android lets a background app do both.
     *
     * @return the check-in job, so the caller can hold its wake lock / broadcast until it is done.
     */
    fun onPush(): Job {
        Log.i(TAG, "push wake received: checking in now")
        state.markTrigger(WakeTrigger.PUSH)
        noteActivity()
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, CheckInService::class.java))
        }.onFailure { Log.w(TAG, "could not restart the check-in service from a push", it) }
        return scope.launch {
            runCatching { transport.get().ensureConnected() }
            checkIn()
        }
    }

    /** Something happened on the phone: a good moment to ask for commands. At most one check-in per [EVENT_GAP_MS]. */
    fun onEvent(cause: String) {
        noteActivity()
        val now = System.currentTimeMillis()
        if (now - lastEventCheckInAt < EVENT_GAP_MS) return
        lastEventCheckInAt = now
        state.markTrigger(cause)
        scope.launch { checkIn() }
    }

    /** Activity without a check-in of its own (the screen went off): only quickens the backup alarm. */
    fun noteActivity() {
        runCatching {
            state.noteActivity()
            WakeKeepAlive.schedule(context)
        }
    }

    fun mark(cause: String) = state.markTrigger(cause)

    private suspend fun checkIn() {
        runCatching { coordinator.get().runOnce() }
            .recoverCatching {
                // Coming out of sleep the network is announced a moment before it carries traffic: one more try.
                if (it !is java.io.IOException) throw it
                delay(RETRY_DELAY_MS)
                coordinator.get().runOnce()
            }
            .onFailure { Log.w(TAG, "wake check-in failed", it) }
    }

    companion object {
        private const val TAG = "WakeChannel"
        private const val EVENT_GAP_MS = 20_000L
        private const val RETRY_DELAY_MS = 2_000L

        fun batteryExempt(context: Context): Boolean? = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()

        /** Before Android 12 every app may schedule exact alarms. */
        fun exactAlarmsAllowed(context: Context): Boolean = runCatching {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        }.getOrDefault(false)

        /** "Restricted" battery use set by the person or the maker's battery manager (Android 9+). */
        fun backgroundRestricted(context: Context): Boolean? = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
        }.getOrNull()
    }
}
