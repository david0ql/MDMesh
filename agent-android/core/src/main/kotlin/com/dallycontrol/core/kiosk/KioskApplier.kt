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
                systemInfo = p.features.systemInfo, keyguard = p.features.keyguard,
                // Anti-theft (a power PIN) always closes the power menu.
                lockButtons = if (!p.powerPin.isNullOrBlank()) true else p.features.lockButtons,
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

    /**
     * Periodic self-heal: when a kiosk is persisted (the device should be locked) but its live lock-task feature
     * mask has drifted from what the policy wants, re-apply the allowlist, feature mask and HOME claim — WITHOUT
     * bringing the launcher to the front. This recovers Home/Recents/power buttons even when the mask was cleared
     * out-of-band (e.g. a kiosk.exit during support) and the broken Home means the launcher never regains focus to
     * self-heal itself. No-op when: no kiosk is persisted, the mask is intact, or lock-task doesn't apply (soft
     * kiosk / not Device Owner / &lt; API 28). Idempotent and cheap in steady state (only a getLockTaskFeatures read).
     */
    suspend fun reassert() {
        val p = store.load() ?: return
        val current = kiosk.currentFeatures()
        if (current < 0) return // soft kiosk or unreadable: nothing to re-apply here
        val desired = lockTaskFeatures(
            KioskToggles(
                home = p.features.home, recents = p.features.recents, notifications = p.features.notifications,
                systemInfo = p.features.systemInfo, keyguard = p.features.keyguard,
                lockButtons = if (!p.powerPin.isNullOrBlank()) true else p.features.lockButtons,
            ),
        )
        if (current == desired) return // mask intact, nothing to heal
        val resolved = if (p.roles.isEmpty()) ResolvedRoles() else runCatching { roles.resolve(p.roles) }.getOrDefault(ResolvedRoles())
        val supportCall = if (p.theme.supportPhone.isNullOrBlank() || com.dallycontrol.proto.KioskRoles.PHONE in p.roles) emptyList()
        else runCatching { roles.resolve(listOf(com.dallycontrol.proto.KioskRoles.PHONE)).all }.getOrDefault(emptyList())
        val allowed = (p.allowedPackages + listOfNotNull(p.pinPackage) + resolved.all + supportCall).distinct()
        home.setClaimEnabled(true)
        kiosk.enter(homeComponent, allowed, desired) // re-applies packages + feature mask + HOME claim, no launcher focus change
    }

    suspend fun exit(): KioskResult = when (val r = kiosk.exit()) {
        KioskResult.Ok -> { store.save(null); home.setClaimEnabled(false); home.showOemHome(); r }
        else -> r
    }

    /** True when a kiosk payload is persisted (device believes it is / should be in kiosk). */
    suspend fun isPersisted(): Boolean = store.load() != null
}
