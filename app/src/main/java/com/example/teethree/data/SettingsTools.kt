package com.example.teethree.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

enum class SettingsPage(val title: String) {
    General("General"),
    Threads("Threads"),
    SourceControl("Source control"),
    Worktrees("Worktrees"),
    Maintenance("Maintenance"),
    Providers("Provider configuration"),
    Defaults("Models and actions"),
    Projects("Projects"),
    Storage("Server storage"),
}

data class SettingsChoice(val value: String?, val label: String)

data class NativeSetting(
    val key: String,
    val title: String,
    val page: SettingsPage,
    val choices: List<SettingsChoice> = emptyList(),
    val boolean: Boolean = false,
    val number: Boolean = false,
    val projectScoped: Boolean = true,
    val capability: String? = null,
)

val nativeSettings =
    listOf(
        NativeSetting(
            "defaultThreadEnvMode",
            "New task workspace",
            SettingsPage.Threads,
            listOf(
                SettingsChoice(null, "Inherit repository setting"),
                SettingsChoice("local", "Current checkout"),
                SettingsChoice("worktree", "New worktree"),
            ),
        ),
        NativeSetting(
            "defaultRuntimeMode",
            "Default agent permissions",
            SettingsPage.Threads,
            listOf(
                SettingsChoice("approval-required", "Ask first"),
                SettingsChoice("auto-accept-edits", "Auto edit"),
                SettingsChoice("auto", "Automatic"),
                SettingsChoice("full-access", "Full access"),
            ),
        ),
        NativeSetting(
            "responseStreamingMode",
            "Response streaming",
            SettingsPage.Threads,
            listOf(
                SettingsChoice("paragraph", "Paragraphs"),
                SettingsChoice("turn", "Completed turns"),
            ),
        ),
        NativeSetting(
            "enableAgentBrowserAccess",
            "Agent browser access",
            SettingsPage.Threads,
            boolean = true,
        ),
        NativeSetting(
            "sidebarAutoSettleOnMerge",
            "Settle threads after merging",
            SettingsPage.Threads,
            boolean = true,
            capability = "threadAutoSettlement",
        ),
        NativeSetting(
            "sidebarAutoSettleAfterDays",
            "Settle inactive threads after days (1–90)",
            SettingsPage.Threads,
            number = true,
            capability = "threadAutoSettlement",
        ),
        NativeSetting(
            "sidebarAutoSettleKeepPullRequests",
            "Keep threads with open pull requests active",
            SettingsPage.Threads,
            boolean = true,
            capability = "threadAutoSettlement",
        ),
        NativeSetting(
            "defaultAutoPull",
            "Automatically pull",
            SettingsPage.SourceControl,
            boolean = true,
        ),
        NativeSetting(
            "removeAgentCreditsOnMerge",
            "Remove agent credits when merging",
            SettingsPage.SourceControl,
            boolean = true,
        ),
        NativeSetting(
            "newWorktreesStartFromOrigin",
            "Start worktrees from remote",
            SettingsPage.Worktrees,
            boolean = true,
        ),
        NativeSetting(
            "worktreeSubmodules",
            "Submodules",
            SettingsPage.Worktrees,
            listOf(
                SettingsChoice(null, "Inherit repository setting"),
                SettingsChoice("recursive", "Recursive"),
                SettingsChoice("top-level", "Top level only"),
                SettingsChoice("none", "Skip"),
            ),
        ),
        NativeSetting(
            "branchNamingMode",
            "Branch naming",
            SettingsPage.Worktrees,
            listOf(
                SettingsChoice("static", "Static prefix"),
                SettingsChoice("semantic", "Semantic"),
                SettingsChoice("custom", "Custom instructions"),
            ),
        ),
        NativeSetting("branchNamePrefix", "Branch prefix", SettingsPage.Worktrees),
        NativeSetting(
            "branchNameInstructions",
            "Branch naming instructions",
            SettingsPage.Worktrees,
        ),
        NativeSetting(
            "worktreesDirectory",
            "Worktrees directory",
            SettingsPage.Worktrees,
            projectScoped = false,
            capability = "worktreesDirectory",
        ),
        NativeSetting(
            "enableProviderUpdateChecks",
            "Check provider updates",
            SettingsPage.Maintenance,
            boolean = true,
            projectScoped = false,
        ),
        NativeSetting(
            "continueThreadsAfterServerUpdate",
            "Continue interrupted threads after restart",
            SettingsPage.Maintenance,
            boolean = true,
            capability = "threadRestartContinuation",
        ),
    )

class SettingsTools(private val repository: T3Repository) {
    fun config(): JsonObject = repository.state.value.serverConfig ?: JsonObject(emptyMap())

    fun settings(): JsonObject = config().obj("settings")

    fun capability(name: String) = config().obj("environment").obj("capabilities").bool(name)

    fun settingValue(setting: NativeSetting, projectId: String?): JsonElement? {
        val settings = settings()
        val overrides = projectId?.let { settings.obj("projectSettingsOverrides").obj(it) }
        return overrides?.get(setting.key) ?: settings[setting.key]
    }

    fun managedReason(path: String): String? = managedSettingReason(config(), path)

    fun settingManagedReason(setting: NativeSetting, projectId: String?): String? =
        managedReason(setting.key)
            ?: projectId?.let { managedReason("projectSettingsOverrides.$it.${setting.key}") }

    fun settingAllowed(setting: NativeSetting, value: JsonElement): Boolean {
        if (setting.key != "defaultRuntimeMode") return true
        val ceiling =
            config().obj("managedPolicy").obj("limits").nullableString("maxRuntimeMode")
                ?: return true
        val modes = listOf("approval-required", "auto-accept-edits", "auto", "full-access")
        return modes.indexOf((value as? JsonPrimitive)?.content) <= modes.indexOf(ceiling)
    }

    fun providerManagedReason(instanceId: String): String? {
        val provider =
            config().array("providers").filterIsInstance<JsonObject>().firstOrNull {
                it.string("instanceId") == instanceId
            }
        val allowed = config().obj("managedPolicy").obj("limits").obj("providers")["allowedDrivers"]
        if (
            allowed != null &&
                provider != null &&
                config()
                    .obj("managedPolicy")
                    .obj("limits")
                    .obj("providers")
                    .array("allowedDrivers")
                    .none { it == JsonPrimitive(provider.string("driver")) }
        )
            return "Managed by ${config().obj("managedPolicy").string("organization", "your organization")}."
        return managedReason("providerInstances.$instanceId.enabled")
    }

    suspend fun save(
        setting: NativeSetting,
        value: JsonElement,
        projectId: String? = null,
    ): JsonObject {
        check(setting in nativeSettings) { "This setting is unavailable." }
        require(projectId == null || setting.projectScoped) {
            "This setting applies to the environment."
        }
        require(projectId == null || capability("projectSettingsOverrides")) {
            "This server does not support project settings overrides."
        }
        require(setting.capability == null || capability(setting.capability)) {
            "This server does not support that setting."
        }
        check(settingManagedReason(setting, projectId) == null) {
            settingManagedReason(setting, projectId).orEmpty()
        }
        require(settingAllowed(setting, value)) {
            "This permission mode exceeds the organization’s policy."
        }
        val valid =
            when {
                setting.boolean -> value == JsonPrimitive(true) || value == JsonPrimitive(false)
                setting.number ->
                    value == JsonNull ||
                        value is JsonPrimitive &&
                            !value.isString &&
                            value.doubleOrNull?.let { it.isFinite() && it in 1.0..90.0 } == true
                setting.choices.isNotEmpty() ->
                    setting.choices.any { choice ->
                        if (choice.value == null) value == JsonNull
                        else value == JsonPrimitive(choice.value)
                    }
                else -> value is JsonPrimitive && value.isString
            }
        require(valid) { "Choose a valid value for ${setting.title}." }
        return write(buildSettingsPatch(settings(), setting.key, value, projectId))
    }

    suspend fun inherit(setting: NativeSetting, projectId: String): JsonObject {
        require(setting.projectScoped)
        require(capability("projectSettingsOverrides")) {
            "This server does not support project settings overrides."
        }
        check(settingManagedReason(setting, projectId) == null) {
            settingManagedReason(setting, projectId).orEmpty()
        }
        return write(buildSettingsPatch(settings(), setting.key, null, projectId))
    }

    suspend fun providerEnabled(instanceId: String, enabled: Boolean): JsonObject {
        check(providerManagedReason(instanceId) == null) {
            providerManagedReason(instanceId).orEmpty()
        }
        val provider =
            config().array("providers").filterIsInstance<JsonObject>().firstOrNull {
                it.string("instanceId") == instanceId
            } ?: error("Provider is unavailable.")
        val current = settings().obj("providerInstances")[instanceId] as? JsonObject
        val instance =
            JsonObject(
                (current ?: buildJsonObject { put("driver", provider.string("driver")) }) +
                    ("enabled" to JsonPrimitive(enabled))
            )
        return repository
            .callTool(
                "server.updateSettings",
                buildJsonObject {
                    put("patch", JsonObject(emptyMap()))
                    put(
                        "providerInstanceMutation",
                        buildJsonObject {
                            put("operation", "upsert")
                            put("instanceId", instanceId)
                            put("instance", instance)
                        },
                    )
                },
            )
            .let { it as? JsonObject ?: error("Invalid settings response.") }
    }

    suspend fun renameProject(projectId: String, title: String) =
        mutateProject(
            projectId,
            buildJsonObject {
                require(title.isNotBlank()) { "Enter a project name." }
                put("title", title.trim())
            },
        )

    suspend fun organizeProject(
        projectId: String,
        pinned: Boolean? = null,
        archived: Boolean? = null,
    ): JsonElement {
        check(capability("projectOrganization")) {
            "Project organization is unavailable on this server."
        }
        return mutateProject(
            projectId,
            buildJsonObject {
                pinned?.let { put("pinned", it) }
                archived?.let { put("archived", it) }
            },
        )
    }

    suspend fun deleteProject(projectId: String): JsonElement =
        repository.callTool(
            "projects.mutate",
            buildJsonObject {
                put("type", "project.delete")
                put("commandId", newId())
                put("projectId", projectId)
            },
        )

    private suspend fun mutateProject(projectId: String, patch: JsonObject): JsonElement =
        repository.callTool(
            "projects.mutate",
            JsonObject(
                patch +
                    mapOf(
                        "type" to JsonPrimitive("project.update"),
                        "commandId" to JsonPrimitive(newId()),
                        "projectId" to JsonPrimitive(projectId),
                    )
            ),
        )

    private suspend fun write(patch: JsonObject): JsonObject =
        repository.callTool("server.updateSettings", buildJsonObject { put("patch", patch) }).let {
            it as? JsonObject ?: error("Invalid settings response.")
        }

    fun observeStorage(
        onDatabase: (JsonObject?) -> Unit,
        onReport: (JsonObject?) -> Unit,
        onError: (Throwable) -> Unit,
    ): AutoCloseable {
        val subscriptions = mutableListOf<AutoCloseable>()
        try {
            if (capability("databaseStorage"))
                subscriptions +=
                    repository.streamValueTool(
                        "server.getDatabaseStorage",
                        JsonObject(emptyMap()),
                        onError,
                    ) {
                        onDatabase(it as? JsonObject)
                    }
            if (capability("storageCleanup"))
                subscriptions +=
                    repository.streamValueTool(
                        "server.getStorageCleanupReport",
                        JsonObject(emptyMap()),
                        onError,
                    ) {
                        onReport(it as? JsonObject)
                    }
        } catch (error: Exception) {
            subscriptions.forEach(AutoCloseable::close)
            throw error
        }
        return AutoCloseable { subscriptions.forEach(AutoCloseable::close) }
    }

    suspend fun cleanup(): JsonObject {
        check(capability("storageCleanupRun")) { "Storage cleanup is unavailable on this server." }
        return repository.callTool("server.runStorageCleanup", JsonObject(emptyMap())).let {
            it as? JsonObject ?: error("Invalid cleanup response.")
        }
    }

    fun observeWorktreeSetup(
        threadId: String,
        onError: (Throwable) -> Unit,
        onValue: (WorktreeSetupStatus?) -> Unit,
    ): AutoCloseable =
        repository.streamValueTool(
            "subscribeWorktreeSetup",
            buildJsonObject { put("threadId", threadId) },
            onError,
        ) { value ->
            onValue((value as? JsonObject)?.let(::parseWorktreeSetup))
        }

    suspend fun cancelWorktreeSetup(threadId: String): Boolean =
        repository
            .callTool("worktreeSetup.cancel", buildJsonObject { put("threadId", threadId) })
            .let { (it as? JsonObject)?.bool("cancelled") == true }
}

internal fun managedSettingReason(config: JsonObject, path: String): String? {
    val policy = config["managedPolicy"] as? JsonObject ?: return null
    return if (
        policy.array("lockedSettings").any { raw ->
            val locked = (raw as? JsonPrimitive)?.content ?: return@any false
            locked == path || locked.startsWith("$path.") || path.startsWith("$locked.")
        }
    )
        "Managed by ${policy.string("organization", "your organization")}."
    else null
}

internal fun buildSettingsPatch(
    settings: JsonObject,
    key: String,
    value: JsonElement?,
    projectId: String?,
): JsonObject {
    if (projectId == null) return buildJsonObject { put(key, value ?: JsonNull) }
    val previous = settings.obj("projectSettingsOverrides").obj(projectId)
    val next = previous.toMutableMap()
    if (
        value == null ||
            value == JsonNull &&
                key !in
                    setOf(
                        "sidebarAutoSettleAfterDays",
                        "defaultModelSelection",
                        "sourceControlWriterModelSelection",
                        "reviewGuideModelSelection",
                        "pullRequestMergeMethod",
                    )
    )
        next.remove(key)
    else next[key] = value
    return buildJsonObject {
        put("projectSettingsOverrides", buildJsonObject { put(projectId, JsonObject(next)) })
    }
}

data class WorktreeSetupStage(
    val id: String,
    val status: String,
    val detail: String?,
    val percent: Long?,
    val tail: List<String>,
)

data class WorktreeSetupStatus(
    val threadId: String,
    val phase: String,
    val sequence: Long,
    val branch: String?,
    val worktreePath: String?,
    val terminalId: String?,
    val error: String?,
    val stages: List<WorktreeSetupStage>,
)

internal fun parseWorktreeSetup(value: JsonObject) =
    WorktreeSetupStatus(
        value.string("threadId"),
        value.string("phase"),
        value.long("sequence"),
        value.nullableString("branch"),
        value.nullableString("worktreePath"),
        value.obj("setupScript").nullableString("terminalId"),
        value.nullableString("error"),
        value.array("stages").filterIsInstance<JsonObject>().map { stage ->
            WorktreeSetupStage(
                stage.string("id"),
                stage.string("status"),
                stage.nullableString("detail"),
                (stage["percent"] as? JsonPrimitive)?.content?.toLongOrNull(),
                stage.array("tail").mapNotNull { (it as? JsonPrimitive)?.content },
            )
        },
    )

internal fun retainWorktreeSetup(
    previous: WorktreeSetupStatus?,
    next: WorktreeSetupStatus?,
    threadId: String,
): WorktreeSetupStatus? {
    val valid = next?.takeIf { it.threadId == threadId }
    val held = previous?.takeIf { it.threadId == threadId }
    if (valid == null) return held
    return if (held != null && valid.sequence < held.sequence) held else valid
}

internal fun worktreeSetupCanCancel(snapshot: WorktreeSetupStatus): Boolean =
    snapshot.phase == "running" && snapshot.stages.none { it.id == "agent" && it.status == "done" }
