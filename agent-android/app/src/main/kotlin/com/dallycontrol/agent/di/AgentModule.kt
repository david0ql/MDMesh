package com.dallycontrol.agent.di

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import com.dallycontrol.agent.BuildConfig
import com.dallycontrol.agent.admin.AdminReceiver
import com.dallycontrol.core.capability.CapabilityCollector
import com.dallycontrol.core.capability.CapabilitySource
import com.dallycontrol.core.command.CommandDispatcher
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.action.AlertNotifier
import com.dallycontrol.core.action.ResetPasswordTokenStore
import com.dallycontrol.core.action.RingController
import com.dallycontrol.core.command.handlers.AppIconsHandler
import com.dallycontrol.core.command.handlers.AppInstallHandler
import com.dallycontrol.core.command.handlers.AppScanHandler
import com.dallycontrol.core.command.handlers.AppUninstallHandler
import com.dallycontrol.core.command.handlers.ConfigApplyHandler
import com.dallycontrol.core.command.handlers.ConfigSyncHandler
import com.dallycontrol.core.command.handlers.DeviceAlertHandler
import com.dallycontrol.core.command.handlers.DeviceLockHandler
import com.dallycontrol.core.command.handlers.DeviceLockscreenMessageHandler
import com.dallycontrol.core.command.handlers.DevicePasscodeResetHandler
import com.dallycontrol.core.command.handlers.DeviceRebootHandler
import com.dallycontrol.core.command.handlers.DeviceRingHandler
import com.dallycontrol.core.command.handlers.DeviceLocationModeHandler
import com.dallycontrol.core.command.handlers.DevicePowerModeHandler
import com.dallycontrol.core.command.handlers.DeviceRingStopHandler
import com.dallycontrol.core.command.handlers.DeviceWipeHandler
import com.dallycontrol.core.config.ConfigApplier
import com.dallycontrol.core.location.LocationModeStore
import com.dallycontrol.core.power.PowerModeStore
import com.dallycontrol.core.command.handlers.KioskEnterHandler
import com.dallycontrol.core.command.handlers.KioskExitHandler
import com.dallycontrol.core.kiosk.AndroidKioskHomeSwitch
import com.dallycontrol.core.kiosk.KioskApplier
import com.dallycontrol.core.command.handlers.PolicyApplyHandler
import com.dallycontrol.core.device.AppInventoryCollector
import com.dallycontrol.core.device.HardwareIdCollector
import com.dallycontrol.core.sync.HardwareIdSource
import com.dallycontrol.core.install.InstallManager
import com.dallycontrol.core.state.DeviceStateCollector
import com.dallycontrol.core.state.DeviceStateSource
import com.dallycontrol.core.store.ConfigStateStore
import com.dallycontrol.core.store.DataStoreKioskStateStore
import com.dallycontrol.core.store.KioskStateStore
import com.dallycontrol.core.store.SharedPrefsConfigStateStore
import com.dallycontrol.core.telemetry.EventLog
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.core.telemetry.DeviceInfoCollector
import com.dallycontrol.core.telemetry.DynamicStateCollector
import com.dallycontrol.core.telemetry.IdentityCollector
import com.dallycontrol.core.telemetry.SecurityCollector
import com.dallycontrol.core.telemetry.TelemetryAssembler
import com.dallycontrol.core.telemetry.TelemetrySource
import com.dallycontrol.proto.AppManagement
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.kiosk.CrashLoopGuard
import com.dallycontrol.kiosk.FaultStore
import com.dallycontrol.kiosk.KioskController
import com.dallycontrol.kiosk.LockTaskKioskController
import com.dallycontrol.kiosk.SharedPrefsFaultStore
import com.dallycontrol.oem.GenericOemAdapter
import com.dallycontrol.oem.KnoxAdapter
import com.dallycontrol.oem.OemAdapter
import com.dallycontrol.policy.CapabilityRegistry
import com.dallycontrol.policy.TogglePolicy
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.agent.remote.DroidVncController
import com.dallycontrol.agent.remote.RemoteVncStartHandler
import com.dallycontrol.agent.remote.RemoteVncStopHandler
import com.dallycontrol.core.config.ServerConfigStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import com.dallycontrol.core.remote.RepeaterTunnel
import com.dallycontrol.core.store.DeviceIdentity
import com.dallycontrol.agent.policy.AndroidAppPolicyEnforcer
import com.dallycontrol.agent.policy.AndroidRoleResolver
import com.dallycontrol.agent.policy.ChromeManagedBrowser
import com.dallycontrol.core.command.handlers.DeviceAppLaunchHandler
import com.dallycontrol.core.config.AppPolicyEnforcer
import com.dallycontrol.core.config.ManagedBrowser
import com.dallycontrol.core.kiosk.RoleResolver
import com.dallycontrol.core.location.TrailStore

/**
 * Assembles the device-specific capability graph and binds it to the `:core`
 * orchestration types. This is the one place that knows about *this* app's admin
 * component and concrete adapters; everything downstream depends only on interfaces.
 *
 * Command handlers are contributed via multibindings (`@IntoSet`), so adding a new
 * command is a single `@Provides @IntoSet` line — the dispatcher never changes.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideDpmHandle(@ApplicationContext context: Context): DpmHandle {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return DpmHandle(dpm = dpm, admin = AdminReceiver.componentName(context))
    }

    @Provides
    @Singleton
    fun provideCapabilityRegistry(handle: DpmHandle): CapabilityRegistry =
        CapabilityRegistry(handle)

    @Provides
    @Singleton
    fun provideOemAdapter(): OemAdapter {
        // Most-capable-first; GenericOemAdapter is always available as the fallback.
        val candidates = listOf(KnoxAdapter(), GenericOemAdapter())
        return candidates.first { it.isAvailable() }
    }


    @Provides
    @Singleton
    fun provideCapabilityCollector(
        @ApplicationContext context: Context,
        registry: CapabilityRegistry,
        vnc: DroidVncController,
        oemAdapter: OemAdapter,
        handle: DpmHandle,
    ): CapabilityCollector {
        return CapabilityCollector(
            agentVersion = BuildConfig.VERSION_NAME,
            agentPackage = context.packageName,
            // A lambda, not a value: this collector is a @Singleton and Device-Owner status only
            // flips to true partway through provisioning — see CapabilityCollector.collect().
            isDeviceOwner = { handle.dpm.isDeviceOwnerApp(context.packageName) },
            policyKeys = registry::supportedPolicyKeys,
            // Remote view/control via droidVNC-NG (ADR 0010): probed per check-in, so installing it or
            // granting its input service shows up without an agent restart.
            remoteControl = vnc::capability,
            oem = oemAdapter::capability,
            // Silent install needs Device Owner — advertise app.silentInstall only when we have it, so
            // the server's capability gate won't queue an app.install we can't perform.
            deviceOwnerAppManagementKeys = AppManagement.DEVICE_OWNER_KEYS,
            deviceActionKeys = DeviceAction.ADVERTISED_KEYS.filter(::deviceActionSupported),
        )
    }

    /** Device actions whose platform API exists on this release (DevicePolicyManager.reboot and
     *  setDeviceOwnerLockScreenInfo are API 24+), so the console never offers what can't run. */
    private fun deviceActionSupported(key: String): Boolean = when (key) {
        "reboot", "lockscreenMessage" -> android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
        else -> true
    }

    /** Expose the collector behind its interface for the Android-free sync/enroll logic. */
    @Provides
    fun provideCapabilitySource(collector: CapabilityCollector): CapabilitySource = collector

    /** Stable, permission-free device id for enrollment de-duplication. */
    @Provides
    fun provideHardwareIdSource(collector: HardwareIdCollector): HardwareIdSource = collector

    /** Expose the device-state collector behind its interface (keeps :core sync Android-free). */
    @Provides
    fun provideDeviceStateSource(collector: DeviceStateCollector): DeviceStateSource = collector

    /** Expose the persistent event buffer behind its interface (keeps :core sync Android-free). */
    @Provides
    fun provideEventSink(eventLog: EventLog): EventSink = eventLog

    /** Compose the telemetry collectors into the census source (graceful degradation per collector). */
    @Provides
    @Singleton
    fun provideTelemetrySource(
        deviceInfo: DeviceInfoCollector,
        identity: IdentityCollector,
        dynamic: DynamicStateCollector,
        security: SecurityCollector,
        trail: TrailStore,
        systemUpdates: com.dallycontrol.agent.policy.SystemUpdates,
    ): TelemetrySource = TelemetryAssembler(
        hardware = { deviceInfo.collect() },
        identity = { identity.collect() },
        dynamic = { dynamic.collect().copy(systemUpdatePendingSince = runCatching { systemUpdates.pendingSince() }.getOrNull()) },
        security = { security.collect() },
        // The trail fixes a successful check-in carried are on the server now.
        onDelivered = { snap -> snap.dynamic.trail.maxOfOrNull { it.capturedAt }?.let(trail::ack) },
    )

    /**
     * The supported toggle policies, keyed by capability key (data-driven routing).
     *
     * A LIVE view, re-probed on every access: its consumers (the policy.apply handler and the
     * config applier) are singletons, and Device-Owner status flips to true partway through
     * provisioning. A snapshot taken before that stayed empty for the life of the process, so every
     * policy.apply answered "policy not supported" while the capability matrix (probed per check-in)
     * advertised the policies.
     */
    @Provides
    fun providePolicyToggles(registry: CapabilityRegistry): Map<String, TogglePolicy> =
        LiveTogglePolicies(registry)

    private class LiveTogglePolicies(
        private val registry: CapabilityRegistry,
    ) : AbstractMap<String, TogglePolicy>() {
        override val entries: Set<Map.Entry<String, TogglePolicy>>
            get() = registry.togglePolicies().entries
        override fun get(key: String): TogglePolicy? = registry.togglePolicies()[key]
        override fun containsKey(key: String): Boolean = registry.togglePolicies().containsKey(key)
    }

    // --- Command handlers (multibound). Add a command == add one @IntoSet provider. ---

    @Provides
    @IntoSet
    fun provideConfigSyncHandler(): CommandHandler = ConfigSyncHandler()

    @Provides
    @IntoSet
    fun providePolicyApplyHandler(
        toggles: Map<String, @JvmSuppressWildcards TogglePolicy>,
    ): CommandHandler = PolicyApplyHandler(toggles)

    @Provides
    @Singleton
    fun provideCommandDispatcher(
        handlers: Set<@JvmSuppressWildcards CommandHandler>,
    ): CommandDispatcher = CommandDispatcher(handlers.toList())

    // --- Kiosk (lock-task) ---

    @Provides
    @Singleton
    fun provideKioskController(handle: DpmHandle): KioskController =
        // droidVNC-NG stays startable in kiosk so remote support works on locked devices (ADR 0010).
        LockTaskKioskController(handle.dpm, handle.admin, supportPackages = listOf(DroidVncController.PACKAGE))

    @Provides
    @Singleton
    fun provideKioskStateStore(@ApplicationContext context: Context): KioskStateStore =
        DataStoreKioskStateStore(context)

    @Provides
    @Singleton
    fun provideFaultStore(@ApplicationContext context: Context): FaultStore =
        SharedPrefsFaultStore(context)

    @Provides
    @Singleton
    fun provideCrashLoopGuard(store: FaultStore): CrashLoopGuard = CrashLoopGuard(store)

    // --- App-management + kiosk + device command handlers (multibound) ---

    @Provides
    @IntoSet
    fun provideAppInstallHandler(installManager: InstallManager): CommandHandler =
        AppInstallHandler(installManager)

    @Provides
    @IntoSet
    fun provideAppUninstallHandler(installManager: InstallManager): CommandHandler =
        AppUninstallHandler(installManager)

    @Provides
    @Singleton
    fun provideAppInventoryCollector(@ApplicationContext context: Context): AppInventoryCollector =
        AppInventoryCollector(context)

    @Provides
    @IntoSet
    fun provideAppScanHandler(inventory: AppInventoryCollector): CommandHandler =
        AppScanHandler(inventory)

    @Provides
    @IntoSet
    fun provideAppIconsHandler(inventory: AppInventoryCollector): CommandHandler =
        AppIconsHandler(inventory)

    // The HOME claim is the activity-alias (toggled on only during kiosk), not the launcher
    // activity itself — see AndroidManifest `.KioskHomeAlias`.
    private fun kioskHomeAlias(context: Context): ComponentName =
        ComponentName(context.packageName, "com.dallycontrol.agent.KioskHomeAlias")

    @Provides
    @Singleton
    fun provideRoleResolver(@ApplicationContext context: Context): RoleResolver = AndroidRoleResolver(context)

    @Provides
    @Singleton
    fun provideKioskApplier(
        kiosk: KioskController,
        store: KioskStateStore,
        roles: RoleResolver,
        @ApplicationContext context: Context,
    ): KioskApplier {
        val home = kioskHomeAlias(context)
        return KioskApplier(kiosk, store, AndroidKioskHomeSwitch(context, home), home, roles)
    }

    @Provides
    @Singleton
    fun provideManagedBrowser(@ApplicationContext context: Context, handle: DpmHandle): ManagedBrowser =
        ChromeManagedBrowser(context, handle.dpm, handle.admin)

    @Provides
    @Singleton
    fun provideAppPolicyEnforcer(
        @ApplicationContext context: Context,
        handle: DpmHandle,
        roles: RoleResolver,
        events: EventSink,
    ): AppPolicyEnforcer = AndroidAppPolicyEnforcer(
        context, handle.dpm, handle.admin, roles, events, protectedPackages = listOf(DroidVncController.PACKAGE),
    )

    /** `device.openStore`: the app's Play Store page (the person taps Install); in kiosk the store must be allowed. */
    @Provides
    @IntoSet
    fun provideDeviceOpenStoreHandler(@ApplicationContext context: Context, handle: DpmHandle): CommandHandler =
        com.dallycontrol.core.command.handlers.DeviceOpenStoreHandler { pkg ->
            val store = "com.android.vending"
            val installed = runCatching { context.packageManager.getPackageInfo(store, 0); true }.getOrDefault(false)
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            when {
                !installed -> "the Play Store is not installed (or is hidden by the app policy)"
                am?.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE &&
                    android.os.Build.VERSION.SDK_INT >= 23 && !handle.dpm.isLockTaskPermitted(store) -> "the kiosk does not allow the Play Store"
                else -> {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg"))
                            .setPackage(store).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    null
                }
            }
        }

    /** `device.appLaunch`: start the app's launcher activity; in kiosk only apps the kiosk allows can start. */
    @Provides
    @IntoSet
    fun provideDeviceAppLaunchHandler(@ApplicationContext context: Context, handle: DpmHandle): CommandHandler =
        DeviceAppLaunchHandler { pkg ->
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            when {
                intent == null -> "not installed or has no launcher entry"
                am?.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE &&
                    android.os.Build.VERSION.SDK_INT >= 23 && !handle.dpm.isLockTaskPermitted(pkg) -> "not allowed by the kiosk"
                else -> {
                    context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    null
                }
            }
        }

    @Provides @IntoSet
    fun provideKioskEnterHandler(applier: KioskApplier): CommandHandler = KioskEnterHandler(applier)

    @Provides @IntoSet
    fun provideKioskExitHandler(applier: KioskApplier): CommandHandler = KioskExitHandler(applier)

    @Provides
    @IntoSet
    fun provideDeviceRebootHandler(handle: DpmHandle): CommandHandler =
        DeviceRebootHandler(handle)

    @Provides
    @IntoSet
    fun provideDeviceLockHandler(handle: DpmHandle): CommandHandler =
        DeviceLockHandler(handle)

    // --- Remote action handlers (multibound) ---

    @Provides
    @IntoSet
    fun provideLockscreenMessageHandler(handle: DpmHandle): CommandHandler =
        DeviceLockscreenMessageHandler(handle)

    @Provides
    @IntoSet
    fun provideAlertHandler(notifier: AlertNotifier): CommandHandler =
        DeviceAlertHandler(notifier)

    @Provides
    @IntoSet
    fun provideRingHandler(ring: RingController): CommandHandler =
        DeviceRingHandler(ring)

    @Provides
    @IntoSet
    fun provideRingStopHandler(ring: RingController): CommandHandler =
        DeviceRingStopHandler(ring)

    @Provides
    @IntoSet
    fun providePasscodeResetHandler(handle: DpmHandle, store: ResetPasswordTokenStore): CommandHandler =
        DevicePasscodeResetHandler(handle, store)

    @Provides
    @IntoSet
    fun provideWipeHandler(handle: DpmHandle): CommandHandler =
        DeviceWipeHandler(handle)

    @Provides
    @IntoSet
    fun providePowerModeHandler(store: PowerModeStore): CommandHandler =
        DevicePowerModeHandler(store)

    @Provides
    @IntoSet
    fun provideLocationModeHandler(store: LocationModeStore): CommandHandler =
        DeviceLocationModeHandler(store)

    // --- Remote view/control (droidVNC-NG, ADR 0010) ---

    @Provides
    @IntoSet
    fun provideRemoteVncStartHandler(
        vnc: DroidVncController,
        serverConfig: ServerConfigStore,
        tunnel: RepeaterTunnel,
        identity: DeviceIdentity,
    ): CommandHandler = RemoteVncStartHandler(vnc, serverConfig, tunnel, identity)

    @Provides
    @Singleton
    fun provideStorageTools(@ApplicationContext context: Context, handle: DpmHandle): com.dallycontrol.agent.storage.StorageTools =
        com.dallycontrol.agent.storage.StorageTools(context, handle)

    @Provides
    @IntoSet
    fun provideStorageScanHandler(tools: com.dallycontrol.agent.storage.StorageTools): CommandHandler =
        com.dallycontrol.agent.storage.StorageScanHandler(tools)

    @Provides
    @IntoSet
    fun provideStorageCleanHandler(tools: com.dallycontrol.agent.storage.StorageTools): CommandHandler =
        com.dallycontrol.agent.storage.StorageCleanHandler(tools)

    @Provides
    @IntoSet
    fun provideStorageAccessHandler(
        @ApplicationContext context: Context,
        handle: DpmHandle,
        tools: com.dallycontrol.agent.storage.StorageTools,
    ): CommandHandler = com.dallycontrol.agent.storage.StorageAccessHandler(context, handle, tools)

    @Provides
    @IntoSet
    fun provideRemoteInputSetupHandler(
        @ApplicationContext context: Context,
        handle: DpmHandle,
        vnc: DroidVncController,
    ): CommandHandler = com.dallycontrol.agent.remote.RemoteInputSetupHandler(context, handle, vnc)

    @Provides
    @IntoSet
    fun provideRemoteVncStopHandler(vnc: DroidVncController, tunnel: RepeaterTunnel): CommandHandler =
        RemoteVncStopHandler(vnc, tunnel)

    // --- Desired-state configuration (config.apply) ---

    @Provides
    @Singleton
    fun provideConfigStateStore(@ApplicationContext context: Context): ConfigStateStore =
        SharedPrefsConfigStateStore(context)

    @Provides
    @Singleton
    fun provideConfigApplier(
        toggles: Map<String, @JvmSuppressWildcards TogglePolicy>,
        kiosk: KioskApplier,
        location: LocationModeStore,
        store: ConfigStateStore,
        browser: ManagedBrowser,
        apps: AppPolicyEnforcer,
        trail: TrailStore,
        @ApplicationContext context: Context,
        systemUpdates: com.dallycontrol.agent.policy.SystemUpdates,
    ): ConfigApplier = ConfigApplier(
        toggles, kiosk, location::set, store, browser, apps,
        wifi = com.dallycontrol.agent.policy.AndroidManagedWifi(context, com.dallycontrol.agent.policy.WifiNetworks(context)),
        systemUpdates = systemUpdates::apply,
        setTrackingMinutes = trail::setIntervalMinutes,
    )

    @Provides
    @Singleton
    fun provideSystemUpdates(@ApplicationContext context: Context, handle: DpmHandle, events: EventSink): com.dallycontrol.agent.policy.SystemUpdates =
        com.dallycontrol.agent.policy.SystemUpdates(context, handle, events)

    @Provides
    @IntoSet
    fun provideConfigApplyHandler(applier: ConfigApplier): CommandHandler = ConfigApplyHandler(applier)
}
