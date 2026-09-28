package com.dallycontrol.core.kiosk

/**
 * Maps device functions (KioskRoles: phone, contacts, messages, browser, camera, maps) to the packages that serve
 * them on this device. [ResolvedRoles.launchable] are apps a user opens (they go on the kiosk home);
 * [ResolvedRoles.support] only need to be allowed to run — e.g. the in-call screen, so a call rings in kiosk.
 */
fun interface RoleResolver {
    fun resolve(roles: List<String>): ResolvedRoles
}

data class ResolvedRoles(
    val launchable: List<String> = emptyList(),
    val support: List<String> = emptyList(),
) {
    val all: List<String> get() = (launchable + support).distinct()
}

/** No functions resolved (tests, or a device without the relevant apps). */
val NoRoles = RoleResolver { ResolvedRoles() }
