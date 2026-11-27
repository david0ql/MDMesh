package com.dallycontrol.core.lost

import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.LostModeStatus
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * Payload of `device.lostMode`.
 *
 * @property enabled false ends lost mode and puts back what it changed.
 * @property message text for the lock screen ("Este equipo pertenece a …").
 * @property phone contact phone shown under the message.
 * @property ringSeconds ring this long when lost mode starts (0 / null = silent).
 * @property trackMinutes minutes between positions while lost (default 1).
 * @property wipeAfterFailedUnlocks wipe after this many wrong unlock attempts in a row (null / 0 = never).
 * @property wipeIfSilentDays wipe when the device has not reached the server for this many days (null / 0 = never).
 */
@Serializable
data class LostModePayload(
    val enabled: Boolean = true,
    val message: String? = null,
    val phone: String? = null,
    val ringSeconds: Int? = null,
    val trackMinutes: Int? = null,
    val wipeAfterFailedUnlocks: Int? = null,
    val wipeIfSilentDays: Int? = null,
)

/** What lost mode does to the device; the real one calls DevicePolicyManager and the agent's stores. */
interface LostModeDevice {
    /** Lock-screen owner text (null clears it). Android 7+; older versions ignore it. */
    fun setLockScreenInfo(text: String?)
    fun lockNow()
    fun ring(durationMs: Long)
    fun stopRing()
    fun trackingMinutes(): Int
    fun setTrackingMinutes(minutes: Int)
    fun locationMode(): String
    fun setLocationMode(mode: String)
    /** Wrong unlock attempts before the device wipes itself, asked by lost mode (0 = not asked). */
    fun setMaxFailedUnlocksForWipe(attempts: Int)
    /** Factory-reset the device keeping its reset protection (the "silent for N days" wipe). */
    fun wipeKeepingProtection()
}

/**
 * Lost mode: the phone locks at once showing who to call, rings, reports its position every minute, and — when
 * asked — wipes itself after N wrong unlocks or after N days without reaching the server (someone took the SIM out
 * and keeps it off Wi-Fi). Its state is kept on disk ([load]/[save]) so it survives restarts, and what it changed
 * (tracking interval, location mode) is remembered and put back when lost mode ends.
 */
class LostMode(
    private val device: LostModeDevice,
    private val load: () -> String?,
    private val save: (String?) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class State(
        val since: Long,
        val payload: LostModePayload,
        val previousTrackingMinutes: Int = 0,
        val previousLocationMode: String = DeviceAction.LOCATION_PASSIVE,
        val lastContactAt: Long,
    )

    @Synchronized
    fun state(): State? = runCatching {
        load()?.let { ProtocolJson.json.decodeFromString(State.serializer(), it) }
    }.getOrNull()

    fun isActive(): Boolean = state() != null

    /** @return the steps that failed (empty = everything applied). */
    @Synchronized
    fun apply(p: LostModePayload): List<String> = if (p.enabled) enable(p) else disable()

    private fun enable(p: LostModePayload): List<String> {
        val errors = ArrayList<String>()
        val current = state()
        val minutes = trackMinutes(p)
        // Remember what the policy had only when lost mode starts: a second "enable" must not record its own values.
        val state = State(
            since = current?.since ?: now(),
            payload = p,
            previousTrackingMinutes = current?.previousTrackingMinutes ?: runCatching { device.trackingMinutes() }.getOrDefault(0),
            previousLocationMode = current?.previousLocationMode
                ?: runCatching { device.locationMode() }.getOrDefault(DeviceAction.LOCATION_PASSIVE),
            lastContactAt = current?.lastContactAt ?: now(),
        )
        write(state)
        step(errors, "lockScreenMessage") { device.setLockScreenInfo(lockScreenText(p)) }
        step(errors, "tracking") {
            device.setLocationMode(DeviceAction.LOCATION_ACTIVE)
            device.setTrackingMinutes(minutes)
        }
        step(errors, "wipeAfterFailedUnlocks") { device.setMaxFailedUnlocksForWipe(p.wipeAfterFailedUnlocks?.coerceIn(0, MAX_UNLOCKS) ?: 0) }
        step(errors, "lock") { device.lockNow() }
        val ringMs = (p.ringSeconds ?: 0).coerceIn(0, MAX_RING_SECONDS) * 1000L
        if (ringMs > 0) step(errors, "ring") { device.ring(ringMs) }
        return errors
    }

    private fun disable(): List<String> {
        val errors = ArrayList<String>()
        val current = state()
        save(null)
        step(errors, "lockScreenMessage") { device.setLockScreenInfo(null) }
        step(errors, "wipeAfterFailedUnlocks") { device.setMaxFailedUnlocksForWipe(0) }
        step(errors, "ring") { device.stopRing() }
        if (current != null) {
            step(errors, "tracking") {
                device.setLocationMode(current.previousLocationMode)
                device.setTrackingMinutes(current.previousTrackingMinutes)
            }
        }
        return errors
    }

    /**
     * The policy sets a tracking interval while the device is lost: remember it for later and keep the lost-mode one.
     * @return the interval to use now.
     */
    @Synchronized
    fun trackingFromPolicy(minutes: Int): Int {
        val s = state() ?: return minutes
        write(s.copy(previousTrackingMinutes = minutes))
        return trackMinutes(s.payload)
    }

    /** Same as [trackingFromPolicy] for the location mode. */
    @Synchronized
    fun locationModeFromPolicy(mode: String): String {
        val s = state() ?: return mode
        write(s.copy(previousLocationMode = mode))
        return DeviceAction.LOCATION_ACTIVE
    }

    /** A check-in reached the server. */
    @Synchronized
    fun contact() {
        val s = state() ?: return
        write(s.copy(lastContactAt = now()))
    }

    /**
     * Called before every check-in attempt, with or without link: wipe when the device is lost, the order said
     * "wipe if silent for N days" and that long has passed since the last report that reached the server.
     * @return true when the wipe was started.
     */
    @Synchronized
    fun wipeIfSilentTooLong(): Boolean {
        val s = state() ?: return false
        if (!silentTooLong(s.payload.wipeIfSilentDays, s.lastContactAt, now())) return false
        return runCatching { device.wipeKeepingProtection(); true }.getOrDefault(false)
    }

    fun status(): LostModeStatus? = state()?.let {
        LostModeStatus(
            active = true,
            since = it.since,
            wipeAfterFailedUnlocks = it.payload.wipeAfterFailedUnlocks?.takeIf { n -> n > 0 },
            wipeIfSilentDays = it.payload.wipeIfSilentDays?.takeIf { n -> n > 0 },
            lastContactAt = it.lastContactAt,
        )
    }

    private fun write(s: State) = save(ProtocolJson.json.encodeToString(State.serializer(), s))

    private inline fun step(errors: MutableList<String>, name: String, block: () -> Unit) {
        runCatching(block).onFailure { errors.add("$name: ${it.javaClass.simpleName}: ${it.message ?: "no message"}") }
    }

    companion object {
        const val DEFAULT_TRACK_MINUTES = 1
        private const val MAX_TRACK_MINUTES = 60
        private const val MAX_RING_SECONDS = 300
        private const val MAX_UNLOCKS = 100
        private const val MAX_TEXT = 300
        private const val DAY_MS = 24 * 3600_000L

        fun trackMinutes(p: LostModePayload): Int = (p.trackMinutes ?: DEFAULT_TRACK_MINUTES).coerceIn(1, MAX_TRACK_MINUTES)

        /** The lock-screen text: the message, then the contact phone on its own line; null when both are empty. */
        fun lockScreenText(p: LostModePayload): String? {
            val message = p.message?.trim().orEmpty()
            val phone = p.phone?.trim().orEmpty()
            val text = listOfNotNull(message.ifEmpty { null }, phone.ifEmpty { null }?.let { "Tel: $it" }).joinToString("\n")
            return text.take(MAX_TEXT).ifEmpty { null }
        }

        fun silentTooLong(days: Int?, lastContactAt: Long, now: Long): Boolean =
            days != null && days > 0 && now - lastContactAt >= days * DAY_MS
    }
}
