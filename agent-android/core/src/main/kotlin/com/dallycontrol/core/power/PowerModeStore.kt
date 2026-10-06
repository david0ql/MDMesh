package com.dallycontrol.core.power

import android.content.Context
import com.dallycontrol.proto.DeviceAction
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The connectivity power mode: always [DeviceAction.POWER_ALWAYS_ON] — the wake socket stays hot 24/7 so commands,
 * installs and remote sessions reach the phone at once. The battery-saving "adaptive" mode left field phones silent
 * for long stretches with the screen off (HyperOS stretching the heartbeat), so it is no longer offered: a
 * `device.powerMode` asking for it keeps the phone always on.
 */
@Singleton
class PowerModeStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("mdm_power", Context.MODE_PRIVATE)

    fun get(): String = DeviceAction.POWER_ALWAYS_ON

    @Suppress("UNUSED_PARAMETER")
    fun set(mode: String) {
        prefs.edit().putString(KEY, DeviceAction.POWER_ALWAYS_ON).apply()
    }

    fun isAlwaysOn(): Boolean = get() == DeviceAction.POWER_ALWAYS_ON

    private companion object { const val KEY = "mode" }
}
