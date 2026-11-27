package com.dallycontrol.core.config

import com.dallycontrol.core.config.PermissionPlanner.Action
import com.dallycontrol.proto.ConfigAppPermissions
import com.dallycontrol.proto.PermissionCatalog
import com.dallycontrol.proto.PermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionPlannerTest {
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"
    private val background = "android.permission.ACCESS_BACKGROUND_LOCATION"
    private val camera = "android.permission.CAMERA"
    private val storage = "android.permission.READ_EXTERNAL_STORAGE"
    private val contacts = "android.permission.READ_CONTACTS"
    private val custom = "com.acme.permission.DANGEROUS"

    private fun rules(all: String? = null, vararg perGroup: Pair<String, String>) =
        ConfigAppPermissions("com.acme.app", all = all, permissions = mapOf(*perGroup))

    @Test fun `the preset grants every declared runtime permission, known group or not`() {
        val plan = PermissionPlanner.plan(rules(PermissionRule.GRANT), listOf(camera, storage, custom), true, emptyMap())
        assertEquals(listOf(camera, storage, custom), plan.steps.map { it.permission })
        assertTrue(plan.steps.all { it.action == Action.GRANT })
    }

    @Test fun `a group rule wins over the preset`() {
        val plan = PermissionPlanner.plan(
            rules(PermissionRule.GRANT, PermissionCatalog.CAMERA to PermissionRule.DENY), listOf(camera, storage), true, emptyMap(),
        )
        assertEquals(Action.DENY, plan.steps.single { it.permission == camera }.action)
        assertEquals(Action.GRANT, plan.steps.single { it.permission == storage }.action)
    }

    @Test fun `background location is granted after the foreground ones`() {
        val plan = PermissionPlanner.plan(rules(PermissionRule.GRANT), listOf(background, camera, fine, coarse), true, emptyMap())
        assertEquals(background, plan.steps.last().permission)
        assertEquals(listOf(camera, fine, coarse), plan.steps.dropLast(1).map { it.permission })
    }

    @Test fun `without a rule nothing is touched`() {
        assertTrue(PermissionPlanner.plan(rules(), listOf(camera, fine), true, emptyMap()).steps.isEmpty())
        assertTrue(PermissionPlanner.plan(null, listOf(camera, fine), true, emptyMap()).steps.isEmpty())
    }

    @Test fun `a device that cannot grant sensors skips those grants but still denies`() {
        val plan = PermissionPlanner.plan(
            rules(PermissionRule.GRANT, PermissionCatalog.MICROPHONE to PermissionRule.DENY),
            listOf(camera, fine, contacts, "android.permission.RECORD_AUDIO"), canGrantSensors = false, fixedBefore = emptyMap(),
        )
        assertEquals(listOf(camera, fine), plan.blockedSensors)
        assertEquals(listOf(contacts to Action.GRANT, "android.permission.RECORD_AUDIO" to Action.DENY), plan.steps.map { it.permission to it.action })
    }

    @Test fun `a rule that goes away releases what the agent fixed`() {
        val plan = PermissionPlanner.plan(rules(), listOf(camera, storage), true, mapOf(camera to PermissionRule.GRANT))
        assertEquals(listOf(Step(camera)), plan.steps.map { Step(it.permission) })
        assertEquals(Action.RELEASE, plan.steps.single().action)
        assertNull(plan.steps.single().rule)
    }

    @Test fun `prompt after a grant of ours revokes once, then only releases`() {
        val prompt = rules(null, PermissionCatalog.CAMERA to PermissionRule.PROMPT)
        assertEquals(Action.REVOKE_AND_RELEASE, PermissionPlanner.plan(prompt, listOf(camera), true, mapOf(camera to PermissionRule.GRANT)).steps.single().action)
        // Next apply: the agent's record now says "prompt", so the user's own answer is not taken away again.
        assertEquals(Action.RELEASE, PermissionPlanner.plan(prompt, listOf(camera), true, mapOf(camera to PermissionRule.PROMPT)).steps.single().action)
        assertEquals(Action.RELEASE, PermissionPlanner.plan(prompt, listOf(camera), true, emptyMap()).steps.single().action)
    }

    @Test fun `unknown rule values are ignored`() {
        assertNull(PermissionPlanner.ruleFor(rules("always"), camera))
        assertEquals(PermissionRule.DENY, PermissionPlanner.ruleFor(rules("always", PermissionCatalog.CAMERA to "deny"), camera))
    }

    @Test fun `every storage and media permission falls in the storage group`() {
        for (p in listOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO")) {
            assertEquals(PermissionCatalog.STORAGE, PermissionCatalog.groupOf("android.permission.$p"))
        }
        assertEquals(PermissionCatalog.OTHER, PermissionCatalog.groupOf(custom))
    }

    private data class Step(val permission: String)
}
