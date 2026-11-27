package com.dallycontrol.agent.di

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.dallycontrol.agent.policy.DeviceRules
import com.dallycontrol.core.action.RingController
import com.dallycontrol.core.capability.CapabilitySource
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.handlers.DeviceLostModeHandler
import com.dallycontrol.core.command.handlers.DeviceWipeHandler
import com.dallycontrol.core.command.wipe.WipeExecutor
import com.dallycontrol.core.command.wipe.WipeJournal
import com.dallycontrol.core.command.wipe.WipePayload
import com.dallycontrol.core.command.wipe.WipePlanner
import com.dallycontrol.core.command.wipe.WipeRunner
import com.dallycontrol.core.location.LocationModeStore
import com.dallycontrol.core.location.TrailStore
import com.dallycontrol.core.lost.LostMode
import com.dallycontrol.core.lost.LostModeDevice
import com.dallycontrol.core.net.MdmApi
import com.dallycontrol.core.store.DeviceIdentity
import com.dallycontrol.core.sync.CheckInHooks
import com.dallycontrol.core.sync.EnrollIdentitySource
import com.dallycontrol.core.sync.ResultAnnouncer
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.core.telemetry.IdentityCollector
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.EventType
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Remote wipe and lost mode: the Android side of [WipeExecutor] and [LostModeDevice], their on-disk state, and the
 * hooks that tie lost mode to the check-in cycle.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProtectionModule {

    /** Written with commit(): the wipe order has to be on disk before the device erases itself. */
    @SuppressLint("ApplySharedPref")
    private fun stored(context: Context, file: String): Pair<() -> String?, (String?) -> Unit> {
        val prefs = { context.getSharedPreferences(file, Context.MODE_PRIVATE) }
        return Pair(
            { prefs().getString(KEY, null) },
            { value -> prefs().edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.commit() },
        )
    }

    @Provides
    @Singleton
    fun provideWipeExecutor(handle: DpmHandle): WipeExecutor = object : WipeExecutor {
        override fun wipeDevice(flags: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) handle.dpm.wipeDevice(flags)
            else handle.dpm.wipeData(flags)
        }

        override fun wipeData(flags: Int) = handle.dpm.wipeData(flags)

        override fun wipeData(flags: Int, reason: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) handle.dpm.wipeData(flags, reason)
            else handle.dpm.wipeData(flags)
        }
    }

    @Provides
    @Singleton
    fun provideWipeJournal(@ApplicationContext context: Context): WipeJournal {
        val (load, save) = stored(context, "mdm_wipe_journal")
        return WipeJournal(load, save)
    }

    @Provides
    @Singleton
    fun provideWipeRunner(executor: WipeExecutor, journal: WipeJournal): WipeRunner = WipeRunner(executor, journal)

    @Provides
    @IntoSet
    fun provideWipeHandler(
        runner: WipeRunner,
        journal: WipeJournal,
        api: MdmApi,
        identity: DeviceIdentity,
        capabilities: CapabilitySource,
    ): CommandHandler = DeviceWipeHandler(
        sdkInt = Build.VERSION.SDK_INT,
        runner = runner,
        journal = journal,
        announce = ResultAnnouncer(api, identity, capabilities)::announce,
    )

    @Provides
    @Singleton
    fun provideLostMode(
        @ApplicationContext context: Context,
        handle: DpmHandle,
        ring: RingController,
        trail: TrailStore,
        location: LocationModeStore,
        rules: DeviceRules,
        runner: WipeRunner,
        events: EventSink,
    ): LostMode {
        val (load, save) = stored(context, "mdm_lost_mode")
        return LostMode(
            device = object : LostModeDevice {
                override fun setLockScreenInfo(text: String?) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) handle.dpm.setDeviceOwnerLockScreenInfo(handle.admin, text)
                }
                override fun lockNow() = handle.dpm.lockNow()
                override fun ring(durationMs: Long) = ring.start(durationMs)
                override fun stopRing() = ring.stop()
                override fun trackingMinutes(): Int = trail.intervalMinutes()
                override fun setTrackingMinutes(minutes: Int) = trail.setIntervalMinutes(minutes)
                override fun locationMode(): String = location.get()
                override fun setLocationMode(mode: String) = location.set(mode)
                override fun setMaxFailedUnlocksForWipe(attempts: Int) = rules.setLostMaxFailed(attempts)
                override fun wipeKeepingProtection() {
                    events.record(EventType.LOST_WIPE, "silent too long")
                    runner.run(WipePlanner.plan(Build.VERSION.SDK_INT, WipePayload(intent = WipePlanner.INTENT_LOST)))
                }
            },
            load = load,
            save = save,
        )
    }

    @Provides
    @IntoSet
    fun provideLostModeHandler(lostMode: LostMode): CommandHandler = DeviceLostModeHandler(lostMode)

    @Provides
    @Singleton
    fun provideCheckInHooks(lostMode: LostMode): CheckInHooks = object : CheckInHooks {
        override fun beforeCycle() {
            lostMode.wipeIfSilentTooLong()
        }

        override fun afterSuccess() = lostMode.contact()
    }

    /** Serial and first IMEI for enrollment: after a factory reset they bring the device back to its row and folder. */
    @Provides
    @Singleton
    fun provideEnrollIdentitySource(identity: IdentityCollector): EnrollIdentitySource = EnrollIdentitySource {
        val info = identity.collect()
        Pair(info.serial, info.imei.firstOrNull())
    }

    private const val KEY = "state"
}
