package com.dallycontrol.core.config

import com.dallycontrol.proto.ConfigAppPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPolicyPlannerTest {
    private val installed = listOf("co.amovil.preventa", "com.whatsapp", "com.facebook.katana", "com.android.chrome")

    @Test fun `allowlist suspends what is not allowed, by package, function or protection`() {
        val plan = AppPolicyPlanner.plan(
            ConfigAppPolicy(mode = "allowlist", allowed = listOf("co.amovil.preventa", "com.whatsapp")),
            userInstalled = installed + "net.christianbeier.droidvnc_ng",
            rolePackages = listOf("com.android.chrome"),
            protected = listOf("net.christianbeier.droidvnc_ng"),
            currentlySuspended = emptyList(),
        )
        assertEquals(setOf("com.facebook.katana"), plan.suspend)
        assertEquals(emptySet<String>(), plan.unsuspend)
    }

    @Test fun `allowing an app later lifts only its suspension`() {
        val plan = AppPolicyPlanner.plan(
            ConfigAppPolicy(mode = "allowlist", allowed = listOf("co.amovil.preventa", "com.facebook.katana")),
            installed, emptyList(), emptyList(), currentlySuspended = listOf("com.facebook.katana", "com.whatsapp"),
        )
        assertEquals(setOf("com.android.chrome"), plan.suspend)
        assertEquals(setOf("com.facebook.katana"), plan.unsuspend)
    }

    @Test fun `open mode lifts everything`() {
        val plan = AppPolicyPlanner.plan(ConfigAppPolicy(mode = "open"), installed, emptyList(), emptyList(), listOf("com.whatsapp"))
        assertEquals(emptySet<String>(), plan.suspend)
        assertEquals(setOf("com.whatsapp"), plan.unsuspend)
    }
}
