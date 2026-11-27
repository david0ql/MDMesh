package com.dallycontrol.agent.di

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.dallycontrol.agent.provisioning.ClockRepair
import com.dallycontrol.core.enroll.EnrollFailure
import com.dallycontrol.core.enroll.EnrollIdentitySource
import com.dallycontrol.core.enroll.EnrollJournal
import com.dallycontrol.core.enroll.EnrollStatusStore
import com.dallycontrol.core.telemetry.IdentityCollector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Enrollment reliability wiring: the journal of attempts and the identity sent with the enroll request. */
@Module
@InstallIn(SingletonComponent::class)
object EnrollModule {

    @Provides
    @Singleton
    fun provideEnrollStatusStore(@ApplicationContext context: Context): EnrollStatusStore =
        EnrollStatusStore(context).apply {
            // A wrong clock is the one failure the agent can fix by itself (Device Owner, Android 9+).
            onFailure = { failure -> if (failure.code == EnrollFailure.CLOCK) ClockRepair.tryRepair(context) }
        }

    @Provides
    fun provideEnrollJournal(store: EnrollStatusStore): EnrollJournal = store

    /** Serial + first IMEI, which only a Device Owner (or a granted READ_PHONE_STATE before Android 10) can read. */
    @Provides
    fun provideEnrollIdentitySource(
        @ApplicationContext context: Context,
        collector: IdentityCollector,
    ): EnrollIdentitySource = EnrollIdentitySource {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm?.isDeviceOwnerApp(context.packageName) != true) {
            null to null
        } else {
            val identity = collector.collect()
            identity.serial to identity.imei.firstOrNull()
        }
    }
}
