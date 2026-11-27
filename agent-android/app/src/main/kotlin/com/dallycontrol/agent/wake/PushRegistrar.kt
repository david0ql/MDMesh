package com.dallycontrol.agent.wake

import android.content.Context
import android.util.Log
import com.dallycontrol.core.sync.CheckInWorker
import com.dallycontrol.core.wake.WakeState
import com.dallycontrol.proto.PushConfig
import com.dallycontrol.proto.PushState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Registers the agent with the server's Firebase project and keeps the token in [WakeState].
 *
 * The project's client values come from the server with each check-in answer, so the APK ships **no**
 * `google-services.json` and one build serves any installation: [FirebaseApp] is initialized by hand with those
 * values. A phone without Google services (or a server without a project) simply has no token; the agent then
 * stays on the live socket and its own backup check-ins, exactly as before this channel existed.
 */
object PushRegistrar {
    private const val TAG = "PushRegistrar"

    /** After a failed attempt, wait before asking Google again (every check-in passes through here). */
    private const val RETRY_AFTER_MS = 30L * 60_000L

    @Volatile private var lastAttemptAt = 0L
    @Volatile private var inFlight = false

    @Synchronized
    fun ensure(context: Context, state: WakeState, config: PushConfig?) {
        val app = context.applicationContext
        val appId = config?.takeIf { usable(it) }?.appIdFor(app.packageName)
        when {
            config == null || appId.isNullOrBlank() -> clear(state)
            !googleServicesPresent(app) -> {
                state.pushToken = null
                state.pushState = PushState.UNAVAILABLE
                state.pushError = "sin servicios de Google"
            }
            else -> runCatching {
                initialize(app, config, appId)
                requestToken(app, state, key(config, appId))
            }.onFailure {
                Log.w(TAG, "push registration could not start", it)
                inFlight = false
                fail(state, it)
            }
        }
    }

    private fun usable(config: PushConfig): Boolean =
        config.provider == PushState.PROVIDER_FCM && !config.projectId.isNullOrBlank() && !config.apiKey.isNullOrBlank()

    private fun key(config: PushConfig, appId: String): String =
        listOf(config.projectId, appId, config.apiKey, config.senderId.orEmpty()).joinToString("|")

    /** The server has no push project (any more): forget the registration. */
    private fun clear(state: WakeState) {
        if (state.pushToken == null && state.pushState == PushState.UNCONFIGURED) return
        state.pushToken = null
        state.pushProject = null
        state.pushError = null
        state.pushState = PushState.UNCONFIGURED
    }

    private fun requestToken(app: Context, state: WakeState, key: String) {
        val registered = state.pushProject == key && state.pushToken != null && state.pushState == PushState.READY
        val now = System.currentTimeMillis()
        if (registered || inFlight || now - lastAttemptAt < RETRY_AFTER_MS) return
        lastAttemptAt = now
        inFlight = true
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            inFlight = false
            if (task.isSuccessful && !task.result.isNullOrBlank()) {
                lastAttemptAt = 0L
                store(app, state, task.result, key)
            } else if (state.pushToken == null || state.pushState != PushState.READY) {
                // (A token that arrived on its own while this request was out stays.)
                fail(state, task.exception ?: IllegalStateException("sin token"))
            }
        }
    }

    /** Google rotated the token (or issued the first one): keep it and tell the server with a check-in. */
    fun onNewToken(context: Context, state: WakeState, token: String) {
        val config = state.config?.push
        val appId = config?.appIdFor(context.packageName)
        if (config != null && appId != null) store(context.applicationContext, state, token, key(config, appId))
    }

    private fun store(context: Context, state: WakeState, token: String, key: String) {
        val changed = state.pushToken != token || state.pushState != PushState.READY
        state.pushToken = token
        state.pushProject = key
        state.pushError = null
        state.pushState = PushState.READY
        if (changed) {
            Log.i(TAG, "push token registered")
            runCatching { CheckInWorker.scheduleNow(context) } // report it without waiting for the next beat
        }
    }

    private fun fail(state: WakeState, error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        // The messages Google services answer with when they are missing, disabled or cannot reach Google.
        val unavailable = listOf("SERVICE_NOT_AVAILABLE", "MISSING_INSTANCEID_SERVICE", "PHONE_REGISTRATION_ERROR")
            .any { message.contains(it) }
        state.pushToken = null
        state.pushState = if (unavailable) PushState.UNAVAILABLE else PushState.ERROR
        state.pushError = message
        Log.w(TAG, "push registration failed: $message")
    }

    private fun googleServicesPresent(context: Context): Boolean = runCatching {
        val code = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context)
        code == ConnectionResult.SUCCESS || code == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED
    }.getOrDefault(false)

    private fun initialize(context: Context, config: PushConfig, appId: String) {
        val options = FirebaseOptions.Builder()
            .setProjectId(config.projectId!!)
            .setApplicationId(appId)
            .setApiKey(config.apiKey!!)
            .apply { config.senderId?.takeIf { it.isNotBlank() }?.let { setGcmSenderId(it) } }
            .build()
        val existing = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (existing != null) {
            if (existing.options == options) return
            existing.delete() // the server moved to another project: register with the new one
        }
        FirebaseApp.initializeApp(context, options)
    }
}
