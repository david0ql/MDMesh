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

    private val audio = "android.permission.RECORD_AUDIO"
    private val grant = PermissionRule.GRANT

    private fun rules(all: String? = null, vararg perGroup: Pair<String, String>) =
        ConfigAppPermissions("com.acme.app", all = all, permissions = mapOf(*perGroup))

    private fun plan(rules: ConfigAppPermissions?, declared: List<String>, fixed: Map<String, String> = emptyMap()) =
        PermissionPlanner.plan(rules, declared, canGrantSensors = true, fixedBefore = fixed)

    @Test fun `the preset grants every declared runtime permission, known group or not`() {
        val plan = plan(rules(grant), listOf(camera, storage, custom))
        assertEquals(listOf(camera, storage, custom), plan.steps.map { it.permission })
        assertTrue(plan.steps.all { it.action == Action.GRANT })
    }

    @Test fun `a group rule wins over the preset`() {
        val plan = plan(rules(grant, PermissionCatalog.CAMERA to PermissionRule.DENY), listOf(camera, storage))
        assertEquals(Action.DENY, plan.steps.single { it.permission == camera }.action)
        assertEquals(Action.GRANT, plan.steps.single { it.permission == storage }.action)
    }

    @Test fun `background location is granted after the foreground ones`() {
        val plan = plan(rules(grant), listOf(background, camera, fine, coarse))
        assertEquals(background, plan.steps.last().permission)
        assertEquals(listOf(camera, fine, coarse), plan.steps.dropLast(1).map { it.permission })
    }

    @Test fun `without a rule nothing is touched`() {
        assertTrue(plan(rules(), listOf(camera, fine)).steps.isEmpty())
        assertTrue(plan(null, listOf(camera, fine)).steps.isEmpty())
    }

    @Test fun `a device that cannot grant sensors skips those grants but still denies`() {
        val plan = PermissionPlanner.plan(
            rules(grant, PermissionCatalog.MICROPHONE to PermissionRule.DENY),
            listOf(camera, fine, contacts, audio),
            canGrantSensors = false,
            fixedBefore = emptyMap(),
        )
        assertEquals(listOf(camera, fine), plan.blockedSensors)
        val steps = plan.steps.map { it.permission to it.action }
        assertEquals(listOf(contacts to Action.GRANT, audio to Action.DENY), steps)
    }

    @Test fun `a rule that goes away releases what the agent fixed`() {
        val plan = plan(rules(), listOf(camera, storage), mapOf(camera to grant))
        assertEquals(listOf(Step(camera)), plan.steps.map { Step(it.permission) })
        assertEquals(Action.RELEASE, plan.steps.single().action)
        assertNull(plan.steps.single().rule)
    }

    @Test fun `prompt after a grant of ours revokes once, then only releases`() {
        val prompt = rules(null, PermissionCatalog.CAMERA to PermissionRule.PROMPT)
        fun action(fixed: Map<String, String>) = plan(prompt, listOf(camera), fixed).steps.single().action
        assertEquals(Action.REVOKE_AND_RELEASE, action(mapOf(camera to grant)))
        // Next apply: the agent's record now says "prompt", so the user's own answer is not taken away again.
        assertEquals(Action.RELEASE, action(mapOf(camera to PermissionRule.PROMPT)))
        assertEquals(Action.RELEASE, action(emptyMap()))
    }

    @Test fun `unknown rule values are ignored`() {
        assertNull(PermissionPlanner.ruleFor(rules("always"), camera))
        val denied = rules("always", PermissionCatalog.CAMERA to "deny")
        assertEquals(PermissionRule.DENY, PermissionPlanner.ruleFor(denied, camera))
    }

    @Test fun `every storage and media permission falls in the storage group`() {
        val storage = listOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO")
        for (p in storage + "READ_MEDIA_AUDIO") {
            assertEquals(PermissionCatalog.STORAGE, PermissionCatalog.groupOf("android.permission.$p"))
        }
        assertEquals(PermissionCatalog.OTHER, PermissionCatalog.groupOf(custom))
    }

    private data class Step(val permission: String)
}
