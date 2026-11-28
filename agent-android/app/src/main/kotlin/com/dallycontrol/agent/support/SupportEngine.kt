package com.dallycontrol.agent.support

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.dallycontrol.agent.admin.AdminReceiver
import com.dallycontrol.core.support.SupportReadiness
import com.dallycontrol.core.support.SupportSettings
import com.dallycontrol.proto.ConfigRemoteSupport
import com.dallycontrol.proto.RemoteControlCapability
import com.dallycontrol.proto.RemoteControlTier
import com.dallycontrol.proto.SupportCapability
import com.dallycontrol.proto.SupportScreenState
import kotlinx.coroutines.delay

/**
 * State of the agent's own remote-support engine (ADR 0012): the policy's settings, whether the screen service is
 * switched on, and switching it on when Android lets the agent do it.
 *
 * What a Device Owner can and cannot do here (verified on emulators, see docs/REMOTE-SUPPORT.md):
 *  - it CANNOT grant itself WRITE_SECURE_SETTINGS (`setPermissionGrantState` only takes runtime permissions),
 *  - it CANNOT write `enabled_accessibility_services` (`setSecureSetting` allows two unrelated keys),
 *  - so on a QR / zero-touch enrollment the service needs ONE tap on the phone, once ([RemoteSetupActivity]);
 *  - with WRITE_SECURE_SETTINGS (granted over USB by the enroller) the agent switches it on by itself.
 * Both attempts are still made once per start ([probeDeviceOwnerRoutes]) and their outcome reported, so a release
 * of Android that allows one is used without a new agent.
 */
@Suppress("TooManyFunctions") // one small question or action each; they share the stored settings
object SupportEngine {
    private const val TAG = "SupportEngine"
    private const val PREFS = "mdm_support"
    private const val KEY_ENGINE = "engine"
    private const val KEY_NOTICE = "notice"
    private const val KEY_CALL = "callAccept"
    private const val KEY_PROMPT = "setupPrompt"
    private const val KEY_PROMPTED = "prompted"
    private const val SERVICE_WAIT_MS = 4_000L

    /** Told when the screen service connects or goes away (the session and the next check-in report it). */
    val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    private fun notifyChanged() = listeners.forEach { runCatching { it() } }
    /** Outcome of the Device Owner self-authorisation attempts, for diagnosis. */
    @Volatile var probe: String = "not run"
        private set
    @Volatile private var probed = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun component(context: Context) = ComponentName(context, SupportAccessibilityService::class.java)

    fun settings(context: Context): SupportSettings {
        val p = prefs(context)
        return SupportSettings.of(
            p.getString(KEY_ENGINE, null),
            if (p.contains(KEY_NOTICE)) p.getBoolean(KEY_NOTICE, true) else null,
            p.getString(KEY_CALL, null),
            if (p.contains(KEY_PROMPT)) p.getBoolean(KEY_PROMPT, true) else null,
        )
    }

    /** The configuration's remote-support section (null = defaults). */
    fun applySettings(context: Context, config: ConfigRemoteSupport?) {
        prefs(context).edit().apply {
            if (config?.engine != null) putString(KEY_ENGINE, config.engine) else remove(KEY_ENGINE)
            if (config?.notice != null) putBoolean(KEY_NOTICE, config.notice!!) else remove(KEY_NOTICE)
            if (config?.callAccept != null) putString(KEY_CALL, config.callAccept) else remove(KEY_CALL)
            if (config?.setupPrompt != null) putBoolean(KEY_PROMPT, config.setupPrompt!!) else remove(KEY_PROMPT)
        }.apply()
        syncComponent(context)
        notifyChanged()
        maybePrompt(context)
    }

    /** The screen service exists (listed in Accessibility) only while the policy allows the built-in engine. */
    fun syncComponent(context: Context) {
        // Also while a kiosk is on: the same service is the kiosk's guard (docs/KIOSK-HARDENING.md).
        val kiosk = com.dallycontrol.agent.kiosk.AndroidKioskLockdown.engaged(context)
        val wanted = if (settings(context).builtinAllowed || kiosk) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(component(context)) != wanted) {
                pm.setComponentEnabledSetting(component(context), wanted, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { Log.w(TAG, "could not sync the screen service component", it) }
    }

    fun isServiceOn(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val mine = component(context)
        return enabled?.split(':')?.any { ComponentName.unflattenFromString(it) == mine } == true
    }

    fun canSelfEnable(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun hasMicrophone(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** As Device Owner, grant the agent its own microphone permission (voice calls of a support session). */
    fun grantMicrophone(context: Context): Boolean {
        if (hasMicrophone(context)) return true
        runCatching {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setPermissionGrantState(
                    AdminReceiver.componentName(context), context.packageName, Manifest.permission.RECORD_AUDIO,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }.onFailure { Log.w(TAG, "could not grant the microphone", it) }
        return hasMicrophone(context)
    }

    /**
     * Switch the screen service on if the agent may. True when it is (or was already) on in the settings.
     * A service enabled this way connects a moment later: see [awaitService].
     */
    @Suppress("ReturnCount") // not allowed / already on / cannot / switched on
    fun ensureServiceOn(context: Context): Boolean {
        if (!settings(context).builtinAllowed || Build.VERSION.SDK_INT < SupportReadiness.MIN_SDK_CONTROL) return false
        syncComponent(context)
        if (isServiceOn(context)) return true
        if (!canSelfEnable(context)) probeDeviceOwnerRoutes(context)
        if (!canSelfEnable(context)) return isServiceOn(context)
        return runCatching {
            val resolver = context.contentResolver
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.takeIf { it.isNotBlank() && it != "null" }
            val updated = listOfNotNull(current, component(context).flattenToString()).joinToString(":")
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            Log.i(TAG, "screen service switched on by the agent")
            true
        }.getOrElse {
            Log.w(TAG, "could not switch the screen service on", it)
            false
        }
    }

    /**
     * The two things a Device Owner could try to authorise itself. Android refuses both today (see the class
     * comment); they cost nothing and are tried once per process so the outcome is on record.
     */
    fun probeDeviceOwnerRoutes(context: Context) {
        if (probed) return
        probed = true
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            probe = "not device owner"
            return
        }
        val admin = AdminReceiver.componentName(context)
        val grant = runCatching {
            dpm.setPermissionGrantState(
                admin, context.packageName, Manifest.permission.WRITE_SECURE_SETTINGS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }.fold({ if (canSelfEnable(context)) "granted" else "refused($it)" }, { "threw ${it.javaClass.simpleName}" })
        val secure = if (canSelfEnable(context)) "skipped" else runCatching {
            val current = Settings.Secure
                .getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.takeIf { it.isNotBlank() && it != "null" }
            dpm.setSecureSetting(
                admin, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                listOfNotNull(current, component(context).flattenToString()).joinToString(":"),
            )
        }.fold(
            { if (isServiceOn(context)) "written" else "ignored" },
            { "threw ${it.javaClass.simpleName} (${it.message?.take(PROBE_MESSAGE_MAX)})" },
        )
        // Usage access (the kiosk guard's other source) is an app-op behind a signature permission: same answer.
        val usage = runCatching {
            dpm.setPermissionGrantState(
                admin, context.packageName, Manifest.permission.PACKAGE_USAGE_STATS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }.fold(
            {
                val held = context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
                if (held) "granted" else "refused($it)"
            },
            { "threw ${it.javaClass.simpleName}" },
        )
        probe = "sdk=${Build.VERSION.SDK_INT} grantWriteSecureSettings=$grant setSecureSetting=$secure " +
            "grantUsageAccess=$usage"
        Log.i(TAG, "device-owner self-authorisation: $probe")
    }

    /** The connected service, waiting a moment for one that was just switched on. */
    @Suppress("ReturnCount")
    suspend fun awaitService(context: Context, timeoutMs: Long = SERVICE_WAIT_MS): SupportAccessibilityService? {
        SupportAccessibilityService.instance?.let { return it }
        if (!ensureServiceOn(context)) return null
        // Switched on in the settings but not bound yet (the agent was just updated, the phone just booted): Android
        // binds it by itself within some seconds, so that case waits longer than a service that was just enabled.
        val until = System.currentTimeMillis() + maxOf(timeoutMs, REBIND_WAIT_MS)
        while (System.currentTimeMillis() < until) {
            SupportAccessibilityService.instance?.let { return it }
            delay(POLL_MS)
        }
        return SupportAccessibilityService.instance
    }

    private const val POLL_MS = 100L
    private const val PROBE_MESSAGE_MAX = 120
    private const val REBIND_WAIT_MS = 20_000L

    internal fun onServiceChanged() {
        notifyChanged()
    }

    fun screenState(context: Context): String = SupportReadiness.screen(
        Build.VERSION.SDK_INT, settings(context), isServiceOn(context), canSelfEnable(context),
    )

    fun supportCapability(context: Context): SupportCapability {
        val screen = screenState(context)
        val ready = screen == SupportScreenState.READY
        return SupportCapability(
            session = true,
            screen = screen,
            control = ready,
            voice = if (hasMicrophone(context) || grantMicrophone(context)) "ready" else "needsPermission",
            capture = if (ready) SupportReadiness.capture(Build.VERSION.SDK_INT) else "none",
            selfEnable = canSelfEnable(context),
        )
    }

    /** What the device offers: droidVNC-NG's capability (ADR 0010) plus the agent's own engine. */
    fun capability(context: Context, vnc: RemoteControlCapability): RemoteControlCapability {
        val support = supportCapability(context)
        if (support.screen != SupportScreenState.READY) return vnc.copy(support = support)
        return vnc.copy(
            tier = RemoteControlTier.CONTROL,
            screenCapture = true,
            inputInjection = true,
            transport = (vnc.transport + TRANSPORT).distinct(),
            support = support,
        )
    }

    /**
     * Whether the one-time authorisation should be asked for now: once, while the phone is being enrolled (the agent
     * was installed less than [ENROLL_WINDOW_MS] ago), when the policy allows it. A phone already in the field that
     * receives this agent as an update is never interrupted: the console asks there (`remote.inputSetup`).
     */
    @Suppress("ReturnCount") // each line is one reason not to ask
    fun shouldPrompt(context: Context): Boolean {
        val s = settings(context)
        if (!s.setupPrompt || prefs(context).getBoolean(KEY_PROMPTED, false)) return false
        if (screenState(context) != SupportScreenState.NEEDS_AUTHORIZATION) return false
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) return false
        val installedAt = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        }.getOrDefault(0L)
        return System.currentTimeMillis() - installedAt < ENROLL_WINDOW_MS
    }

    /** Show the guided authorisation step if this is the moment for it. */
    fun maybePrompt(context: Context) {
        if (!shouldPrompt(context)) return
        probeDeviceOwnerRoutes(context)
        if (isServiceOn(context)) return
        markPrompted(context)
        RemoteSetupActivity.open(context)
    }

    private const val ENROLL_WINDOW_MS = 60 * 60_000L

    fun markPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    const val TRANSPORT = "support-ws"
}
