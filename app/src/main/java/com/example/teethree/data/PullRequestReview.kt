package com.example.teethree.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.*

data class PullRequestPermissions(
    val actions: Set<String>,
    val mergeMethods: Set<String>,
    val updateMethods: Set<String>,
    val inlineComment: Boolean,
    val rangeComment: Boolean,
    val reply: Boolean,
    val resolve: Boolean,
    val edit: Boolean,
    val editComment: Boolean,
    val reviewers: Boolean,
    val labels: Boolean,
    val reactions: Boolean,
    val viewedFiles: Boolean,
    val verdicts: Set<String> = emptySet(),
    val stackRebase: Boolean = false,
    val maintainerEdits: Boolean = false,
)

internal fun pullRequestPermissions(detail: JsonObject): PullRequestPermissions {
    val capabilities = detail.obj("capabilities")
    val viewer = detail.obj("viewerPermissions")
    fun values(value: JsonObject, key: String) =
        value.array(key).mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    val mergeMethods =
        values(capabilities, "mergeMethods")
            .filter { detail.obj("mergeCapabilities").bool(it) }
            .toSet()
    val updateMethods =
        values(capabilities, "updateMethods").intersect(values(viewer, "updateMethods"))
    val open = detail.string("state") == "open"
    val actions =
        values(capabilities, "actions")
            .intersect(values(viewer, "actions"))
            .filter { action ->
                when (action) {
                    "reopen" -> detail.string("state") == "closed"
                    "revert" -> detail.string("state") == "merged"
                    "delete-branch" -> !open && capabilities.bool("deleteBranch")
                    else ->
                        open &&
                            when (action) {
                                "merge" ->
                                    !detail.bool("isDraft") &&
                                        detail.string("mergeability") != "conflicting" &&
                                        mergeMethods.isNotEmpty()
                                "enable-auto-merge" ->
                                    !detail.bool("isDraft") &&
                                        detail["autoMergeEnabled"] == JsonPrimitive(false) &&
                                        mergeMethods.isNotEmpty()
                                "disable-auto-merge" -> detail.bool("autoMergeEnabled")
                                "update-branch" -> updateMethods.isNotEmpty()
                                "approve-workflows" -> detail.long("workflowApprovalsRequired") > 0
                                "ready" -> detail.bool("isDraft")
                                "draft" -> !detail.bool("isDraft")
                                else -> true
                            }
                }
            }
            .toSet()
    val verdicts =
        if (open)
            values(capabilities.obj("review"), "verdicts").intersect(values(viewer, "verdicts"))
        else emptySet()
    val inline =
        open &&
            capabilities.obj("review").bool("inlineComment") &&
            viewer.bool("comment") &&
            verdicts.isNotEmpty()
    return PullRequestPermissions(
        actions,
        mergeMethods,
        updateMethods,
        inline,
        inline && capabilities.obj("review").bool("rangeComment"),
        capabilities.obj("review").bool("reply") && viewer.bool("comment"),
        capabilities.obj("review").bool("resolve") && viewer.bool("resolve"),
        capabilities.obj("edit").bool("changeRequest") &&
            (if (viewer.containsKey("editChangeRequest")) viewer.bool("editChangeRequest")
            else
                sameReviewLogin(
                    detail.nullableString("viewer"),
                    detail.obj("author").nullableString("login"),
                ) || "merge" in values(viewer, "actions")),
        capabilities.obj("edit").bool("comment"),
        capabilities.obj("reviewers").bool("request") && viewer.bool("requestReviewers"),
        capabilities.bool("labels") && (!viewer.containsKey("labels") || viewer.bool("labels")),
        capabilities.bool("reactions"),
        capabilities.nullableString("viewedFiles") != null,
        verdicts,
        open &&
            capabilities.bool("stackActions") &&
            viewer.bool("stackRebase") &&
            "update-branch" in values(capabilities, "actions") &&
            "rebase" in values(capabilities, "updateMethods"),
        open &&
            capabilities.obj("edit").bool("maintainerEdits") &&
            viewer.bool("maintainerEdits") &&
            detail["maintainerCanModify"] is JsonPrimitive &&
            detail["maintainerCanModify"] != JsonNull,
    )
}

private fun sameReviewLogin(one: String?, two: String?) =
    !one.isNullOrBlank() && !two.isNullOrBlank() && one.trim().equals(two.trim(), ignoreCase = true)

internal fun canEditReviewComment(detail: JsonObject, comment: JsonObject): Boolean {
    if (
        !detail.obj("capabilities").obj("edit").bool("comment") ||
            comment.string("kind") !in setOf("issue-comment", "review-comment")
    )
        return false
    return if (comment.containsKey("canEdit")) comment.bool("canEdit")
    else
        sameReviewLogin(
            detail.nullableString("viewer"),
            comment.obj("author").nullableString("login"),
        )
}

data class ReviewDiffLine(
    val text: String,
    val oldLine: Int?,
    val newLine: Int?,
    val kind: String?,
) {
    fun position(side: String = "right"): JsonObject {
        require(kind != null) { "Choose a line inside a change hunk." }
        return buildJsonObject {
            put("kind", kind)
            oldLine?.let { put("oldLine", it) }
            newLine?.let { put("newLine", it) }
            if (kind == "context") put("side", side)
        }
    }
}

data class ReviewDiffFile(
    val path: String,
    val oldPath: String,
    val lines: List<ReviewDiffLine>,
    val commentable: Boolean = true,
)

internal fun parseReviewDiff(patch: String): List<ReviewDiffFile> {
    val files = mutableListOf<ReviewDiffFile>()
    var path = ""
    var oldPath = ""
    var lines = mutableListOf<ReviewDiffLine>()
    var oldLine: Int? = null
    var newLine: Int? = null
    var commentable = true
    fun filename(value: String): String =
        decodedReviewGitPath(value) ?: value.also { commentable = false }
    fun save() {
        if (path.isNotEmpty())
            files += ReviewDiffFile(path, oldPath.ifEmpty { path }, lines.toList(), commentable)
    }
    val hunk = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*")
    patch.lineSequence().forEach { line ->
        when {
            line.startsWith("diff --git ") -> {
                save()
                path = ""
                oldPath = ""
                lines = mutableListOf()
                oldLine = null
                newLine = null
                commentable = true
                gitHeaderPaths(line.removePrefix("diff --git "))?.let { (old, new) ->
                    oldPath = filename(old).removePrefix("a/")
                    path = filename(new).removePrefix("b/")
                }
            }
            line.startsWith("rename from ") && oldLine == null ->
                oldPath = filename(line.removePrefix("rename from "))
            line.startsWith("rename to ") && newLine == null ->
                path = filename(line.removePrefix("rename to "))
            line.startsWith("--- ") && oldLine == null ->
                oldPath = filename(line.substring(4)).removePrefix("a/")
            line.startsWith("+++ ") && newLine == null -> {
                path = filename(line.substring(4)).removePrefix("b/")
                if (path == "/dev/null") path = oldPath
            }
            hunk.matches(line) -> {
                val match = hunk.matchEntire(line)!!
                oldLine = match.groupValues[1].toIntOrNull()
                newLine = match.groupValues[2].toIntOrNull()
                lines += ReviewDiffLine(line, null, null, null)
            }
            oldLine != null && newLine != null && line.startsWith("+") -> {
                lines += ReviewDiffLine(line, null, newLine, "added")
                newLine = newLine!! + 1
            }
            oldLine != null && newLine != null && line.startsWith("-") -> {
                lines += ReviewDiffLine(line, oldLine, null, "deleted")
                oldLine = oldLine!! + 1
            }
            oldLine != null && newLine != null && line.startsWith(" ") -> {
                lines += ReviewDiffLine(line, oldLine, newLine, "context")
                oldLine = oldLine!! + 1
                newLine = newLine!! + 1
            }
            else -> lines += ReviewDiffLine(line, null, null, null)
        }
    }
    save()
    return files
        .groupBy { it.path }
        .values
        .map { chunks ->
            chunks
                .first()
                .copy(
                    lines = chunks.flatMap { it.lines },
                    commentable = chunks.all { it.commentable },
                )
        }
}

private fun quotedReviewGitPath(value: String): Pair<String, Int>? = runCatching {
    require(value.startsWith('"'))
    val bytes = ByteArrayOutputStream()
    var index = 1
    while (index < value.length && value[index] != '"') {
        if (value[index] != '\\') {
            val point = value.codePointAt(index)
            bytes.write(String(Character.toChars(point)).toByteArray(Charsets.UTF_8))
            index += Character.charCount(point)
        } else {
            index += 1
            require(index < value.length)
            val code = value[index++]
            if (code in '0'..'7') {
                var number = code - '0'
                var digits = 1
                while (digits < 3 && index < value.length && value[index] in '0'..'7') {
                    number = number * 8 + (value[index++] - '0')
                    digits += 1
                }
                require(number <= 255)
                bytes.write(number)
            } else {
                bytes.write(
                    when (code) {
                        'a' -> 7
                        'b' -> 8
                        't' -> 9
                        'n' -> 10
                        'v' -> 11
                        'f' -> 12
                        'r' -> 13
                        '\\' -> 92
                        '"' -> 34
                        else -> error("Invalid Git path escape")
                    }
                )
            }
        }
    }
    require(index < value.length && value[index] == '"')
    val decoded =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    require(!decoded.contains('\u0000'))
    decoded to index + 1
}
    .getOrNull()

private fun decodedReviewGitPath(value: String): String? =
    if (value.startsWith('"'))
        quotedReviewGitPath(value)
            ?.takeIf { (_, end) -> end == value.length || value[end] == '\t' }
            ?.first
    else value.substringBefore('\t').takeUnless { it.contains('\u0000') }

internal fun decodeReviewGitPath(value: String): String = decodedReviewGitPath(value) ?: value

private fun gitHeaderPaths(value: String): Pair<String, String>? {
    if (value.startsWith('"')) {
        val first = quotedReviewGitPath(value) ?: return null
        val next = value.substring(first.second).trimStart()
        return value.substring(0, first.second) to next
    }
    val boundaries = Regex(" b/").findAll(value).toList()
    val pairs =
        boundaries
            .map { it.range.first }
            .map { index -> value.substring(0, index) to value.substring(index + 1) }
    return pairs.firstOrNull { (old, new) -> old.removePrefix("a/") == new.removePrefix("b/") }
        ?: pairs.firstOrNull()
}

internal fun inlineReviewDraft(
    file: ReviewDiffFile,
    line: ReviewDiffLine,
    body: String,
    side: String = "right",
    startLine: ReviewDiffLine? = null,
): JsonObject {
    require(body.isNotBlank() && body.length <= 65_536) {
        "Write a comment of at most 65,536 characters."
    }
    require(file.commentable && file.path.isNotBlank() && !file.path.contains('\u0000')) {
        "This file path cannot be commented on from the diff."
    }
    require(side in setOf("left", "right"))
    val end = if (side == "left") line.oldLine else line.newLine
    require(end != null && end > 0) { "Choose a line on the selected side of the diff." }
    if (startLine != null) {
        val start = if (side == "left") startLine.oldLine else startLine.newLine
        require(start != null && start > 0 && start <= end) {
            "Choose a valid range on one side of the diff."
        }
    }
    return buildJsonObject {
        put("path", file.path)
        if (file.oldPath != "/dev/null" && file.oldPath != file.path) put("oldPath", file.oldPath)
        put("position", line.position(side))
        startLine?.let { put("startPosition", it.position(side)) }
        put("body", body)
    }
}

class PullRequestReviewTools(private val repository: T3Repository) {
    suspend fun read(request: WorkspacePullRequest, method: String): JsonObject =
        call(request, method)

    suspend fun freshDetail(request: WorkspacePullRequest): JsonObject =
        call(request, "pullRequests.detail", buildJsonObject { put("allowStale", false) })

    suspend fun mutate(
        request: WorkspacePullRequest,
        method: String,
        values: JsonObject,
    ): JsonElement =
        repository.callTool(
            method,
            buildJsonObject {
                request.ref().forEach { (k, v) -> put(k, v) }
                values.forEach { (k, v) -> put(k, v) }
            },
        )

    suspend fun action(
        request: WorkspacePullRequest,
        action: String,
        mergeMethod: String?,
        updateMethod: String?,
        stack: JsonObject? = null,
    ): JsonElement {
        val detail =
            call(request, "pullRequests.detail", buildJsonObject { put("allowStale", false) })
        if (action == "merge" && detail.obj("capabilities").bool("stackActions")) {
            val currentStack = call(request, "pullRequests.stack")
            require(
                currentStack.isEmpty() || stack?.long("number") == currentStack.long("number")
            ) {
                "Refresh this stack and merge its open layers together."
            }
            require(stack == null || currentStack.isNotEmpty()) {
                "This pull request is no longer in the stack. Refresh it."
            }
        }
        return mutate(
            request,
            "pullRequests.runAction",
            pullRequestActionInput(detail, action, mergeMethod, updateMethod, stack),
        )
    }

    private suspend fun call(
        request: WorkspacePullRequest,
        method: String,
        extra: JsonObject = JsonObject(emptyMap()),
    ) = (mutate(request, method, extra) as? JsonObject) ?: JsonObject(emptyMap())
}

internal fun pullRequestActionInput(
    detail: JsonObject,
    action: String,
    mergeMethod: String?,
    updateMethod: String?,
    stack: JsonObject?,
): JsonObject {
    val permissions = pullRequestPermissions(detail)
    val stackRebase = stack != null && action == "update-branch" && updateMethod == "rebase"
    require(action in permissions.actions || (stackRebase && permissions.stackRebase)) {
        "This action is no longer available for this pull request."
    }
    if (action == "merge" || action == "enable-auto-merge") {
        require(detail.string("state") == "open" && !detail.bool("isDraft")) {
            "Only an open, ready pull request can be merged."
        }
        require(mergeMethod in permissions.mergeMethods) { "Choose an available merge method." }
    }
    if (action == "update-branch") {
        require(
            if (stack != null) stackRebase && permissions.stackRebase
            else updateMethod in permissions.updateMethods
        ) {
            "Choose an available update method. Stack updates require permission to rebase the stack."
        }
    }
    val heads = stack?.let { observed ->
        require(
            detail.obj("capabilities").bool("stackActions") &&
                action in setOf("merge", "update-branch") &&
                observed.long("number") > 0
        ) {
            "This action cannot be applied to a stack."
        }
        val layers = observed.array("layers").map { it.jsonObject }
        val position = layers.indexOfFirst { it.long("number") == detail.long("number") }
        require(position >= 0) { "This pull request is no longer in the stack. Refresh it." }
        require(action != "update-branch" || position == layers.lastIndex) {
            "Open the top layer to rebase this stack."
        }
        val affected =
            (if (action == "merge") layers.take(position + 1) else layers).filter {
                it.string("state") != "merged"
            }
        require(
            layers[position].string("state") == "open" &&
                affected.isNotEmpty() &&
                affected.all { it.string("state") == "open" }
        ) {
            "Reopen the closed layers before applying this stack action."
        }
        require(action != "merge" || affected.none { it.bool("isDraft") }) {
            "Mark every layer being merged ready for review."
        }
        affected.map { layer ->
            require(layer.long("number") > 0 && !layer.nullableString("headSha").isNullOrBlank()) {
                "Refresh the stack to load its latest revisions."
            }
            buildJsonObject {
                put("number", layer.long("number"))
                put("headSha", layer.string("headSha"))
            }
        }
    }
    return buildJsonObject {
        put("action", action)
        if (action in setOf("merge", "enable-auto-merge")) put("mergeMethod", mergeMethod)
        if (action == "update-branch") put("updateMethod", updateMethod)
        if (stack != null) {
            put("stackNumber", stack.long("number"))
            put("expectedStackHeads", JsonArray(heads.orEmpty()))
        }
    }
}

internal fun pendingReviewDraft(draft: JsonObject, headSha: String?): JsonObject {
    require(!headSha.isNullOrBlank()) { "Refresh the pull request before adding line comments." }
    return JsonObject(draft + ("headSha" to JsonPrimitive(headSha)))
}

internal fun pullRequestReviewInput(
    detail: JsonObject,
    verdict: String,
    body: String,
    drafts: List<JsonObject>,
): JsonObject {
    val permissions = pullRequestPermissions(detail)
    require(verdict in permissions.verdicts) {
        "This review is no longer available for this pull request."
    }
    require(body.length <= 65_536) { "Write a review of at most 65,536 characters." }
    require(
        drafts.isEmpty() ||
            (permissions.inlineComment &&
                drafts.all {
                    it.nullableString("headSha") != null &&
                        it.nullableString("headSha") == detail.nullableString("headSha")
                })
    ) {
        "These line comments belong to an older revision. Remove them and review the latest changes."
    }
    require(permissions.rangeComment || drafts.none { it["startPosition"] != null }) {
        "This host no longer supports line ranges."
    }
    require(
        (detail.string("provider") != "forgejo" ||
            verdict != "request-changes" ||
            body.isNotBlank()) && (verdict == "approve" || body.isNotBlank() || drafts.isNotEmpty())
    ) {
        "Write a review summary before submitting."
    }
    return buildJsonObject {
        put("verdict", verdict)
        put("body", body)
        put(
            "comments",
            JsonArray(
                drafts.map { draft ->
                    JsonObject(
                        draft.filterKeys {
                            it in setOf("path", "oldPath", "position", "startPosition", "body")
                        }
                    )
                }
            ),
        )
    }
}

private val generatedReviewFiles =
    setOf(
        "bun.lock",
        "bun.lockb",
        "cargo.lock",
        "composer.lock",
        "deno.lock",
        "flake.lock",
        "gemfile.lock",
        "go.sum",
        "gradle.lockfile",
        "mix.lock",
        "npm-shrinkwrap.json",
        "package-lock.json",
        "packages.lock.json",
        "pipfile.lock",
        "pnpm-lock.yaml",
        "podfile.lock",
        "poetry.lock",
        "pubspec.lock",
        "uv.lock",
        "yarn.lock",
    )
private val generatedReviewPattern =
    Regex(
        "(\\.generated\\.|\\.gen\\.|\\.g\\.dart$|\\.pb\\.go$|_pb2(_grpc)?\\.py$|\\.pb\\.(cc|h)$|\\.min\\.(js|css)$|\\.(js|css)\\.map$)"
    )
private val testReviewPattern =
    Regex(
        "(\\.(test|spec|e2e|eval)\\.[^.]+$|_(test|spec)\\.[^.]+$|^test_.+\\.py$|^conftest\\.py$|^(vitest|jest)\\.setup\\.[^.]+$)",
        RegexOption.IGNORE_CASE,
    )
private val mockReviewPattern =
    Regex(
        "(\\.snap$|\\.(mocks?|fixtures?|stubs?|fakes?)\\.[^.]+$|^(mocks?|fixtures?|stubs?|fakes?)([-_.A-Z]|$))"
    )
private val testReviewClass = Regex("(Tests?|Spec)\\.(java|kt|kts|scala|groovy|cs|php|swift|m)$")

internal fun isReviewTestFile(path: String): Boolean {
    val parts = path.replace('\\', '/').split('/')
    val name = parts.lastOrNull().orEmpty()
    val directories = parts.dropLast(1).map { it.lowercase() }
    if (
        name.lowercase() in generatedReviewFiles ||
            generatedReviewPattern.containsMatchIn(name) ||
            directories.any { it in setOf("__generated__", "_generated", "generated") }
    )
        return false
    return testReviewPattern.containsMatchIn(name) ||
        mockReviewPattern.containsMatchIn(name) ||
        testReviewClass.containsMatchIn(name) ||
        directories.any {
            it in
                setOf(
                    "__tests__",
                    "__test__",
                    "cypress",
                    "e2e",
                    "evals",
                    "spec",
                    "specs",
                    "test",
                    "tests",
                    "__fixtures__",
                    "__mocks__",
                    "__snapshots__",
                    "fakes",
                    "fixture",
                    "fixtures",
                    "mock",
                    "mocks",
                    "stubs",
                    "testdata",
                )
        }
}
