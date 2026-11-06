package com.example.teethree.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class EffectiveProjectDefaults(
    val modelSelection: JsonObject?,
    val runtimeMode: String,
    val workspaceMode: String,
    val scripts: JsonArray,
    val startFromOrigin: Boolean,
    val submodules: String,
)

data class ProjectDefaultModelOption(
    val instanceId: String,
    val model: String,
    val label: String,
    val isDefault: Boolean,
)

data class ProjectAction(
    val id: String,
    val name: String,
    val command: String,
    val icon: String = "play",
    val setup: Boolean = false,
    val waitForSetup: Boolean = false,
    val onSettle: Boolean = false,
    val previewUrl: String? = null,
    val autoOpenPreview: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
)

fun effectiveProjectDefaults(
    config: JsonObject?,
    project: T3Project?,
    projectFile: JsonObject? = null,
): EffectiveProjectDefaults {
    val configuration = config ?: JsonObject(emptyMap())
    val settings = configuration.obj("settings")
    val overrides =
        project?.let { settings.obj("projectSettingsOverrides").obj(it.id) }
            ?: JsonObject(emptyMap())
    fun value(key: String): JsonElement? = overrides[key] ?: settings[key]
    val options = projectDefaultModelOptions(configuration, project?.id)
    val selected = configuredProjectModel(settings, project)
    val usable = selected?.takeIf { choice ->
        options.any {
            it.instanceId == choice.string("instanceId") && it.model == choice.string("model")
        }
    }
    val globalFallback =
        if (selected != null && usable == null)
            (settings["defaultModelSelection"] as? JsonObject)?.takeIf { choice ->
                options.any {
                    it.instanceId == choice.string("instanceId") &&
                        it.model == choice.string("model")
                }
            }
        else null
    val fallback = options.firstOrNull { it.isDefault } ?: options.firstOrNull()
    val modes = listOf("approval-required", "auto-accept-edits", "auto", "full-access")
    val requestedMode =
        (value("defaultRuntimeMode") as? JsonPrimitive)?.content?.takeIf { it in modes }
            ?: "full-access"
    val ceiling = configuration.obj("managedPolicy").obj("limits").nullableString("maxRuntimeMode")
    val runtimeMode =
        if (ceiling in modes && modes.indexOf(requestedMode) > modes.indexOf(ceiling)) ceiling!!
        else requestedMode
    val legacyWorkspace =
        if (!settings.bool("projectSettingsFolded")) project?.defaultThreadEnvMode else null
    val workspace =
        ((if (overrides.containsKey("defaultThreadEnvMode")) overrides["defaultThreadEnvMode"]
            else legacyWorkspace?.let(::JsonPrimitive) ?: settings["defaultThreadEnvMode"])
                as? JsonPrimitive)
            ?.content
            ?.takeIf { it in setOf("local", "worktree") }
            ?: projectFile?.nullableString("defaultThreadEnvMode")?.takeIf {
                it in setOf("local", "worktree")
            }
            ?: "local"
    val submodules =
        (value("worktreeSubmodules") as? JsonPrimitive)?.content?.takeIf {
            it in setOf("recursive", "top-level", "none")
        }
            ?: projectFile?.nullableString("worktreeSubmodules")?.takeIf {
                it in setOf("recursive", "top-level", "none")
            }
            ?: "recursive"
    return EffectiveProjectDefaults(
        usable
            ?: globalFallback
            ?: fallback?.let { model ->
                buildJsonObject {
                    put("instanceId", model.instanceId)
                    put("model", model.model)
                }
            },
        runtimeMode,
        workspace,
        effectiveProjectScripts(settings, project),
        (value("newWorktreesStartFromOrigin") as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
            ?: true,
        submodules,
    )
}

fun resolvedNewTaskWorkspaceMode(
    config: JsonObject?,
    project: T3Project?,
    projectHasThreads: Boolean,
    projectFile: JsonObject? = null,
): String {
    val configuration = config ?: JsonObject(emptyMap())
    val workspace = effectiveProjectDefaults(config, project, projectFile).workspaceMode
    if (project == null) return workspace
    val scratch = configuration.nullableString("scratchWorkspaceRoot")
    if (scratch != null && findProjectAtPath(listOf(project), scratch) != null) return "local"
    if (workspace == "local" || projectHasThreads) return workspace
    val settings = configuration.obj("settings")
    val overrides = settings.obj("projectSettingsOverrides").obj(project.id)
    val legacy = if (!settings.bool("projectSettingsFolded")) project.defaultThreadEnvMode else null
    val projectValue =
        if (overrides.containsKey("defaultThreadEnvMode")) overrides["defaultThreadEnvMode"]
        else legacy?.let(::JsonPrimitive)
    val hasProjectValue = overrides.containsKey("defaultThreadEnvMode") || legacy != null
    val resolved = if (hasProjectValue) projectValue else settings["defaultThreadEnvMode"]
    val sourceIsEnvironment =
        if (resolved is JsonPrimitive && resolved.content in setOf("local", "worktree"))
            !hasProjectValue
        else projectFile?.nullableString("defaultThreadEnvMode") == null
    if (!sourceIsEnvironment) return workspace
    val root = configuration.nullableString("newProjectsRoot") ?: return workspace
    val folder = project.workspaceRoot.trim()
    val windows =
        Regex("^[A-Za-z]:").containsMatchIn(root) || root.startsWith("\\") || root.startsWith("//")
    fun comparison(value: String): String =
        if (windows) value.trim().replace('\\', '/').trimEnd('/').lowercase()
        else value.trim().trimEnd('/').ifEmpty { "/" }
    val normalizedRoot = comparison(root)
    val prefix = if (normalizedRoot.endsWith('/')) normalizedRoot else "$normalizedRoot/"
    val normalizedFolder = comparison(folder)
    val child = normalizedFolder.removePrefix(prefix)
    return if (normalizedFolder.startsWith(prefix) && child.isNotEmpty() && '/' !in child) "local"
    else workspace
}

internal fun configuredProjectModel(settings: JsonObject, project: T3Project?): JsonObject? {
    val overrides = project?.let { settings.obj("projectSettingsOverrides").obj(it.id) }
    if (overrides?.containsKey("defaultModelSelection") == true)
        return overrides["defaultModelSelection"] as? JsonObject
    if (!settings.bool("projectSettingsFolded") && project?.defaultModelSelection != null)
        return project.defaultModelSelection
    return settings["defaultModelSelection"] as? JsonObject
}

internal fun effectiveProjectScripts(settings: JsonObject, project: T3Project?): JsonArray {
    val defaults = settings["defaultProjectScripts"] as? JsonArray ?: JsonArray(emptyList())
    if (project == null) return defaults
    val scoped =
        settings.obj("projectSettingsOverrides").obj(project.id)["defaultProjectScripts"]
            as? JsonArray
    if (scoped != null) return scoped
    if (settings.bool("projectSettingsFolded")) return defaults
    val legacy = settings.obj("projectScriptOverrides")
    if (legacy[project.id] == JsonNull) return defaults
    return legacy[project.id] as? JsonArray
        ?: project.scripts.takeIf { it.isNotEmpty() }
        ?: defaults
}

fun projectDefaultModelOptions(
    config: JsonObject,
    projectId: String?,
): List<ProjectDefaultModelOption> {
    val projectProviders = projectId?.let {
        config.obj("settings").obj("projectSettingsOverrides").obj(it).obj("providerInstances")
    }
    val allowed =
        config.obj("managedPolicy").obj("limits").obj("providers")["allowedDrivers"] as? JsonArray
    return config
        .array("providers")
        .filterIsInstance<JsonObject>()
        .filter { provider ->
            val enabled =
                (projectProviders?.obj(provider.string("instanceId"))?.get("enabled")
                        as? JsonPrimitive)
                    ?.booleanOrNull ?: provider.bool("enabled")
            enabled &&
                provider.bool("installed") &&
                provider.string("availability") != "unavailable" &&
                (allowed == null || JsonPrimitive(provider.string("driver")) in allowed)
        }
        .flatMap { provider ->
            provider.array("models").filterIsInstance<JsonObject>().mapNotNull { model ->
                val slug = model.string("slug")
                if (slug.isBlank()) null
                else
                    ProjectDefaultModelOption(
                        provider.string("instanceId"),
                        slug,
                        "${provider.string("displayName", provider.string("driver"))} · ${model.string("name", slug)}",
                        model.bool("isDefault"),
                    )
            }
        }
}

class ProjectDefaultsTools(private val repository: T3Repository) {
    fun config() = repository.state.value.serverConfig ?: JsonObject(emptyMap())

    fun project(projectId: String?) =
        repository.state.value.projects.firstOrNull { it.id == projectId }

    fun actions(projectId: String?) =
        effectiveProjectScripts(config().obj("settings"), project(projectId))
            .filterIsInstance<JsonObject>()
            .mapNotNull(::parseProjectAction)

    fun currentModel(projectId: String?) =
        configuredProjectModel(config().obj("settings"), project(projectId))

    fun managedReason(key: String, projectId: String?): String? =
        managedSettingReason(config(), key)
            ?: projectId?.let {
                managedSettingReason(config(), "projectSettingsOverrides.$it.$key")
            }

    private fun guard(key: String, projectId: String?) {
        check(managedReason(key, projectId) == null) { managedReason(key, projectId).orEmpty() }
        check(
            projectId == null ||
                config().obj("environment").obj("capabilities").bool("projectSettingsOverrides")
        ) {
            "This server does not support project settings overrides."
        }
    }

    suspend fun model(projectId: String?, instanceId: String?, model: String?) {
        guard("defaultModelSelection", projectId)
        val selection =
            if (instanceId == null || model == null) JsonNull
            else {
                require(
                    projectDefaultModelOptions(config(), projectId).any {
                        it.instanceId == instanceId && it.model == model
                    }
                ) {
                    "Choose an available model for this project."
                }
                val previous =
                    currentModel(projectId)?.takeIf {
                        it.string("instanceId") == instanceId && it.string("model") == model
                    }
                JsonObject(
                    (previous ?: JsonObject(emptyMap())) +
                        mapOf(
                            "instanceId" to JsonPrimitive(instanceId),
                            "model" to JsonPrimitive(model),
                        )
                )
            }
        write("defaultModelSelection", selection, projectId)
    }

    suspend fun modelOptions(projectId: String?, options: JsonArray?) {
        guard("defaultModelSelection", projectId)
        val previous = currentModel(projectId) ?: error("Choose a preferred model first.")
        require(
            projectDefaultModelOptions(config(), projectId).any {
                it.instanceId == previous.string("instanceId") &&
                    it.model == previous.string("model")
            }
        ) {
            "Choose an available model first."
        }
        val fields = previous.toMutableMap()
        if (options == null) fields.remove("options") else fields["options"] = options
        write("defaultModelSelection", JsonObject(fields), projectId)
    }

    suspend fun importActions(projectId: String, file: JsonObject) {
        guard("defaultProjectScripts", projectId)
        var next = effectiveProjectScripts(config().obj("settings"), project(projectId))
        val names = next.filterIsInstance<JsonObject>().map { it.string("name") }.toMutableSet()
        file.array("scripts").filterIsInstance<JsonObject>().forEach { raw ->
            if (names.add(raw.string("name"))) {
                val action =
                    parseProjectAction(JsonObject(raw + ("id" to JsonPrimitive(newId()))))
                        ?: error("The project file contains an invalid action.")
                next = updateProjectActions(next, action)
            }
        }
        write("defaultProjectScripts", next, projectId)
    }

    suspend fun inherit(projectId: String, key: String) {
        require(key in setOf("defaultModelSelection", "defaultProjectScripts"))
        guard(key, projectId)
        write(key, null, projectId)
    }

    suspend fun saveAction(projectId: String?, edited: ProjectAction) {
        guard("defaultProjectScripts", projectId)
        val existing = effectiveProjectScripts(config().obj("settings"), project(projectId))
        write("defaultProjectScripts", updateProjectActions(existing, edited), projectId)
    }

    suspend fun removeAction(projectId: String?, id: String) {
        guard("defaultProjectScripts", projectId)
        val existing = effectiveProjectScripts(config().obj("settings"), project(projectId))
        write(
            "defaultProjectScripts",
            JsonArray(existing.filterNot { (it as? JsonObject)?.string("id") == id }),
            projectId,
        )
    }

    suspend fun moveAction(projectId: String?, id: String, offset: Int) {
        guard("defaultProjectScripts", projectId)
        require(offset == -1 || offset == 1)
        val existing =
            effectiveProjectScripts(config().obj("settings"), project(projectId)).toMutableList()
        val index = existing.indexOfFirst { (it as? JsonObject)?.string("id") == id }
        if (index < 0 || index + offset !in existing.indices) return
        val other = existing[index + offset]
        existing[index + offset] = existing[index]
        existing[index] = other
        write("defaultProjectScripts", JsonArray(existing), projectId)
    }

    suspend fun projectFile(project: T3Project): JsonObject? {
        val result =
            repository.callTool(
                "projects.readFile",
                buildJsonObject {
                    put("cwd", project.workspaceRoot)
                    put("relativePath", "t3.json")
                    put("allowMissing", true)
                },
            ) as? JsonObject ?: error("Invalid project file response.")
        if (!result.bool("exists", true)) return null
        check(!result.bool("truncated")) { "t3.json is too large to import." }
        return parseProjectDefaultsFile(result.string("contents"))
            ?: error(
                "t3.json is not valid. Correct it on the environment before importing actions."
            )
    }

    private suspend fun write(key: String, value: JsonElement?, projectId: String?) {
        repository.callTool(
            "server.updateSettings",
            buildJsonObject {
                put("patch", buildSettingsPatch(config().obj("settings"), key, value, projectId))
            },
        )
    }
}

internal val projectActionIcons = setOf("play", "test", "lint", "configure", "build", "debug")

internal fun parseProjectAction(value: JsonObject): ProjectAction? {
    if (value.string("name").isBlank() || value.string("command").isBlank()) return null
    return ProjectAction(
        value.string("id"),
        value.string("name"),
        value.string("command"),
        value.string("icon", "play"),
        value.bool("runOnWorktreeCreate"),
        value.bool("runOnWorktreeCreate") && value["async"] == JsonPrimitive(false),
        value.bool("runOnSettle"),
        value.nullableString("previewUrl"),
        value.bool("autoOpenPreview"),
        value,
    )
}

internal fun projectActionJson(action: ProjectAction): JsonObject {
    require(action.id.isNotBlank() && action.name.isNotBlank() && action.command.isNotBlank()) {
        "Enter an action name and command."
    }
    require(action.icon in projectActionIcons) { "Choose an action icon." }
    if (!action.previewUrl.isNullOrBlank())
        require(providerBrowserUrlAllowed(action.previewUrl)) {
            "Use an HTTP or HTTPS preview URL."
        }
    val fields = action.raw.toMutableMap()
    listOf("async", "runOnSettle", "previewUrl", "autoOpenPreview").forEach(fields::remove)
    fields["id"] = JsonPrimitive(action.id)
    fields["name"] = JsonPrimitive(action.name.trim())
    fields["command"] = JsonPrimitive(action.command.trim())
    fields["icon"] = JsonPrimitive(action.icon)
    fields["runOnWorktreeCreate"] = JsonPrimitive(action.setup)
    if (action.setup && action.waitForSetup) fields["async"] = JsonPrimitive(false)
    if (action.onSettle) fields["runOnSettle"] = JsonPrimitive(true)
    if (!action.previewUrl.isNullOrBlank()) {
        fields["previewUrl"] = JsonPrimitive(action.previewUrl.trim())
        fields["autoOpenPreview"] = JsonPrimitive(action.autoOpenPreview)
    }
    return JsonObject(fields)
}

internal fun updateProjectActions(existing: JsonArray, edited: ProjectAction): JsonArray {
    val saved = projectActionJson(edited)
    var replaced = false
    val next = existing.map { raw ->
        val script = raw as? JsonObject ?: return@map raw
        if (script.string("id") == edited.id) {
            replaced = true
            saved
        } else {
            val fields = script.toMutableMap()
            if (edited.setup && script.bool("runOnWorktreeCreate"))
                fields["runOnWorktreeCreate"] = JsonPrimitive(false)
            if (edited.onSettle && script.bool("runOnSettle"))
                fields["runOnSettle"] = JsonPrimitive(false)
            JsonObject(fields)
        }
    }
    return JsonArray(if (replaced) next else next + saved)
}

@OptIn(ExperimentalSerializationApi::class)
private val projectFileJson = Json {
    allowComments = true
    allowTrailingComma = true
}

internal fun parseProjectDefaultsFile(contents: String): JsonObject? {
    val file =
        runCatching { projectFileJson.parseToJsonElement(contents) as? JsonObject }.getOrNull()
            ?: return null
    if (
        file["defaultThreadEnvMode"] != null &&
            file.string("defaultThreadEnvMode") !in setOf("local", "worktree")
    )
        return null
    if (
        file["worktreeSubmodules"] != null &&
            file.string("worktreeSubmodules") !in setOf("recursive", "top-level", "none")
    )
        return null
    val scripts = file["scripts"]
    if (
        scripts != null &&
            (scripts !is JsonArray ||
                scripts.size > 50 ||
                scripts.any { raw ->
                    val value = raw as? JsonObject ?: return@any true
                    value.string("name").isBlank() ||
                        value.string("command").isBlank() ||
                        value["icon"] != null && value.string("icon") !in projectActionIcons ||
                        listOf("runOnWorktreeCreate", "runOnSettle", "async", "autoOpenPreview")
                            .any { key ->
                                value[key] != null &&
                                    value[key] !in listOf(JsonPrimitive(true), JsonPrimitive(false))
                            }
                })
    )
        return null
    return file
}
