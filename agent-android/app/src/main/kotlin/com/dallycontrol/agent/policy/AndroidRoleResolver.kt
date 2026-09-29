package com.dallycontrol.agent.policy

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telecom.TelecomManager
import com.dallycontrol.core.kiosk.ResolvedRoles
import com.dallycontrol.core.kiosk.RoleResolver
import com.dallycontrol.proto.KioskRoles

/**
 * Resolves device functions to THIS phone's packages by asking Android who serves each one (default dialer,
 * default SMS app, browser/contacts/camera/maps category holders…), so one configuration fits Samsung, Motorola,
 * Xiaomi and Pixel alike. The phone function also brings the in-call screen and telecom UI — without them an
 * incoming call cannot show in kiosk.
 */
class AndroidRoleResolver(private val context: Context) : RoleResolver {
    private val pm: PackageManager get() = context.packageManager

    override fun resolve(roles: List<String>): ResolvedRoles {
        val found = linkedSetOf<String>()
        val support = linkedSetOf<String>()
        for (role in roles) {
            when (role) {
                KioskRoles.PHONE -> {
                    val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    runCatching { tm?.defaultDialerPackage }.getOrNull()?.let(found::add)
                    if (Build.VERSION.SDK_INT >= 29) runCatching { tm?.systemDialerPackage }.getOrNull()?.let(found::add)
                    activities(Intent(Intent.ACTION_DIAL)).forEach(found::add)
                    services(Intent("android.telecom.InCallService")).filter(::isSystem).forEach(support::add)
                    listOf("com.android.server.telecom", "com.android.phone").filter(::installed).forEach(support::add)
                }
                KioskRoles.CONTACTS -> {
                    activities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)).forEach(found::add)
                    activities(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)).forEach(found::add)
                }
                KioskRoles.MESSAGES -> {
                    runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(found::add)
                    activities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)).forEach(found::add)
                }
                KioskRoles.BROWSER -> {
                    activities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)).forEach(found::add)
                    activities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))).forEach(found::add)
                }
                KioskRoles.CAMERA -> {
                    activities(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)).forEach(found::add)
                    activities(Intent(MediaStore.ACTION_IMAGE_CAPTURE)).forEach(found::add)
                }
                KioskRoles.MAPS -> {
                    activities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS)).forEach(found::add)
                    activities(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=Bogota"))).forEach(found::add)
                }
            }
        }
        val own = setOf(context.packageName, "android")
        val all = (found + support).filterNot { it in own }
        val launchable = all.filter { pm.getLaunchIntentForPackage(it) != null }
        return ResolvedRoles(launchable = launchable, support = all - launchable.toSet())
    }

    @Suppress("DEPRECATION")
    private fun activities(intent: Intent): List<String> = runCatching {
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
    }.getOrDefault(emptyList()).distinct()

    @Suppress("DEPRECATION")
    private fun services(intent: Intent): List<String> = runCatching {
        pm.queryIntentServices(intent, 0).map { it.serviceInfo.packageName }
    }.getOrDefault(emptyList()).distinct()

    @Suppress("DEPRECATION")
    private fun installed(pkg: String) = runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun isSystem(pkg: String) = runCatching {
        (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false) || Build.VERSION.SDK_INT < 21
}
