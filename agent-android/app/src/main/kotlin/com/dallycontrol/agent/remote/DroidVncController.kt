package com.dallycontrol.agent.remote

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.RemoteControlCapability
import com.dallycontrol.proto.RemoteControlTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remote view + control through droidVNC-NG (ADR 0010): an open-source VNC server app, deployed and
 * configured by this Device Owner, driven through its documented Intent interface
 * (https://github.com/bk138/droidVNC-NG/blob/master/doc/Intent-Interface.md).
 *
 * For a session the device dials OUT to the server's Mode-II repeater with a one-time session id, so it
 * works behind NAT / on mobile data with no inbound port. The server never sees a decoded frame.
 *
 * Unattended use needs two grants a Device Owner cannot give itself, made once at USB enrollment
 * (`scripts/adb-enroll.sh --remote`): the PROJECT_MEDIA app-op for droidVNC-NG (no capture consent
 * dialog) and WRITE_SECURE_SETTINGS for this agent (so it can keep droidVNC-NG's input service on).
 */
@Singleton
class DroidVncController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val handle: DpmHandle,
) {
    private val prefs = context.getSharedPreferences("mdm_remote_vnc", Context.MODE_PRIVATE)

    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    }.getOrDefault(false)

    fun isInputServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.split(':')?.any { it.equals(INPUT_SERVICE, ignoreCase = true) } == true
    }

    /** What this device can offer the console: none / view (capture only) / control (capture + input). */
    fun capability(): RemoteControlCapability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInstalled()) return RemoteControlCapability()
        val control = isInputServiceEnabled() || canWriteSecureSettings(context)
        return RemoteControlCapability(
            tier = if (control) RemoteControlTier.CONTROL else RemoteControlTier.VIEW,
            screenCapture = true,
            inputInjection = control,
            transport = listOf(TRANSPORT, TRANSPORT_WSS),
        )
    }

    /**
     * Prepare droidVNC-NG for unattended use. Idempotent; cheap enough to run before every session:
     * managed restrictions (per-device access key, never listening, no start-on-boot), notification
     * permission, protection from force-stop (a force-stop also drops its accessibility grant), and
     * its input service switched on when we may write secure settings.
     */
    fun prepare(): String? {
        if (!isInstalled()) return "droidVNC-NG ($PACKAGE) is not installed"
        runCatching { com.dallycontrol.agent.admin.AdminReceiver.protectProcesses(context) }
        val dpm = handle.dpm
        val configured = runCatching {
            dpm.setApplicationRestrictions(handle.admin, PACKAGE, restrictions(accessKey()))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                dpm.setPermissionGrantState(
                    handle.admin, PACKAGE, android.Manifest.permission.POST_NOTIFICATIONS,
                    android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val current = dpm.getUserControlDisabledPackages(handle.admin)
                if (PACKAGE !in current) {
                    dpm.setUserControlDisabledPackages(handle.admin, current + PACKAGE)
                }
            }
        }
        ensureInputService()
        allowInKiosk()
        return configured.exceptionOrNull()?.let { "cannot configure droidVNC-NG: ${it.message}" }
    }

    /**
     * Start the VNC server (not listening), wait until droidVNC-NG confirms it is up, then dial the
     * repeater and wait for that answer too. droidVNC-NG stops itself when asked to dial before its server
     * is active, so the two steps must be sequenced on its answers. Returns an error, or null once the
     * device is connected to the repeater.
     */
    suspend fun startSession(
        sessionId: String,
        password: String?,
        viewOnly: Boolean,
        host: String,
        port: Int,
    ): String? {
        prepare()?.let { return it }
        val start = serviceIntent(ACTION_START).apply {
            putExtra(EXTRA_PORT, -1) // never accept inbound connections
            putExtra(EXTRA_VIEW_ONLY, viewOnly)
            if (!password.isNullOrEmpty()) putExtra(EXTRA_PASSWORD, password)
            putExtra(EXTRA_REQUEST_ID, "$sessionId-start")
            // Capture through droidVNC-NG's accessibility service instead of waiting for the screen-capture
            // consent, which a kiosk never lets show (or a HyperOS reboot resets). Where screen capture is
            // allowed, droidVNC-NG upgrades to it by itself once the console connects.
            putExtra(EXTRA_FALLBACK_SCREEN_CAPTURE, usesFallbackCapture())
        }
        val connect = serviceIntent(ACTION_CONNECT_REPEATER).apply {
            putExtra(EXTRA_HOST, host)
            putExtra(EXTRA_PORT, port)
            putExtra(EXTRA_REPEATER_ID, sessionId)
            putExtra(EXTRA_REQUEST_ID, sessionId)
            putExtra(EXTRA_RECONNECT_TRIES, RECONNECT_TRIES)
        }
        // A fresh server per session: droidVNC-NG refuses START while active (and would keep the previous
        // session's password), so stop whatever runs first and wait for it to answer.
        request(serviceIntent(ACTION_STOP).putExtra(EXTRA_REQUEST_ID, "$sessionId-stop"), ACTION_STOP,
            "$sessionId-stop", STOP_TIMEOUT_MS, foreground = false)
        val started = request(start, ACTION_START, "$sessionId-start", START_TIMEOUT_MS)
        return when {
            started == null && !usesFallbackCapture() ->
                "droidVNC-NG did not confirm its start (screen-capture permission missing and its input service is off)"
            started == null -> "droidVNC-NG did not confirm its start"
            !started -> "droidVNC-NG failed to start"
            request(connect, ACTION_CONNECT_REPEATER, sessionId, CONNECT_TIMEOUT_MS) != true ->
                "droidVNC-NG could not reach the repeater at $host:$port"
            else -> null
        }
    }

    /** Send [intent] to droidVNC-NG; await its answer for [requestId]: success, or null on timeout. */
    private suspend fun request(
        intent: Intent,
        action: String,
        requestId: String,
        timeoutMs: Long,
        foreground: Boolean = true,
    ): Boolean? {
        val answer = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getStringExtra(EXTRA_REQUEST_ID) == requestId) {
                    answer.complete(i.getBooleanExtra(EXTRA_REQUEST_SUCCESS, false))
                }
            }
        }
        // droidVNC-NG answers with an implicit broadcast from another app, so the receiver must be exported.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        return try {
            if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
            withTimeoutOrNull(timeoutMs) { answer.await() }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "droidVNC-NG request $action failed", e)
            false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun stopSession(): String? = runCatching {
        if (!isInstalled()) return null
        context.startService(serviceIntent(ACTION_STOP))
        null
    }.getOrElse {
        // Android refuses to start a service of an app that is not running: droidVNC-NG is already stopped.
        if (it is IllegalStateException && it.message?.contains("Not allowed to start service") == true) null
        else "could not stop droidVNC-NG: ${it.message}"
    }

    private fun serviceIntent(action: String) = Intent(action).apply {
        component = ComponentName(PACKAGE, "$PACKAGE.MainService")
        putExtra(EXTRA_ACCESS_KEY, accessKey())
    }

    /** Per-device secret for droidVNC-NG's Intent interface, delivered to it via managed restrictions. */
    private fun accessKey(): String = prefs.getString(KEY_ACCESS, null) ?: run {
        val bytes = ByteArray(ACCESS_KEY_BYTES).also { SecureRandom().nextBytes(it) }
        bytes.joinToString("") { "%02x".format(it) }.also { prefs.edit().putString(KEY_ACCESS, it).apply() }
    }

    /**
     * A device in kiosk (lock task) can only start allowlisted packages, and droidVNC-NG's capture start opens an
     * activity of its own. Kiosks applied by this agent already allowlist it; this also covers kiosks applied before
     * that (or by an older agent) without re-applying the configuration. No-op when not in kiosk.
     */
    /**
     * Accessibility-screenshot capture: Android 11+ with droidVNC-NG's input service on, and its screen-capture
     * app-op not granted (granted, it captures silently at full speed; the fallback would ask on the phone to upgrade).
     */
    private fun usesFallbackCapture(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isInputServiceEnabled() && !screenCaptureAllowed()

    /** droidVNC-NG's PROJECT_MEDIA app-op is "allow" (set by the cable/Wi-Fi enroller). Unknown counts as not. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun screenCaptureAllowed(): Boolean = runCatching {
        val uid = context.packageManager.getApplicationInfo(PACKAGE, 0).uid
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(OP_PROJECT_MEDIA, uid, PACKAGE) == android.app.AppOpsManager.MODE_ALLOWED
    }.onFailure { Log.i(TAG, "cannot read droidVNC-NG screen-capture app-op: ${it.message}") }.getOrDefault(false)

    private fun allowInKiosk() {
        if (android.os.Build.VERSION.SDK_INT < 26) return // getLockTaskPackages is API 26; older kiosks listed it at entry
        runCatching {
            val current = handle.dpm.getLockTaskPackages(handle.admin)
            if (current.isNotEmpty() && PACKAGE !in current) {
                handle.dpm.setLockTaskPackages(handle.admin, current + PACKAGE)
            }
        }.onFailure { Log.w(TAG, "could not allow droidVNC-NG in kiosk", it) }
    }

    private fun ensureInputService() {
        if (isInputServiceEnabled() || !canWriteSecureSettings(context)) return
        runCatching {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.takeIf { it.isNotBlank() && it != "null" }
            val updated = listOfNotNull(current, INPUT_SERVICE).joinToString(":")
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        }.onFailure { Log.w(TAG, "could not enable droidVNC-NG input service", it) }
    }

    companion object {
        const val PACKAGE = "net.christianbeier.droidvnc_ng"
        const val INPUT_SERVICE = "$PACKAGE/$PACKAGE.InputService"
        const val TRANSPORT = "vnc-repeater"
        /** The repeater connection tunnelled over the server's HTTPS origin ([com.dallycontrol.core.remote.RepeaterTunnel]). */
        const val TRANSPORT_WSS = "vnc-repeater-wss"
        const val DEFAULT_REPEATER_PORT = 5500
        private const val ACTION_START = "$PACKAGE.ACTION_START"
        private const val ACTION_STOP = "$PACKAGE.ACTION_STOP"
        private const val ACTION_CONNECT_REPEATER = "$PACKAGE.ACTION_CONNECT_REPEATER"
        private const val EXTRA_ACCESS_KEY = "$PACKAGE.EXTRA_ACCESS_KEY"
        private const val EXTRA_REQUEST_ID = "$PACKAGE.EXTRA_REQUEST_ID"
        private const val EXTRA_PORT = "$PACKAGE.EXTRA_PORT"
        private const val EXTRA_PASSWORD = "$PACKAGE.EXTRA_PASSWORD"
        private const val EXTRA_VIEW_ONLY = "$PACKAGE.EXTRA_VIEW_ONLY"
        private const val EXTRA_FALLBACK_SCREEN_CAPTURE = "$PACKAGE.EXTRA_FALLBACK_SCREEN_CAPTURE"
        private const val OP_PROJECT_MEDIA = "android:project_media"
        private const val EXTRA_HOST = "$PACKAGE.EXTRA_HOST"
        private const val EXTRA_REPEATER_ID = "$PACKAGE.EXTRA_REPEATER_ID"
        private const val EXTRA_RECONNECT_TRIES = "$PACKAGE.EXTRA_RECONNECT_TRIES"
        private const val RECONNECT_TRIES = 3
        private const val START_TIMEOUT_MS = 90_000L // a first start after install compiles + loads native code
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val EXTRA_REQUEST_SUCCESS = "$PACKAGE.EXTRA_REQUEST_SUCCESS"
        private const val ACCESS_KEY_BYTES = 16
        private const val KEY_ACCESS = "access_key"
        private const val TAG = "DroidVnc"
    }
}

/**
 * droidVNC-NG's managed restrictions: our access key, never listening, no start-on-boot, and the key chords
 * for the system actions pinned to droidVNC-NG's defaults. The console's navigation bar (Back, Home, Recents,
 * Power, Volume, Rotate) sends exactly these, so a chord changed on the device must not break it.
 */
private fun restrictions(accessKey: String) = Bundle().apply {
    putString("accessKey", accessKey)
    putBoolean("startOnBoot", false)
    putInt("port", -1)
    putBoolean("showPointers", true)
    putBoolean("fileTransfer", false)
    putString("chordBack", "Escape")
    putString("chordHome", "Home")
    putString("chordRecents", "Control_L+Shift_L+Escape")
    putString("chordPower", "End")
    putString("chordVolumeUp", "Control_L+Alt_L+Page_Up")
    putString("chordVolumeDown", "Control_L+Alt_L+Page_Down")
    putString("chordRotate", "Control_L+Alt_L+Delete")
}

private fun canWriteSecureSettings(context: Context): Boolean =
    context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED
