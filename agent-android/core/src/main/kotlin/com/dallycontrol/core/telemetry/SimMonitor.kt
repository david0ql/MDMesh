package com.dallycontrol.core.telemetry

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.dallycontrol.proto.SimSlotDto
import com.dallycontrol.proto.SimStateDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the SIM card(s) and records `simRemoved` / `simInserted` / `simChanged` events when they change (see
 * [SimDiff]). Called on every check-in and from the SIM-state broadcast, so a removal is reported within seconds
 * when the phone has a connection. Needs READ_PHONE_STATE (+ READ_PHONE_NUMBERS for the number), which the
 * provisioning baseline grants; without them it reports what it can.
 */
@Singleton
class SimMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: EventSink,
) {
    private val prefs = context.getSharedPreferences("mdm_sim", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** The current state; records events against the last baseline. */
    @Synchronized
    fun check(): SimStateDto? {
        val now = read() ?: return null
        val previous = prefs.getString(KEY_LAST, null)?.let { runCatching { json.decodeFromString(SimStateDto.serializer(), it) }.getOrNull() }
        SimDiff.events(previous, now).forEach { events.record(it.type, it.detail) }
        if (SimDiff.isBaseline(now)) prefs.edit().putString(KEY_LAST, json.encodeToString(SimStateDto.serializer(), now)).apply()
        return now
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun read(): SimStateDto? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return null
        val slotCount = if (Build.VERSION.SDK_INT >= 23) tm.phoneCount.coerceAtLeast(1) else 1
        val states = (0 until slotCount).map { slot ->
            if (Build.VERSION.SDK_INT >= 26) tm.getSimState(slot) else tm.simState
        }
        val state = when {
            states.any { it == TelephonyManager.SIM_STATE_READY } -> SimDiff.READY
            states.any { it in LOCKED_STATES } -> SimDiff.LOCKED
            states.all { it == TelephonyManager.SIM_STATE_ABSENT } -> SimDiff.ABSENT
            else -> SimDiff.UNKNOWN
        }
        val slots = if (granted(Manifest.permission.READ_PHONE_STATE) && Build.VERSION.SDK_INT >= 22) {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            runCatching { sm?.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList()).map { info ->
                SimSlotDto(
                    slot = info.simSlotIndex,
                    carrier = (info.carrierName ?: info.displayName)?.toString(),
                    number = number(sm, info),
                    countryIso = info.countryIso,
                    subscriptionId = info.subscriptionId,
                )
            }
        } else {
            emptyList()
        }
        return SimStateDto(state = state, slots = slots, fingerprint = SimDiff.fingerprint(slots))
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun number(sm: SubscriptionManager?, info: android.telephony.SubscriptionInfo): String? {
        if (!granted(Manifest.permission.READ_PHONE_NUMBERS) && !granted(Manifest.permission.READ_PHONE_STATE)) return null
        val n = runCatching {
            if (Build.VERSION.SDK_INT >= 33 && sm != null) sm.getPhoneNumber(info.subscriptionId) else info.number
        }.getOrNull()
        return n?.takeIf { it.isNotBlank() }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val KEY_LAST = "last"
        val LOCKED_STATES = setOf(
            TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED,
            TelephonyManager.SIM_STATE_NETWORK_LOCKED, TelephonyManager.SIM_STATE_PERM_DISABLED,
        )
    }
}
