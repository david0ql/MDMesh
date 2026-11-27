package com.dallycontrol.agent.provisioning

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.dallycontrol.agent.admin.AdminReceiver
import com.dallycontrol.agent.service.CheckInService
import com.dallycontrol.core.config.ServerConfigStore
import com.dallycontrol.core.enroll.EnrollFailure
import com.dallycontrol.core.enroll.EnrollStatusStore
import com.dallycontrol.core.enroll.NetworkClock
import com.dallycontrol.core.store.DeviceIdStore
import com.dallycontrol.core.store.EnrollTokenStore
import com.dallycontrol.core.sync.CheckInCoordinator
import com.dallycontrol.core.sync.CheckInWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one way a provisioning payload (the QR's / zero-touch's / KME's admin extras) is taken in, whichever callback
 * Android delivers it to: [AdminPolicyComplianceActivity] on Android 12+, [AdminReceiver.onProfileProvisioningComplete]
 * and [ProvisioningSuccessActivity] before that. Every step is idempotent, so it does not matter that more than one of
 * them may fire for the same provisioning - which is what the older path got wrong: it stored the token but never
 * applied the Device-Owner baseline.
 */
object ProvisioningIntake {

    private const val TAG = "ProvisioningIntake"

    /**
     * Store server + token from [extras], apply the baseline, and start enrolling. Returns true when the payload
     * carried an enroll token. [done] runs once the token is on disk (a broadcast receiver finishes its goAsync there).
     */
    fun accept(context: Context, extras: PersistableBundle?, path: String, done: () -> Unit = {}): Boolean {
        val app = context.applicationContext
        // An enrolled agent is never re-pointed by a late or repeated provisioning callback.
        if (kotlinx.coroutines.runBlocking { DeviceIdStore(app).current() }.orEmpty().isNotBlank()) {
            ProvisioningBaseline.ensure(app)
            done()
            return false
        }
        // Server URL first (synchronous), so nothing scheduled below can run against the baked placeholder.
        ServerConfigStore(app).save(extras?.getString(AdminReceiver.EXTRA_SERVER_URL))
        EnrollStatusStore(app).setPath(path)
        ProvisioningBaseline.ensure(app)
        CheckInWorker.schedule(app)
        val token = extras?.getString(AdminReceiver.EXTRA_ENROLL_TOKEN)?.trim().orEmpty()
        scope.launch {
            try {
                // Never replace a token on a device that is already enrolled, and keep the one being used.
                if (token.isNotEmpty() && DeviceIdStore(app).current().isNullOrBlank()) {
                    EnrollTokenStore(app).save(token)
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "could not store the enroll token", e)
            } finally {
                CheckInWorker.scheduleNow(app)
                startService(app)
                EnrollRetry.start(app)
                done()
            }
        }
        return token.isNotEmpty()
    }

    /**
     * Use the payload kept at GET_PROVISIONING_MODE when the agent is Device Owner, is not enrolled and has no token:
     * the callback that should have delivered it did not come (yet). Safe to call any number of times.
     */
    fun adoptPending(context: Context): Boolean {
        val app = context.applicationContext
        val status = EnrollStatusStore(app)
        val pending = status.pending() ?: return false
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val wanted = dpm?.isDeviceOwnerApp(app.packageName) == true && kotlinx.coroutines.runBlocking {
            DeviceIdStore(app).current().isNullOrBlank() && EnrollTokenStore(app).token().isNullOrBlank()
        }
        if (wanted) {
            Log.i(TAG, "no provisioning callback delivered the payload; using the copy from GET_PROVISIONING_MODE")
            val extras = PersistableBundle().apply {
                putString(AdminReceiver.EXTRA_ENROLL_TOKEN, pending.first)
                pending.second?.let { putString(AdminReceiver.EXTRA_SERVER_URL, it) }
            }
            accept(app, extras, EnrollStatusStore.PATH_QR_EARLY)
        }
        return wanted
    }

    /** The console's QR scanned from the agent's own screen ([EnrollScanActivity]). */
    fun acceptScanned(context: Context, qr: com.dallycontrol.core.enroll.EnrollQr) {
        val app = context.applicationContext
        EnrollStatusStore(app).apply {
            clearFailure()
            setPath(EnrollStatusStore.PATH_QR_AGENT, replace = true)
        }
        EnrollPrompt.dismiss(app)
        val extras = PersistableBundle().apply {
            putString(AdminReceiver.EXTRA_ENROLL_TOKEN, qr.token)
            qr.serverUrl?.let { putString(AdminReceiver.EXTRA_SERVER_URL, it) }
        }
        accept(app, extras, EnrollStatusStore.PATH_QR_AGENT)
    }

    /** Check for a pending payload now and a few more times: Device Owner is set moments after the admin is enabled. */
    fun adoptPendingSoon(context: Context) {
        val app = context.applicationContext
        scope.launch {
            for (waitMs in ADOPT_WAITS_MS) {
                delay(waitMs)
                val settled = EnrollStatusStore(app).pending() == null ||
                    runCatching { adoptPending(app) }.getOrDefault(false)
                if (settled) break
            }
        }
    }

    @Suppress("MagicNumber") // seconds after the admin was enabled: 3 s, 15 s, 45 s, 2 min
    private val ADOPT_WAITS_MS = longArrayOf(3_000, 12_000, 30_000, 75_000)

    /** The wake channel: a Device Owner may start it from the background (same as the USB path does). */
    fun startService(app: Context) {
        runCatching { ContextCompat.startForegroundService(app, Intent(app, CheckInService::class.java)) }
            .onFailure { Log.w(TAG, "could not start CheckInService: ${it.message}") }
    }

    @Suppress("DEPRECATION") // typed getParcelableExtra is API 33+
    fun extrasOf(intent: Intent?): PersistableBundle? =
        intent?.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/**
 * Keeps asking the server to enroll, with a growing wait, until it works or the server gives an answer that only a
 * person can fix. WorkManager's own retry starts at 30 s and doubles; right after provisioning - someone is holding
 * the phone and watching - that is too slow, and a one-shot job that already failed is not retried at all.
 */
object EnrollRetry {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun coordinator(): CheckInCoordinator
    }

    private val running = AtomicBoolean(false)
    @Suppress("MagicNumber") // the waits themselves: quick at first (someone is watching), then once a minute
    private val WAITS_SEC = longArrayOf(3, 5, 10, 20, 30, 60)
    private const val MAX_MS = 30L * 60L * 1000L
    private const val SECOND_MS = 1000L
    private const val TAG = "EnrollRetry"

    /** Start the loop unless it is already running. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (!running.compareAndSet(false, true)) return
        ProvisioningIntake.scope.launch {
            try {
                loop(app)
            } finally {
                running.set(false)
            }
        }
    }

    /** A person asked: forget the last failure and try right now. */
    fun now(context: Context) {
        val app = context.applicationContext
        EnrollStatusStore(app).clearFailure()
        CheckInWorker.scheduleNow(app)
        ProvisioningIntake.startService(app)
        if (running.get()) {
            ProvisioningIntake.scope.launch { attempt(app) }
        } else {
            start(app)
        }
    }

    private suspend fun loop(app: Context) {
        val ids = DeviceIdStore(app)
        val status = EnrollStatusStore(app)
        val deadline = System.currentTimeMillis() + MAX_MS
        var round = 0
        var go = ids.current().isNullOrBlank()
        while (go) {
            attempt(app)
            val failure = status.snapshot().errorCode
            // Stop when enrolled, when only a person can fix it (spent or expired code), or after half an hour
            // (the periodic check-in keeps trying after that).
            go = ids.current().isNullOrBlank() && System.currentTimeMillis() < deadline &&
                !(failure != null && EnrollFailure(failure, "").isTerminal)
            if (go) delay(WAITS_SEC[minOf(round++, WAITS_SEC.size - 1)] * SECOND_MS)
        }
    }

    private suspend fun attempt(app: Context) {
        runCatching {
            EntryPointAccessors.fromApplication(app, Deps::class.java).coordinator().runOnce()
        }.onFailure { Log.i(TAG, "enroll attempt failed: ${it.message}") }
    }
}

/**
 * A phone whose clock is years off rejects every HTTPS certificate, so it can neither enroll nor learn the time over
 * HTTPS. As Device Owner (Android 9+) the agent may set the clock: it takes the time from the server's plain-HTTP
 * `Date` header ([NetworkClock]) - only when a certificate-date failure was just seen and the clock is off by more
 * than a day.
 */
object ClockRepair {
    private const val TAG = "ClockRepair"

    fun tryRepair(context: Context): Boolean {
        val app = context.applicationContext
        return runCatching {
            val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val allowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpm.isDeviceOwnerApp(app.packageName)
            val serverNow = if (allowed) NetworkClock.fetch(ServerConfigStore(app).baseUrl()) else null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && serverNow != null &&
                NetworkClock.needsRepair(System.currentTimeMillis(), serverNow)
            ) {
                val ok = setTime(dpm, AdminReceiver.componentName(app), serverNow)
                Log.i(TAG, "clock was wrong; set from the server's Date header: $ok")
                ok
            } else {
                false
            }
        }.getOrElse {
            Log.w(TAG, "could not repair the clock: ${it.message}")
            false
        }
    }

    /** setTime refuses while automatic time is on, so switch it off for the call and back on after. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun setTime(dpm: DevicePolicyManager, admin: android.content.ComponentName, millis: Long): Boolean {
        if (dpm.setTime(admin, millis)) return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dpm.setAutoTimeEnabled(admin, false)
            try {
                dpm.setTime(admin, millis)
            } finally {
                dpm.setAutoTimeEnabled(admin, true)
            }
        } else {
            @Suppress("DEPRECATION")
            dpm.setGlobalSetting(admin, android.provider.Settings.Global.AUTO_TIME, "0")
            try {
                dpm.setTime(admin, millis)
            } finally {
                @Suppress("DEPRECATION")
                dpm.setGlobalSetting(admin, android.provider.Settings.Global.AUTO_TIME, "1")
            }
        }
    }
}
