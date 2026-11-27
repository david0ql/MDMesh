package com.dallycontrol.agent.policy

import android.os.Build
import com.dallycontrol.core.action.RingController
import com.dallycontrol.core.command.wipe.WipePayload
import com.dallycontrol.core.command.wipe.WipePlanner
import com.dallycontrol.core.command.wipe.WipeRunner
import com.dallycontrol.core.location.LocationModeStore
import com.dallycontrol.core.location.TrailStore
import com.dallycontrol.core.lost.LostMode
import com.dallycontrol.core.lost.LostModeDevice
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.EventType
import com.dallycontrol.proto.ProtectionStatus
import javax.inject.Inject
import javax.inject.Singleton

/** Where the agent keeps how often and how it captures its position. */
@Singleton
class PositionCadence @Inject constructor(
    private val trail: TrailStore,
    private val location: LocationModeStore,
) {
    fun trackingMinutes(): Int = trail.intervalMinutes()
    fun setTrackingMinutes(minutes: Int) = trail.setIntervalMinutes(minutes)
    fun locationMode(): String = location.get()
    fun setLocationMode(mode: String) = location.set(mode)
}

/** The wipe lost mode runs on its own when the device has been silent too long: everything, protection kept. */
@Singleton
class LostWipe @Inject constructor(
    private val runner: WipeRunner,
    private val events: EventSink,
) {
    fun run() {
        events.record(EventType.LOST_WIPE, "silent too long")
        runner.run(WipePlanner.plan(Build.VERSION.SDK_INT, WipePayload(intent = WipePlanner.INTENT_LOST)))
    }
}

/** What lost mode does on a real device (see [LostMode] for the behaviour). */
@Singleton
class AndroidLostModeDevice @Inject constructor(
    private val handle: DpmHandle,
    private val ring: RingController,
    private val cadence: PositionCadence,
    private val protection: DeviceProtection,
    private val wipe: LostWipe,
) : LostModeDevice {
    override fun setLockScreenInfo(text: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) handle.dpm.setDeviceOwnerLockScreenInfo(handle.admin, text)
    }

    override fun lockNow() = handle.dpm.lockNow()
    override fun ring(durationMs: Long) = ring.start(durationMs)
    override fun stopRing() = ring.stop()
    override fun trackingMinutes(): Int = cadence.trackingMinutes()
    override fun setTrackingMinutes(minutes: Int) = cadence.setTrackingMinutes(minutes)
    override fun locationMode(): String = cadence.locationMode()
    override fun setLocationMode(mode: String) = cadence.setLocationMode(mode)
    override fun setMaxFailedUnlocksForWipe(attempts: Int) = protection.setLostMaxFailed(attempts)
    override fun wipeKeepingProtection() = wipe.run()
}

/** What travels to the server as `security.protection`: what the device holds, and lost mode. */
@Singleton
class ProtectionReport @Inject constructor(
    private val protection: DeviceProtection,
    private val lostMode: LostMode,
) {
    /** Null when it cannot be read (the report goes out without it rather than not at all). */
    fun read(): ProtectionStatus? = runCatching { protection.status().copy(lostMode = lostMode.status()) }.getOrNull()
}
