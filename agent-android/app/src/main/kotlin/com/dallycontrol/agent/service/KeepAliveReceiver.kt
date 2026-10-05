package com.dallycontrol.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dallycontrol.core.sync.CheckInCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Fired by the [WakeKeepAlive] doze-proof alarm. Does a single check-in (delivering any pending
 * commands during the Doze maintenance window) and reschedules the next alarm. Uses `goAsync` so
 * the short network round-trip completes; long-running commands are still handled by the foreground
 * service / its WebSocket when the device is active.
 *
 * The broadcast is released after [BUDGET_MS] even if the check-in is still running: a cycle can
 * wait on the coordinator lock (an install in flight) or a slow cellular network, and holding the
 * broadcast past Android's 60 s background limit is reported as "app not responding" — on HyperOS
 * that ANR is followed by the process being killed. The cycle itself keeps running in [scope].
 *
 * A check-in here does NOT require starting a foreground service, so it sidesteps the Android 12+
 * background-FGS-start restriction.
 */
@AndroidEntryPoint
class KeepAliveReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: CheckInCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        // Re-arm the next alarm BEFORE the check-in (FLAG_UPDATE_CURRENT makes it idempotent):
        // if the check-in hangs or the process is killed mid-flight, the heartbeat chain survives.
        WakeKeepAlive.schedule(context)
        // Another cycle is already running (service, WebSocket wake, worker): it reports for us.
        if (coordinator.isBusy) return

        val pending = goAsync()
        val released = AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) pending.finish() }
        scope.launch {
            try {
                coordinator.runOnce()
            } catch (e: Exception) {
                Log.w(TAG, "heartbeat check-in failed", e) // transient — the next heartbeat retries
            } finally {
                release()
            }
        }
        scope.launch {
            delay(BUDGET_MS)
            release()
        }
    }

    private companion object {
        const val TAG = "KeepAliveReceiver"

        /** Well under the 60 s background-broadcast ANR limit. */
        const val BUDGET_MS = 25_000L

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
