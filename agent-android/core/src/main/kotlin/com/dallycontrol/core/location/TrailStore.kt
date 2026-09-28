package com.dallycontrol.core.location

import android.content.Context
import com.dallycontrol.proto.LocationDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The location trail (ConfigTracking): the interval set by the configuration, and the fixes captured between
 * check-ins. Fixes stay until a check-in that carried them succeeds ([ack]), so a phone offline for hours
 * uploads its whole route when it reconnects. Bounded to [CAP] fixes (a day at one every 5 minutes).
 */
@Singleton
class TrailStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("mdm_trail", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LocationDto.serializer())

    /** Minutes between fixes; 0 = no trail. */
    fun intervalMinutes(): Int = prefs.getInt(KEY_INTERVAL, 0)

    fun setIntervalMinutes(minutes: Int) {
        val m = minutes.coerceIn(0, 1440)
        prefs.edit().putInt(KEY_INTERVAL, m).apply()
        if (m == 0) prefs.edit().remove(KEY_FIXES).apply()
    }

    @Synchronized
    fun add(fix: LocationDto) {
        val all = pending().filter { it.capturedAt != fix.capturedAt } + fix
        write(all.sortedBy { it.capturedAt }.takeLast(CAP))
    }

    @Synchronized
    fun pending(): List<LocationDto> = runCatching {
        json.decodeFromString(serializer, prefs.getString(KEY_FIXES, null) ?: return emptyList())
    }.getOrDefault(emptyList())

    /** Drop the fixes a successful check-in carried (captured at or before [upTo]). */
    @Synchronized
    fun ack(upTo: Long) {
        write(pending().filter { it.capturedAt > upTo })
    }

    private fun write(list: List<LocationDto>) {
        prefs.edit().putString(KEY_FIXES, json.encodeToString(serializer, list)).apply()
    }

    companion object {
        const val CAP = 288
        private const val KEY_INTERVAL = "interval_min"
        private const val KEY_FIXES = "fixes"
    }
}
