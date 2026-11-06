package com.example.teethree.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsToolsTest {
    @Test
    fun projectSettingWritesPreserveUnknownKeysAndSeparateInheritFromNullableOff() {
        val settings =
            Json.parseToJsonElement(
                    """{"projectSettingsOverrides":{"project":{"futureKey":{"keep":true},"defaultRuntimeMode":"auto","sidebarAutoSettleAfterDays":3,"providerInstances":{"codex":{"enabled":false}}},"other":{"branchNamePrefix":"other"}}}"""
                )
                .jsonObject
        val changed =
            buildSettingsPatch(
                settings,
                "defaultRuntimeMode",
                JsonPrimitive("approval-required"),
                "project",
            )
        val override = changed.obj("projectSettingsOverrides").obj("project")
        assertEquals(JsonPrimitive("approval-required"), override["defaultRuntimeMode"])
        assertEquals(
            settings.obj("projectSettingsOverrides").obj("project")["futureKey"],
            override["futureKey"],
        )
        assertEquals(
            settings.obj("projectSettingsOverrides").obj("project")["providerInstances"],
            override["providerInstances"],
        )
        assertFalse(changed.obj("projectSettingsOverrides").containsKey("other"))
        val inherited =
            buildSettingsPatch(settings, "defaultRuntimeMode", null, "project")
                .obj("projectSettingsOverrides")
                .obj("project")
        assertFalse(inherited.containsKey("defaultRuntimeMode"))
        assertTrue(inherited.containsKey("futureKey"))
        val off =
            buildSettingsPatch(settings, "sidebarAutoSettleAfterDays", JsonNull, "project")
                .obj("projectSettingsOverrides")
                .obj("project")
        assertEquals(JsonNull, off["sidebarAutoSettleAfterDays"])
        val inheritDays =
            buildSettingsPatch(settings, "sidebarAutoSettleAfterDays", null, "project")
                .obj("projectSettingsOverrides")
                .obj("project")
        assertFalse(inheritDays.containsKey("sidebarAutoSettleAfterDays"))
        assertEquals(
            JsonNull,
            buildSettingsPatch(settings, "defaultThreadEnvMode", JsonNull, null)[
                "defaultThreadEnvMode"],
        )
    }

    @Test
    fun managedLocksApplyToAncestorsAndDescendantsButNotSiblingSettings() {
        val config =
            Json.parseToJsonElement(
                    """{"managedPolicy":{"organization":"Example","lockedSettings":["providerInstances.codex.config.binaryPath","branchNamePrefix","projectSettingsOverrides.project"]}}"""
                )
                .jsonObject
        assertEquals(
            "Managed by Example.",
            managedSettingReason(config, "providerInstances.codex.config"),
        )
        assertEquals("Managed by Example.", managedSettingReason(config, "branchNamePrefix"))
        assertEquals(
            "Managed by Example.",
            managedSettingReason(config, "projectSettingsOverrides.project.defaultRuntimeMode"),
        )
        assertNull(managedSettingReason(config, "providerInstances.codex.enabled"))
        assertNull(managedSettingReason(JsonObject(emptyMap()), "branchNamePrefix"))
    }

    @Test
    fun worktreeSetupRetainsSettledSnapshotAcrossNullAndOutOfOrderHandoff() {
        fun snapshot(sequence: Int, phase: String, thread: String = "thread") =
            parseWorktreeSetup(
                Json.parseToJsonElement(
                        """{"threadId":"$thread","sequence":$sequence,"phase":"$phase","branch":"feature","setupScript":{"terminalId":"setup"},"stages":[{"id":"checkout","status":"done","percent":100,"tail":["Ready"]}]}"""
                    )
                    .jsonObject
            )
        val running = snapshot(1, "running")
        val done = snapshot(2, "done")
        assertEquals(done, retainWorktreeSetup(done, running, "thread"))
        assertEquals(done, retainWorktreeSetup(done, null, "thread"))
        assertEquals(done, retainWorktreeSetup(running, done, "thread"))
        assertNull(retainWorktreeSetup(done, null, "other"))
        assertEquals(100L, done.stages.single().percent)
        assertEquals("setup", done.terminalId)
        assertEquals(listOf("Ready"), done.stages.single().tail)
        assertTrue(worktreeSetupCanCancel(running))
        assertFalse(worktreeSetupCanCancel(done))
        assertFalse(
            worktreeSetupCanCancel(
                running.copy(
                    stages = listOf(WorktreeSetupStage("agent", "done", null, null, emptyList()))
                )
            )
        )
    }
}
