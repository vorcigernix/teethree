package com.example.teethree.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class WorkspaceEntry(val path: String, val directory: Boolean, val ignored: Boolean)

data class WorkspaceDirectory(val entries: List<WorkspaceEntry>, val truncated: Boolean)

data class WorkspaceFile(val path: String, val contents: String, val truncated: Boolean)

data class WorkspaceChange(val path: String, val insertions: Int, val deletions: Int)

data class WorkspaceGitStatus(
    val isRepository: Boolean,
    val branch: String?,
    val rootPath: String?,
    val files: List<WorkspaceChange>,
    val ahead: Int,
    val behind: Int,
    val hasRemote: Boolean,
)

data class WorkspaceBranch(val name: String, val current: Boolean, val remote: Boolean)

data class WorkspaceBranches(val branches: List<WorkspaceBranch>, val nextCursor: Int?)

data class WorkspacePatch(val text: String, val truncated: Boolean, val nextCursor: String? = null)

data class WorkspacePullRequest(
    val projectId: String,
    val repository: String,
    val host: String,
    val number: Int,
    val title: String,
    val state: String,
    val author: String,
    val branch: String,
    val url: String,
)

data class WorkspacePullRequestList(
    val entries: List<WorkspacePullRequest>,
    val nextCursors: JsonObject,
    val errors: List<String>,
    val truncated: Boolean,
)

data class WorkspaceComment(val id: String, val author: String, val body: String)

data class WorkspacePullRequestDetail(
    val request: WorkspacePullRequest,
    val body: String,
    val baseBranch: String,
    val additions: Int,
    val deletions: Int,
    val comments: List<WorkspaceComment>,
    val commentsTruncated: Boolean,
    val canComment: Boolean,
    val verdicts: Set<String>,
    val canReadDiff: Boolean,
    val checks: List<Pair<String, String>>,
    val rawDetail: JsonObject = JsonObject(emptyMap()),
    val rawActivity: JsonObject = JsonObject(emptyMap()),
)

class WorkspaceTools(private val repository: T3Repository) {
    suspend fun directory(cwd: String, path: String): WorkspaceDirectory =
        parseWorkspaceDirectory(
            call(
                "projects.listEntries",
                buildJsonObject {
                    put("cwd", cwd)
                    if (path.isNotEmpty()) put("directoryPath", path)
                },
            )
        )

    suspend fun file(cwd: String, path: String): WorkspaceFile {
        val result =
            call(
                "projects.readFile",
                buildJsonObject {
                    put("cwd", cwd)
                    put("relativePath", path)
                },
            )
        return WorkspaceFile(
            result.string("relativePath", path),
            result.string("contents"),
            result.bool("truncated"),
        )
    }

    suspend fun gitStatus(cwd: String): WorkspaceGitStatus =
        parseWorkspaceGitStatus(call("vcs.refreshStatus", buildJsonObject { put("cwd", cwd) }))

    suspend fun branches(cwd: String, cursor: Int? = null): WorkspaceBranches {
        val result =
            call(
                "vcs.listRefs",
                buildJsonObject {
                    put("cwd", cwd)
                    put("limit", 100)
                    put("includeMatchingRemoteRefs", true)
                    if (cursor != null) put("cursor", cursor)
                },
            )
        return WorkspaceBranches(
            result.array("refs").map { raw ->
                raw.jsonObject.let {
                    WorkspaceBranch(it.string("name"), it.bool("current"), it.bool("isRemote"))
                }
            },
            result.nullableString("nextCursor")?.toIntOrNull(),
        )
    }

    suspend fun diff(cwd: String, path: String? = null): WorkspacePatch {
        val result =
            call(
                "review.getDiffPreview",
                buildJsonObject {
                    put("cwd", cwd)
                    put("sourceKind", "working-tree")
                    if (path != null)
                        put(
                            "file",
                            buildJsonObject {
                                put("path", path)
                                put("previousPath", kotlinx.serialization.json.JsonNull)
                                put("sourceKind", "working-tree")
                            },
                        )
                },
            )
        return WorkspacePatch(
            result.array("sources").joinToString("\n") { it.jsonObject.string("diff") },
            result.bool("incomplete") ||
                result.array("sources").any { it.jsonObject.bool("truncated") },
        )
    }

    suspend fun switchBranch(cwd: String, name: String) {
        call(
            "vcs.switchRef",
            buildJsonObject {
                put("cwd", cwd)
                put("refName", name)
            },
        )
    }

    suspend fun createBranch(cwd: String, name: String) {
        require(name.isNotBlank()) { "Enter a branch name." }
        call(
            "vcs.createRef",
            buildJsonObject {
                put("cwd", cwd)
                put("refName", name.trim())
                put("switchRef", true)
            },
        )
    }

    suspend fun pull(cwd: String) {
        call("vcs.pull", buildJsonObject { put("cwd", cwd) })
    }

    suspend fun gitAction(
        cwd: String,
        action: String,
        message: String = "",
        paths: Set<String> = emptySet(),
    ): String {
        require(action in setOf("commit", "push", "create_pr", "commit_push", "commit_push_pr")) {
            "Unsupported Git action."
        }
        if (action.startsWith("commit")) {
            require(message.isNotBlank()) { "Enter a commit message." }
            require(paths.isNotEmpty()) { "Select the files to commit." }
        }
        val result =
            call(
                "git.runStackedAction",
                buildJsonObject {
                    put("actionId", newId())
                    put("cwd", cwd)
                    put("action", action)
                    if (message.isNotBlank()) put("commitMessage", message)
                    if (paths.isNotEmpty()) put("filePaths", JsonArray(paths.map(::JsonPrimitive)))
                },
            )
        return result.obj("toast").string("title", "Git action completed")
    }

    suspend fun pullRequests(
        projectId: String,
        state: String,
        cursors: JsonObject? = null,
    ): WorkspacePullRequestList {
        require(state in setOf("open", "closed", "merged", "all")) {
            "Unsupported pull request state."
        }
        val result =
            call(
                "pullRequests.list",
                buildJsonObject {
                    put("projectId", projectId)
                    put("state", state)
                    put("limit", 50)
                    if (cursors != null && cursors.isNotEmpty()) put("cursors", cursors)
                },
            )
        return WorkspacePullRequestList(
            result.array("entries").map { parseWorkspacePullRequest(it.jsonObject) },
            result.obj("nextCursors"),
            result.array("errors").map { it.jsonObject.string("message") },
            result.bool("truncated"),
        )
    }

    suspend fun pullRequestDetail(request: WorkspacePullRequest): WorkspacePullRequestDetail =
        coroutineScope {
            val detail = async { call("pullRequests.detail", request.ref()) }
            val activity = async { call("pullRequests.activity", request.ref()) }
            parseWorkspacePullRequestDetail(request, detail.await(), activity.await())
        }

    suspend fun pullRequestDiff(
        request: WorkspacePullRequest,
        cursor: String? = null,
    ): WorkspacePatch {
        val payload = buildJsonObject {
            request.ref().forEach { (key, value) -> put(key, value) }
            if (cursor != null) put("cursor", cursor)
        }
        val result = repository.postTool("/api/pull-requests/diff", payload)
        return WorkspacePatch(
            result.string("patch"),
            result.bool("truncated"),
            result.nullableString("nextCursor"),
        )
    }

    suspend fun comment(request: WorkspacePullRequest, body: String) {
        require(body.isNotBlank() && body.length <= 65_536) {
            "Write a comment of at most 65,536 characters."
        }
        repository.callTool(
            "pullRequests.comment",
            buildJsonObject {
                request.ref().forEach { (key, value) -> put(key, value) }
                put("body", body)
            },
        )
    }

    suspend fun review(request: WorkspacePullRequest, verdict: String, body: String) {
        require(verdict in setOf("comment", "approve", "request-changes")) {
            "Unsupported review verdict."
        }
        require(body.length <= 65_536) { "Reviews support at most 65,536 characters." }
        repository.callTool(
            "pullRequests.submitReview",
            buildJsonObject {
                request.ref().forEach { (key, value) -> put(key, value) }
                put("verdict", verdict)
                put("body", body)
                put("comments", JsonArray(emptyList()))
            },
        )
    }

    private suspend fun call(method: String, input: JsonObject) =
        repository.callTool(method, input).jsonObject
}

internal fun WorkspacePullRequest.ref() = buildJsonObject {
    put("projectId", projectId)
    put("repository", repository)
    put("number", number)
    if (host.isNotEmpty()) put("host", host)
}

internal fun parseWorkspaceDirectory(value: JsonObject) =
    WorkspaceDirectory(
        value
            .array("entries")
            .map { raw ->
                raw.jsonObject.let {
                    WorkspaceEntry(
                        it.string("path"),
                        it.string("kind") == "directory",
                        it.bool("ignored"),
                    )
                }
            }
            .sortedWith(
                compareBy<WorkspaceEntry> { !it.directory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.path }
            ),
        value.bool("truncated"),
    )

internal fun parseWorkspaceGitStatus(value: JsonObject) =
    WorkspaceGitStatus(
        value.bool("isRepo"),
        value.nullableString("refName"),
        value.obj("workingTree").nullableString("rootPath"),
        value.obj("workingTree").array("files").map { raw ->
            raw.jsonObject.let {
                WorkspaceChange(
                    it.string("path"),
                    it.long("insertions").toInt(),
                    it.long("deletions").toInt(),
                )
            }
        },
        value.long("aheadCount").toInt(),
        value.long("behindCount").toInt(),
        value.bool("hasPrimaryRemote"),
    )

internal fun parseWorkspacePullRequest(value: JsonObject) =
    WorkspacePullRequest(
        value.string("projectId"),
        value.string("repository"),
        value.string("host"),
        value.long("number").toInt(),
        value.string("title"),
        value.string("state"),
        value.obj("author").string("login"),
        value.string("headBranch"),
        value.string("url"),
    )

internal fun parseWorkspacePullRequestDetail(
    request: WorkspacePullRequest,
    detail: JsonObject,
    activity: JsonObject,
) =
    WorkspacePullRequestDetail(
        request.copy(
            title = detail.string("title", request.title),
            state = detail.string("state", request.state),
        ),
        detail.string("body"),
        detail.string("baseBranch"),
        detail.long("additions").toInt(),
        detail.long("deletions").toInt(),
        activity.array("comments").map { raw ->
            raw.jsonObject.let {
                WorkspaceComment(
                    it.string("id"),
                    it.obj("author").string("login"),
                    it.string("body"),
                )
            }
        },
        activity.bool("commentsTruncated"),
        detail.obj("capabilities").bool("comment") &&
            detail.obj("viewerPermissions").bool("comment"),
        detail
            .obj("capabilities")
            .obj("review")
            .array("verdicts")
            .mapNotNull { (it as? JsonPrimitive)?.content }
            .toSet()
            .intersect(
                detail
                    .obj("viewerPermissions")
                    .array("verdicts")
                    .mapNotNull { (it as? JsonPrimitive)?.content }
                    .toSet()
            ),
        detail.obj("capabilities").bool("diff"),
        detail.array("checks").map { raw ->
            raw.jsonObject.let { it.string("name") to it.string("state", it.string("status")) }
        },
        detail,
        activity,
    )
