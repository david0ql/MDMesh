package com.dallycontrol.agent.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.dallycontrol.core.net.AndroidNetworkState
import com.dallycontrol.core.net.ConnectionDiagnostics
import com.dallycontrol.core.sync.CheckInCoordinator
import com.dallycontrol.core.transport.ConnectionWatchdog
import com.dallycontrol.core.transport.NetworkChangeTracker
import com.dallycontrol.core.transport.TransportManager
import com.dallycontrol.proto.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * Keeps the connection up for [CheckInService], beyond what the socket does on its own:
 *
 *  - **Network changes.** The default network changed or came back: reopen the socket and report at once, instead
 *    of waiting for the socket's ping to miss (up to ~100 s) plus its backoff. This closes the "disconnected"
 *    stretch after walking from Wi-Fi to mobile data or coming out of a dead zone.
 *  - **Watchdog.** See [ConnectionWatchdog]: a check-in every few minutes whatever the alarms do, and a full
 *    transport restart when check-ins keep failing although there is network.
 *  - **Shutdown.** Notes an orderly power-off and tries one last report, so the console shows the device as
 *    switched off instead of as a lost connection.
 */
class ConnectionKeeper @Inject constructor(
    private val coordinator: CheckInCoordinator,
    private val transport: TransportManager,
    private val connection: ConnectionDiagnostics,
    private val wake: com.dallycontrol.agent.wake.WakeChannel,
) {
    private val watchdog = ConnectionWatchdog()
    private val networkChanges = NetworkChangeTracker()
    private var scope: CoroutineScope? = null
    private var onRecovered: () -> Unit = {}

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runCatching { connection.networkBack() }
            if (networkChanges.onAvailable(network.networkHandle)) onNetworkRecovered()
        }

        override fun onLost(network: Network) {
            if (networkChanges.onLost(network.networkHandle)) {
                runCatching { connection.recordStop(StopReason.NETWORK_LOST) }
            }
        }
    }

    /** @param onRecovered called after the check-in that follows a network recovery. */
    fun start(context: Context, scope: CoroutineScope, onRecovered: () -> Unit) {
        this.scope = scope
        this.onRecovered = onRecovered
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(networkCallback)
            } else {
                // Android 6 has no default-network callback: watch every network that offers internet.
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, networkCallback)
            }
        }.onFailure { Log.w(TAG, "network callback unavailable", it) }
        val network = AndroidNetworkState(context.applicationContext)
        scope.launch { runWatchdog(network) }
    }

    fun stop(context: Context) {
        runCatching {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(networkCallback)
        }
    }

    /**
     * The device is shutting down (or restarting). The note is on disk first — the report may not make it out
     * before the system stops us.
     */
    fun onShutdown(context: Context) {
        Log.i(TAG, "device shutting down: sending the goodbye report")
        runCatching { connection.recordStop(StopReason.SHUTDOWN, batteryPercent(context)) }
        shutdownScope.launch {
            runCatching { withTimeout(GOODBYE_TIMEOUT_MS) { coordinator.runGoodbye() } }
                .onFailure { Log.w(TAG, "goodbye check-in did not go out", it) }
        }
    }

    private fun onNetworkRecovered() {
        Log.i(TAG, "network changed or came back: reconnecting now")
        // Back on a network: the backup alarm runs at its fast pace for a while, and this check-in says why it came.
        wake.noteActivity()
        wake.mark(com.dallycontrol.proto.WakeTrigger.NETWORK)
        // restart(), not just reconnectNow(): connections pooled on the previous network are dead too, and a
        // check-in written into one hangs until the read timeout (20 s) before it is retried on a fresh one.
        transport.restart()
        scope?.launch {
            runCatching { coordinator.runOnce() }
                .recoverCatching {
                    // The network is announced a moment before it carries traffic: one more try.
                    delay(RETRY_DELAY_MS)
                    coordinator.runOnce()
                }
                .onFailure { Log.w(TAG, "check-in after network recovery failed", it) }
            onRecovered()
        }
    }

    private suspend fun runWatchdog(network: AndroidNetworkState) {
        while (true) {
            delay(ConnectionWatchdog.TICK_MS)
            runCatching { connection.markAlive() }
            if (network.current().connected) runCatching { transport.ensureConnected() }
            val action = watchdog.decide(
                now = System.currentTimeMillis(),
                lastSuccessAt = connection.lastSuccessAt,
                networkAvailable = network.current().connected,
            )
            if (action != ConnectionWatchdog.Action.NONE && !coordinator.isBusy) act(action)
        }
    }

    private suspend fun act(action: ConnectionWatchdog.Action) {
        if (action == ConnectionWatchdog.Action.RESTART) {
            Log.w(TAG, "no successful check-in for a while with network available: restarting the transport")
            transport.restart()
        }
        runCatching { coordinator.runOnce() }
            .onFailure { Log.w(TAG, "watchdog check-in failed", it) }
    }

    private fun batteryPercent(context: Context): Int? {
        val batt = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batt?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level < 0 || scale <= 0) null else level * PERCENT / scale
    }

    private companion object {
        const val TAG = "ConnectionKeeper"
        const val RETRY_DELAY_MS = 2_000L
        const val GOODBYE_TIMEOUT_MS = 8_000L
        const val PERCENT = 100

        /** Outlives the service's own scope: the goodbye report runs while everything is being torn down. */
        val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
