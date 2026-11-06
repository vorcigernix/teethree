package com.example.teethree.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ProviderAndProjectDefaultsTest {
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private val provider =
        json(
            """{"instanceId":"codex","driver":"codex","displayName":"Codex","enabled":true,"installed":true,"availability":"available","version":"1","setup":{"canInstall":true},"versionAdvisory":{"status":"behind_latest","canUpdate":true,"canInstallVersion":true,"latestVersion":"2"},"compatibilityAdvisory":{"latestVersionStatus":"supported"},"updateState":{"status":"idle"},"models":[{"slug":"first","name":"First","isDefault":true},{"slug":"preferred","name":"Preferred"}]}"""
        )
    private val project = T3Project("project", "Project", "/workspace")

    private fun config(settings: JsonObject = json("{}")) =
        JsonObject(mapOf("providers" to JsonArray(listOf(provider)), "settings" to settings))

    @Test
    fun providerMaintenanceHonorsCompatibilityConcurrentUpdateAndOrganizationPolicy() {
        val base = parseProviderMaintenance(provider, json("{}"))
        assertTrue(base.canInstall)
        assertTrue(base.canUpdate)
        assertTrue(base.canInstallVersion)
        listOf("broken", "unsupported").forEach { status ->
            assertFalse(
                parseProviderMaintenance(
                        JsonObject(
                            provider +
                                ("compatibilityAdvisory" to
                                    json("""{"latestVersionStatus":"$status"}"""))
                        ),
                        json("{}"),
                    )
                    .canUpdate
            )
        }
        listOf("queued", "running").forEach { status ->
            assertFalse(
                parseProviderMaintenance(
                        JsonObject(provider + ("updateState" to json("""{"status":"$status"}"""))),
                        json("{}"),
                    )
                    .canUpdate
            )
        }
        assertFalse(
            parseProviderMaintenance(
                    JsonObject(provider + ("availability" to JsonPrimitive("unavailable"))),
                    json("{}"),
                )
                .canUpdate
        )
        listOf(
                """{"managedPolicy":{"limits":{"updates":{"mode":"off"}}}}""",
                """{"managedPolicy":{"limits":{"providers":{"allowedDrivers":["claude"]}}}}""",
            )
            .forEach { policy ->
                val blocked = parseProviderMaintenance(provider, json(policy))
                assertFalse(blocked.canInstall)
                assertFalse(blocked.canUpdate)
                assertFalse(blocked.canInstallVersion)
                assertNotNull(blocked.policyReason)
            }
        val installation =
            parseProviderInstallation(
                json(
                    """{"driver":"codex","operationId":"op","phase":"downloading","downloadedBytes":42,"totalBytes":null,"source":"local","canRemove":false}"""
                )
            )
        assertTrue(installation.active)
        assertEquals("op", installation.operationId)
        assertEquals(42L, installation.downloadedBytes)
        assertNull(installation.totalBytes)
        assertFalse(installation.canRemove)
    }

    @Test
    fun projectDefaultsPreserveOptionsLegacyPrecedenceExplicitNullAndFoldedResets() {
        val global =
            json(
                """{"defaultModelSelection":{"instanceId":"codex","model":"preferred","options":[{"id":"effort","value":"high"}]},"defaultRuntimeMode":"auto","defaultThreadEnvMode":null,"worktreeSubmodules":null}"""
            )
        val legacy =
            project.copy(
                defaultModelSelection =
                    json(
                        """{"instanceId":"codex","model":"first","options":[{"id":"future","value":false}]}"""
                    ),
                defaultThreadEnvMode = "worktree",
            )
        val initial =
            effectiveProjectDefaults(
                config(global),
                legacy,
                json("""{"defaultThreadEnvMode":"local","worktreeSubmodules":"none"}"""),
            )
        assertEquals("first", initial.modelSelection!!.string("model"))
        assertEquals(
            JsonPrimitive(false),
            initial.modelSelection
                .array("options")
                .filterIsInstance<JsonObject>()
                .single()["value"],
        )
        assertEquals("worktree", initial.workspaceMode)
        assertEquals("none", initial.submodules)
        val folded =
            effectiveProjectDefaults(
                config(JsonObject(global + ("projectSettingsFolded" to JsonPrimitive(true)))),
                legacy,
            )
        assertEquals("preferred", folded.modelSelection!!.string("model"))
        assertEquals("local", folded.workspaceMode)
        val cleared =
            JsonObject(
                global +
                    ("projectSettingsOverrides" to
                        json(
                            """{"project":{"defaultModelSelection":null,"defaultThreadEnvMode":null}}"""
                        ))
            )
        val explicit =
            effectiveProjectDefaults(
                config(cleared),
                legacy,
                json("""{"defaultThreadEnvMode":"local"}"""),
            )
        assertEquals("first", explicit.modelSelection!!.string("model"))
        assertEquals("local", explicit.workspaceMode)
        val policy =
            JsonObject(
                config(global) +
                    ("managedPolicy" to
                        json("""{"limits":{"maxRuntimeMode":"approval-required"}}"""))
            )
        assertEquals("approval-required", effectiveProjectDefaults(policy, project).runtimeMode)
    }

    @Test
    fun disabledProjectModelFallsBackToEnvironmentWithoutDiscardingItsOptions() {
        val other =
            JsonObject(
                provider +
                    mapOf(
                        "instanceId" to JsonPrimitive("other"),
                        "driver" to JsonPrimitive("claude"),
                    )
            )
        val settings =
            json(
                """{"defaultModelSelection":{"instanceId":"codex","model":"preferred","options":[{"id":"effort","value":"high"}]},"projectSettingsOverrides":{"project":{"defaultModelSelection":{"instanceId":"other","model":"first"},"providerInstances":{"other":{"enabled":false}}}}}"""
            )
        val configuration =
            JsonObject(config(settings) + ("providers" to JsonArray(listOf(provider, other))))
        val selected = effectiveProjectDefaults(configuration, project).modelSelection!!
        assertEquals("codex", selected.string("instanceId"))
        assertEquals("preferred", selected.string("model"))
        assertEquals(
            "high",
            selected.array("options").filterIsInstance<JsonObject>().single().string("value"),
        )
    }

    @Test
    fun projectProviderEnableOverridesEnvironmentDefaultWithoutBypassingAvailability() {
        val disabled = JsonObject(provider + ("enabled" to JsonPrimitive(false)))
        val configuration =
            JsonObject(
                config(
                    json(
                        """{"projectSettingsOverrides":{"project":{"providerInstances":{"codex":{"enabled":true}}}}}"""
                    )
                ) + ("providers" to JsonArray(listOf(disabled)))
            )
        assertEquals(2, projectDefaultModelOptions(configuration, "project").size)
        assertTrue(projectDefaultModelOptions(configuration, null).isEmpty())
        val unavailable =
            JsonObject(
                configuration +
                    ("providers" to
                        JsonArray(
                            listOf(
                                JsonObject(
                                    disabled + ("availability" to JsonPrimitive("unavailable"))
                                )
                            )
                        ))
            )
        assertTrue(projectDefaultModelOptions(unavailable, "project").isEmpty())
    }

    @Test
    fun actionOverridesKeepEmptyListAuthoritativeAndFoldLegacyOnlyOnce() {
        val legacy =
            project.copy(
                scripts =
                    JsonArray(
                        listOf(
                            json(
                                """{"id":"old","name":"Old","command":"old","icon":"play","runOnWorktreeCreate":false}"""
                            )
                        )
                    )
            )
        val defaults =
            json(
                """{"defaultProjectScripts":[{"id":"global","name":"Global","command":"global"}]}"""
            )
        assertEquals(
            "old",
            effectiveProjectScripts(defaults, legacy)
                .filterIsInstance<JsonObject>()
                .single()
                .string("id"),
        )
        assertEquals(
            "global",
            effectiveProjectScripts(
                    JsonObject(defaults + ("projectSettingsFolded" to JsonPrimitive(true))),
                    legacy,
                )
                .filterIsInstance<JsonObject>()
                .single()
                .string("id"),
        )
        val empty =
            JsonObject(
                defaults +
                    ("projectSettingsOverrides" to
                        json("""{"project":{"defaultProjectScripts":[]}}"""))
            )
        assertTrue(effectiveProjectScripts(empty, legacy).isEmpty())
        val reset =
            JsonObject(defaults + ("projectScriptOverrides" to json("""{"project":null}""")))
        assertEquals(
            "global",
            effectiveProjectScripts(reset, legacy)
                .filterIsInstance<JsonObject>()
                .single()
                .string("id"),
        )
    }

    @Test
    fun actionEditingPreservesUnknownFieldsAndMovesExclusiveSetupAndSettleRoles() {
        val old =
            json(
                """{"id":"old","name":"Existing","command":"old","icon":"test","runOnWorktreeCreate":true,"runOnSettle":true,"future":{"preserve":true}}"""
            )
        val action =
            ProjectAction(
                "new",
                " New ",
                " command ",
                setup = true,
                waitForSetup = true,
                onSettle = true,
                raw =
                    json(
                        """{"future":"keep","previewUrl":"https://old.example","autoOpenPreview":true}"""
                    ),
            )
        val next =
            updateProjectActions(JsonArray(listOf(old)), action).filterIsInstance<JsonObject>()
        assertFalse(next.first().bool("runOnWorktreeCreate"))
        assertFalse(next.first().bool("runOnSettle"))
        assertTrue(next.first().obj("future").bool("preserve"))
        val saved = next.last()
        assertEquals(JsonPrimitive(false), saved["async"])
        assertEquals("New", saved.string("name"))
        assertEquals("keep", saved.string("future"))
        assertFalse(saved.containsKey("previewUrl"))
        assertFalse(saved.containsKey("autoOpenPreview"))
        assertThrows(IllegalArgumentException::class.java) {
            projectActionJson(action.copy(previewUrl = "javascript:alert(1)"))
        }
    }

    @Test
    fun projectFileImportAcceptsCommentsAndTrailingCommasWithoutCorruptingUrls() {
        val file =
            parseProjectDefaultsFile(
                """{/* shared */"defaultThreadEnvMode":"worktree","worktreeSubmodules":"top-level","scripts":[{"name":"Dev","command":"start","previewUrl":"https://host.example/path//keep",},],}"""
            )!!
        assertEquals("worktree", file.string("defaultThreadEnvMode"))
        assertEquals(
            "https://host.example/path//keep",
            file.array("scripts").filterIsInstance<JsonObject>().single().string("previewUrl"),
        )
        listOf(
                """{"scripts":[{"name":"Bad","command":""}]}""",
                """{"scripts":[{"name":"Bad","command":"x","async":"false"}]}""",
                """{"worktreeSubmodules":"all"}""",
                "{unquoted:true}",
            )
            .forEach { assertNull(parseProjectDefaultsFile(it)) }
    }

    @Test
    fun newNameOnlyProjectsUseLocalOnlyForEnvironmentWorkspaceDefaults() {
        val root =
            JsonObject(
                config(json("""{"defaultThreadEnvMode":"worktree"}""")) +
                    ("newProjectsRoot" to JsonPrimitive("/projects/"))
            )
        val fresh = project.copy(workspaceRoot = "/projects/new/")
        assertEquals("local", resolvedNewTaskWorkspaceMode(root, fresh, false))
        assertEquals("worktree", resolvedNewTaskWorkspaceMode(root, fresh, true))
        assertEquals(
            "worktree",
            resolvedNewTaskWorkspaceMode(
                root,
                fresh.copy(workspaceRoot = "/projects/deep/new"),
                false,
            ),
        )
        assertEquals(
            "worktree",
            resolvedNewTaskWorkspaceMode(
                root,
                fresh.copy(defaultThreadEnvMode = "worktree"),
                false,
            ),
        )
        val scoped =
            JsonObject(
                root +
                    ("settings" to
                        json(
                            """{"defaultThreadEnvMode":"local","projectSettingsOverrides":{"project":{"defaultThreadEnvMode":"worktree"}}}"""
                        ))
            )
        assertEquals("worktree", resolvedNewTaskWorkspaceMode(scoped, fresh, false))
        val fromFile = JsonObject(root + ("settings" to json("""{"defaultThreadEnvMode":null}""")))
        assertEquals(
            "worktree",
            resolvedNewTaskWorkspaceMode(
                fromFile,
                fresh,
                false,
                json("""{"defaultThreadEnvMode":"worktree"}"""),
            ),
        )
        val scratch =
            JsonObject(root + ("scratchWorkspaceRoot" to JsonPrimitive(fresh.workspaceRoot)))
        assertEquals(
            "local",
            resolvedNewTaskWorkspaceMode(
                scratch,
                fresh.copy(defaultThreadEnvMode = "worktree"),
                true,
            ),
        )
        val windows = JsonObject(root + ("newProjectsRoot" to JsonPrimitive("C:/Projects/")))
        assertEquals(
            "local",
            resolvedNewTaskWorkspaceMode(
                windows,
                fresh.copy(workspaceRoot = "c:/projects/New"),
                false,
            ),
        )
        assertEquals(
            "worktree",
            resolvedNewTaskWorkspaceMode(root, fresh.copy(workspaceRoot = "/projects"), false),
        )
    }
}
