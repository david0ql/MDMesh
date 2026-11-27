package com.dallycontrol.core.location

import android.content.Context
import com.dallycontrol.proto.DeviceAction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists how the agent captures location. Default [DeviceAction.LOCATION_PASSIVE]: read the OS's
 * last-known location each check-in (near-zero battery). [DeviceAction.LOCATION_ACTIVE] requests a
 * fresh GPS/fused fix each cycle (accurate, more battery). Set remotely via `device.locationMode`.
 */
@Singleton
class LocationModeStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("mdm_location", Context.MODE_PRIVATE)

    fun get(): String =
        prefs.getString(KEY, DeviceAction.LOCATION_PASSIVE) ?: DeviceAction.LOCATION_PASSIVE

    fun set(mode: String) {
        val normalized = if (mode == DeviceAction.LOCATION_ACTIVE) {
            DeviceAction.LOCATION_ACTIVE
        } else {
            DeviceAction.LOCATION_PASSIVE
        }
        prefs.edit().putString(KEY, normalized).apply()
    }

    fun isActive(): Boolean = get() == DeviceAction.LOCATION_ACTIVE

    /** Seconds an active fix may wait for a fresh position (5–60, default 25). */
    fun fixTimeoutSec(): Int = FixPolicy.clampTimeout(prefs.getInt(KEY_TIMEOUT, FixPolicy.DEFAULT_TIMEOUT_SEC))

    /** null restores the default. */
    fun setFixTimeoutSec(seconds: Int?) {
        if (seconds == null) {
            prefs.edit().remove(KEY_TIMEOUT).apply()
        } else {
            prefs.edit().putInt(KEY_TIMEOUT, FixPolicy.clampTimeout(seconds)).apply()
        }
    }

    private companion object {
        const val KEY = "mode"
        const val KEY_TIMEOUT = "fix_timeout_sec"
    }
}
