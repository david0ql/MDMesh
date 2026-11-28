package com.dallycontrol.agent.di

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.dallycontrol.agent.policy.AndroidLostModeDevice
import com.dallycontrol.agent.policy.DeviceProtection
import com.dallycontrol.core.capability.CapabilitySource
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.handlers.DeviceLostModeHandler
import com.dallycontrol.core.command.handlers.DeviceWipeHandler
import com.dallycontrol.core.command.wipe.WipeExecutor
import com.dallycontrol.core.command.wipe.WipeJournal
import com.dallycontrol.core.command.wipe.WipeRunner
import com.dallycontrol.core.lost.LostMode
import com.dallycontrol.core.net.MdmApi
import com.dallycontrol.core.store.DeviceIdentity
import com.dallycontrol.core.sync.CheckInHooks
import com.dallycontrol.core.sync.ResultAnnouncer
import com.dallycontrol.policy.wifi.DpmHandle
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Factory-reset protection, remote wipe and lost mode: the Android side of [WipeExecutor], the on-disk state of the
 * wipe journal and of lost mode, and the hooks that tie lost mode to the check-in cycle.
 */
@Module
@InstallIn(SingletonComponent::class)
object ProtectionModule {

    /** One text kept in its own preferences file. Written with commit(): a wipe order must be on disk first. */
    private class Stored(private val context: Context, private val file: String) {
        private fun prefs() = context.getSharedPreferences(file, Context.MODE_PRIVATE)

        fun load(): String? = prefs().getString(KEY, null)

        @SuppressLint("ApplySharedPref")
        fun save(value: String?) {
            prefs().edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.commit()
        }
    }

    @Provides
    @Singleton
    fun provideDeviceProtection(@ApplicationContext context: Context, handle: DpmHandle): DeviceProtection =
        DeviceProtection(context, handle)

    @Provides
    @Singleton
    fun provideWipeExecutor(handle: DpmHandle): WipeExecutor = object : WipeExecutor {
        override fun wipeDevice(flags: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                handle.dpm.wipeDevice(flags)
            } else {
                handle.dpm.wipeData(flags)
            }
        }

        override fun wipeData(flags: Int) = handle.dpm.wipeData(flags)

        override fun wipeData(flags: Int, reason: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                handle.dpm.wipeData(flags, reason)
            } else {
                handle.dpm.wipeData(flags)
            }
        }
    }

    @Provides
    @Singleton
    fun provideWipeJournal(@ApplicationContext context: Context): WipeJournal {
        val stored = Stored(context, "mdm_wipe_journal")
        return WipeJournal(stored::load, stored::save)
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
    fun provideLostMode(@ApplicationContext context: Context, device: AndroidLostModeDevice): LostMode {
        val stored = Stored(context, "mdm_lost_mode")
        return LostMode(device = device, load = stored::load, save = stored::save)
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

    private const val KEY = "state"
}
