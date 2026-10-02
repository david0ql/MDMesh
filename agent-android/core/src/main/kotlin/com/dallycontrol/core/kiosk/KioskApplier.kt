package com.dallycontrol.core.kiosk

import android.content.ComponentName
import com.dallycontrol.core.store.KioskStateStore
import com.dallycontrol.kiosk.KioskController
import com.dallycontrol.kiosk.KioskResult
import com.dallycontrol.kiosk.KioskToggles
import com.dallycontrol.kiosk.lockTaskFeatures
import com.dallycontrol.proto.KioskApplyPayload

/**
 * The one implementation of "put this device in kiosk with payload P" / "leave kiosk", shared by the
 * `kiosk.enter` / `kiosk.exit` commands and by `config.apply`. Idempotent: entering with the same payload
 * re-asserts the allowlist and features; exiting when not in kiosk is harmless.
 */
class KioskApplier(
    private val kiosk: KioskController,
    private val store: KioskStateStore,
    private val home: KioskHomeSwitch,
    private val homeComponent: ComponentName,
    private val roles: RoleResolver = NoRoles,
) {
    suspend fun enter(p: KioskApplyPayload): KioskResult {
        val features = lockTaskFeatures(
            KioskToggles(
                home = p.features.home, recents = p.features.recents, notifications = p.features.notifications,
                systemInfo = p.features.systemInfo, keyguard = p.features.keyguard, lockButtons = p.features.lockButtons,
            ),
        )
        // Functions resolve to THIS device's packages (its dialer + in-call screen, its contacts app, …): the openable
        // ones join the kiosk home, the supporting ones are only allowed to run.
        val resolved = if (p.roles.isEmpty()) ResolvedRoles() else runCatching { roles.resolve(p.roles) }.getOrDefault(ResolvedRoles())
        // A support line needs the phone's call screen allowed to run, even when the kiosk does not offer the dialer.
        val supportCall = if (p.theme.supportPhone.isNullOrBlank() || com.dallycontrol.proto.KioskRoles.PHONE in p.roles) emptyList()
        else runCatching { roles.resolve(listOf(com.dallycontrol.proto.KioskRoles.PHONE)).all }.getOrDefault(emptyList())
        val allowed = (p.allowedPackages + listOfNotNull(p.pinPackage) + resolved.all + supportCall).distinct()
        val shown = if (resolved.launchable.isEmpty()) p else p.copy(allowedPackages = (p.allowedPackages + resolved.launchable).distinct())
        home.setClaimEnabled(true)
        return when (val r = kiosk.enter(homeComponent, allowed, features)) {
            KioskResult.Ok -> { store.save(shown); home.showLauncher(); r }
            else -> { home.setClaimEnabled(false); r } // never leave a non-kiosk device claiming HOME
        }
    }

    suspend fun exit(): KioskResult = when (val r = kiosk.exit()) {
        KioskResult.Ok -> { store.save(null); home.setClaimEnabled(false); home.showOemHome(); r }
        else -> r
    }

    /** True when a kiosk payload is persisted (device believes it is / should be in kiosk). */
    suspend fun isPersisted(): Boolean = store.load() != null
}
