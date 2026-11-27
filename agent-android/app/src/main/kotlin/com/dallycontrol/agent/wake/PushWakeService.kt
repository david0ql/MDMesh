package com.dallycontrol.agent.wake

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dallycontrol.agent.BuildConfig
import com.dallycontrol.core.wake.WakeBridge
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Receives the server's push wake. The message is a signal only (`wake=commands`): it carries no command and no
 * secret, so anybody able to forge one could at most make the agent check in — which it authenticates as always.
 */
@AndroidEntryPoint
class PushWakeService : FirebaseMessagingService() {

    @Inject lateinit var wake: WakeChannel

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data[KEY_WAKE] == null) return
        val job = wake.onPush()
        // The library holds a wake lock only while this method runs: stay in it until the check-in is done (or
        // nearly out of the time Android allows), so the phone does not fall back asleep mid-request.
        runBlocking { withTimeoutOrNull(HOLD_MS) { job.join() } }
    }

    override fun onNewToken(token: String) {
        PushRegistrar.onNewToken(applicationContext, wake.state, token)
    }

    companion object {
        const val KEY_WAKE = "wake"
        private const val HOLD_MS = 9_000L
    }
}

/**
 * Field and test entry point for the push channel, for the shell only (guarded by `android.permission.DUMP`, which
 * apps cannot hold — same guard as the ADB enrollment receiver):
 *
 * ```
 * adb shell am broadcast -a com.dallycontrol.agent.PUSH_TEST -n <pkg>/com.dallycontrol.agent.wake.PushTestReceiver
 *     # behaves exactly as if the push wake had just arrived
 * adb shell am broadcast -a com.dallycontrol.agent.PUSH_TEST -n <pkg>/... --es token <fake>     (debug builds only)
 *     # pretends Google issued this token, so a simulated push service can address the device
 * ```
 */
@AndroidEntryPoint
class PushTestReceiver : BroadcastReceiver() {

    @Inject lateinit var wake: WakeChannel

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token != null) setToken(context, token) else simulatePush()
    }

    private fun setToken(context: Context, token: String) {
        if (!BuildConfig.DEBUG) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "error: a token can only be set on a debug build"
            return
        }
        PushRegistrar.onNewToken(context, wake.state, token)
        resultCode = Activity.RESULT_OK
        resultData = if (wake.state.pushToken == token) {
            "ok: token set"
        } else {
            "error: the server has not sent a push project yet"
        }
    }

    private fun simulatePush() {
        val pending = goAsync()
        val job = wake.onPush()
        scope.launch {
            withTimeoutOrNull(BUDGET_MS) { job.join() }
            pending.resultCode = Activity.RESULT_OK
            pending.resultData = "ok: push wake handled"
            pending.finish()
        }
    }

    private companion object {
        const val ACTION = "com.dallycontrol.agent.PUSH_TEST"
        const val EXTRA_TOKEN = "token"
        const val BUDGET_MS = 9_000L
        val scope = CoroutineScope(Dispatchers.IO)
    }
}

/** Hands the check-in cycle its view of the wake channels. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WakeModule {
    @Binds
    abstract fun bindWakeBridge(channel: WakeChannel): WakeBridge
}
