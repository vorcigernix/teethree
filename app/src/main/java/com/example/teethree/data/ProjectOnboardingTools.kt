package com.example.teethree.data

import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SourceControlHost(
    val kind: String,
    val label: String,
    val available: Boolean,
    val authenticated: Boolean,
    val account: String?,
    val host: String?,
    val detail: String?,
    val installHint: String,
    val appSignIn: Boolean,
)

data class ForgeRepository(
    val provider: String,
    val name: String,
    val url: String,
    val sshUrl: String,
)

data class ProjectCloneStatus(
    val projectId: String,
    val remoteUrl: String,
    val destination: String,
    val phase: String,
    val stage: String,
    val percent: Long?,
    val detail: String?,
    val stalled: Boolean,
    val error: String?,
    val sequence: Long,
)

data class CreatedProject(val projectId: String, val workspaceRoot: String, val warning: String?)

data class SourceControlSignIn(val code: String, val verificationUri: String, val expiresAt: Long)

data class ProjectFolder(val name: String, val path: String)

data class ProjectFolderListing(val parent: String, val folders: List<ProjectFolder>)

class ProjectOnboardingTools(private val repository: T3Repository) {
    fun trackedClonesAvailable(): Boolean =
        repository.state.value.serverConfig
            ?.obj("environment")
            ?.obj("capabilities")
            ?.bool("projectCloneTracking") == true

    suspend fun discover(): List<SourceControlHost> =
        repository.callTool("server.discoverSourceControl", JsonObject(emptyMap())).let { raw ->
            (raw as? JsonObject)
                ?.array("sourceControlProviders")
                ?.filterIsInstance<JsonObject>()
                ?.map(::parseSourceControlHost)
                .orEmpty()
        }

    suspend fun lookup(provider: String, name: String): ForgeRepository {
        require(name.isNotBlank()) { "Enter an owner and repository name." }
        val result =
            repository.callTool(
                "sourceControl.lookupRepository",
                buildJsonObject {
                    put("provider", provider)
                    put("repository", name.trim())
                },
            ) as? JsonObject ?: error("Invalid repository response.")
        return ForgeRepository(
            result.string("provider"),
            result.string("nameWithOwner"),
            result.string("url"),
            result.string("sshUrl"),
        )
    }

    suspend fun startClone(
        title: String,
        destination: String,
        remoteUrl: String?,
        provider: String?,
        repositoryName: String?,
        protocol: String,
    ): String {
        check(trackedClonesAvailable()) {
            "Update this environment to a server with clone progress support."
        }
        require(title.isNotBlank()) { "Enter a project name." }
        require(validProjectPath(destination)) {
            "Enter an absolute path on the environment, or a path beginning with ~/."
        }
        require(protocol in setOf("auto", "ssh", "https"))
        val remote = remoteUrl?.let(::normalizeProjectCloneUrl)
        require(remote != null || !provider.isNullOrBlank() && !repositoryName.isNullOrBlank()) {
            "Choose a repository or enter a Git URL."
        }
        val projectId = newId()
        repository.callTool(
            "projectClone.start",
            buildJsonObject {
                put("projectId", projectId)
                put("title", title.trim())
                put("createdAt", Instant.now().toString())
                put("destinationPath", destination.trim())
                put("protocol", protocol)
                if (remote != null) put("remoteUrl", remote)
                if (!provider.isNullOrBlank()) put("provider", provider)
                if (!repositoryName.isNullOrBlank()) put("repository", repositoryName.trim())
            },
        )
        return projectId
    }

    fun observeClones(
        onError: (Throwable) -> Unit,
        onValue: (List<ProjectCloneStatus>) -> Unit,
    ): AutoCloseable =
        repository.streamValueTool("subscribeProjectClones", JsonObject(emptyMap()), onError) { raw
            ->
            onValue(
                (raw as? JsonArray)
                    ?.filterIsInstance<JsonObject>()
                    ?.map(::parseProjectClone)
                    .orEmpty()
            )
        }

    suspend fun cloneAction(projectId: String, retry: Boolean): Boolean =
        repository
            .callTool(
                if (retry) "projectClone.retry" else "projectClone.cancel",
                buildJsonObject { put("projectId", projectId) },
            )
            .let { (it as? JsonObject)?.bool("applied") == true }

    suspend fun createNew(name: String): CreatedProject {
        require(name.isNotBlank() && name.trim().length <= 200) {
            "Enter a project name with at most 200 characters."
        }
        val result =
            repository.callTool("projects.createNew", buildJsonObject { put("name", name.trim()) })
                as? JsonObject ?: error("Invalid project response.")
        return CreatedProject(
            result.string("projectId"),
            result.string("workspaceRoot"),
            result.nullableString("commitError"),
        )
    }

    suspend fun importFolder(title: String, path: String, createIfMissing: Boolean): String {
        require(title.isNotBlank()) { "Enter a project name." }
        require(validProjectPath(path)) {
            "Enter an absolute path on the environment, or a path beginning with ~/."
        }
        val existing = findProjectAtPath(repository.state.value.projects, path)
        if (existing != null) {
            if (existing.archived)
                SettingsTools(repository).organizeProject(existing.id, archived = false)
            return existing.id
        }
        val id = newId()
        repository.callTool(
            "projects.mutate",
            buildJsonObject {
                put("type", "project.create")
                put("commandId", newId())
                put("projectId", id)
                put("title", title.trim())
                put("workspaceRoot", path.trim())
                put("createWorkspaceRootIfMissing", createIfMissing)
            },
        )
        return id
    }

    suspend fun browse(path: String): ProjectFolderListing {
        require(path.isNotBlank() && path.length <= 512) {
            "Enter a host folder path with at most 512 characters."
        }
        val result =
            repository.callTool(
                "filesystem.browse",
                buildJsonObject { put("partialPath", projectBrowseDirectory(path)) },
            ) as? JsonObject ?: error("Invalid folder response.")
        return ProjectFolderListing(
            result.string("parentPath"),
            result.array("entries").filterIsInstance<JsonObject>().map {
                ProjectFolder(it.string("name"), it.string("fullPath"))
            },
        )
    }

    suspend fun publish(
        cwd: String,
        provider: String,
        name: String,
        visibility: String,
    ): ForgeRepository {
        require(validProjectPath(cwd)) { "Choose a workspace folder on the environment." }
        require(name.isNotBlank()) { "Enter a repository name." }
        require(visibility in setOf("private", "public"))
        val result =
            repository.callTool(
                "sourceControl.publishRepository",
                buildJsonObject {
                    put("cwd", cwd)
                    put("provider", provider)
                    put("repository", name.trim())
                    put("visibility", visibility)
                },
            ) as? JsonObject ?: error("Invalid publishing response.")
        val value = result.obj("repository")
        return ForgeRepository(
            value.string("provider"),
            value.string("nameWithOwner"),
            value.string("url"),
            value.string("sshUrl"),
        )
    }

    suspend fun startSignIn(provider: String, host: String): SourceControlSignIn {
        val result =
            repository.callTool("sourceControl.signInStart", signInInput(provider, host))
                as? JsonObject ?: error("Invalid sign-in response.")
        val uri = result.string("verificationUri")
        require(providerBrowserUrlAllowed(uri)) {
            "The environment returned an unsafe verification address."
        }
        return SourceControlSignIn(result.string("userCode"), uri, result.long("expiresAt"))
    }

    suspend fun waitForSignIn(provider: String, host: String) =
        repository.callTool("sourceControl.signInWait", signInInput(provider, host))

    suspend fun cancelSignIn(provider: String, host: String) =
        repository.callTool("sourceControl.signInCancel", signInInput(provider, host))

    suspend fun signOut(provider: String, host: String) {
        val config =
            repository.state.value.serverConfig ?: error("Environment settings are unavailable.")
        check(managedSettingReason(config, "sourceControlHosts.$provider") == null) {
            managedSettingReason(config, "sourceControlHosts.$provider").orEmpty()
        }
        val signIns = config.obj("settings").obj("sourceControlHosts").obj(provider).obj("signIns")
        repository.callTool(
            "server.updateSettings",
            buildJsonObject {
                put(
                    "patch",
                    buildJsonObject {
                        put(
                            "sourceControlHosts",
                            buildJsonObject {
                                put(
                                    provider,
                                    buildJsonObject {
                                        put(
                                            "signIns",
                                            JsonObject(signIns + (host to JsonPrimitive(""))),
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    private fun signInInput(provider: String, host: String): JsonObject {
        val uri = runCatching { URI("https://${host.trim()}") }.getOrNull()
        require(
            !provider.isBlank() &&
                uri?.host != null &&
                uri.rawUserInfo == null &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        ) {
            "Enter a source-control host name."
        }
        return buildJsonObject {
            put("provider", provider)
            put("host", host.trim())
        }
    }
}

internal fun parseSourceControlHost(value: JsonObject) =
    SourceControlHost(
        value.string("kind"),
        value.string("label", value.string("kind")),
        value.string("status") == "available",
        value.obj("auth").string("status") == "authenticated",
        sourceControlOption(value.obj("auth")["account"]),
        sourceControlOption(value.obj("auth")["host"]),
        sourceControlOption(value.obj("auth")["detail"]) ?: sourceControlOption(value["detail"]),
        value.string("installHint"),
        value.bool("appSignIn"),
    )

internal fun sourceControlOption(value: JsonElement?): String? =
    when (value) {
        null,
        JsonNull -> null
        is JsonPrimitive -> value.content
        is JsonObject -> value.nullableString("value").takeIf { value.string("_tag") == "Some" }
        else -> null
    }

internal fun parseProjectClone(value: JsonObject) =
    ProjectCloneStatus(
        value.string("projectId"),
        value.string("remoteUrl"),
        value.string("destinationPath"),
        value.string("phase"),
        value.string("stage"),
        (value["percent"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it in 0L..100L },
        value.nullableString("detail"),
        value.bool("stalled"),
        value.nullableString("error"),
        value.long("sequence"),
    )

internal fun mergeProjectClones(
    previous: List<ProjectCloneStatus>,
    incoming: List<ProjectCloneStatus>,
): List<ProjectCloneStatus> = incoming.map { next ->
    previous.firstOrNull { it.projectId == next.projectId && it.sequence > next.sequence } ?: next
}

internal fun projectCloneSummary(value: ProjectCloneStatus): String =
    listOfNotNull(
            when (value.stage) {
                "connecting" -> "Connecting"
                "counting" -> "Counting objects"
                "receiving" -> "Receiving objects"
                "resolving" -> "Resolving deltas"
                "checkout" -> "Checking out files"
                else -> value.stage
            },
            value.percent?.let { "$it%" },
            if (value.stalled) "Stalled" else value.detail,
        )
        .joinToString(" · ")

internal fun validProjectPath(path: String): Boolean {
    val value = path.trim()
    return value.isNotEmpty() &&
        value.length <= 512 &&
        value.none(Char::isISOControl) &&
        (value.startsWith('/') ||
            value.startsWith("~/") ||
            value.startsWith("~\\") ||
            value.startsWith("\\\\") ||
            value.length >= 3 && value[0].isLetter() && value[1] == ':' && value[2] in "/\\")
}

internal fun normalizeProjectCloneUrl(value: String): String {
    val remote =
        value.trim().let {
            if (Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]+$").matches(it))
                "https://github.com/${it.removeSuffix(".git")}.git"
            else it
        }
    require(remote.isNotBlank() && remote.none(Char::isISOControl) && !remote.startsWith('-')) {
        "Enter a Git repository address."
    }
    val uri = runCatching { URI(remote) }.getOrNull()
    val scp = Regex("^[^/@\\s:]+@[^/\\s:]+:.+$").matches(remote)
    require(scp || uri?.scheme?.lowercase() in setOf("https", "http", "ssh", "git", "file")) {
        "Use an HTTPS, SSH or Git repository address."
    }
    require(scp || uri?.scheme?.lowercase() == "file" || !uri?.host.isNullOrBlank()) {
        "Enter a repository host."
    }
    require(uri?.scheme?.lowercase() != "file" || !uri.path.isNullOrBlank()) {
        "Enter a repository path."
    }
    return remote
}

internal fun projectCloneDirectoryName(value: String): String {
    val withoutQuery = value.substringBefore('?').substringBefore('#').trim()
    val schemeIndex = withoutQuery.indexOf("://")
    val hasHost = schemeIndex >= 0 || Regex("^[^/\\\\:]+@[^/\\\\:]+:").containsMatchIn(withoutQuery)
    val path = if (schemeIndex >= 0) withoutQuery.substring(schemeIndex + 3) else withoutQuery
    val pieces = path.split(Regex("[/\\\\:]+")).filter(String::isNotBlank)
    if (hasHost && (pieces.size < 2 || pieces.size == 2 && pieces.last().all(Char::isDigit)))
        return ""
    return pieces
        .lastOrNull()
        ?.removeSuffix(".git")
        ?.takeUnless { it == "." || it == ".." }
        .orEmpty()
}

internal fun cloneDestination(parent: String, name: String): String {
    if (name.isBlank()) return parent
    val separator = if ('\\' in parent && '/' !in parent) "\\" else "/"
    return parent.trimEnd('/', '\\') + separator + name
}

internal fun projectBrowseDirectory(path: String): String {
    val value = path.trim()
    return if (value.endsWith('/') || value.endsWith('\\') || value == "~") value
    else value + if ('\\' in value && '/' !in value) "\\" else "/"
}

internal fun findProjectAtPath(projects: List<T3Project>, path: String): T3Project? {
    fun normalized(value: String): String {
        val trimmed = value.trim()
        val windows =
            Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(trimmed) ||
                trimmed.startsWith("\\\\") ||
                trimmed.startsWith("//")
        return if (windows) trimmed.replace('\\', '/').trimEnd('/').lowercase()
        else trimmed.trimEnd('/').ifEmpty { "/" }
    }
    return projects.firstOrNull { normalized(it.workspaceRoot) == normalized(path) }
}
