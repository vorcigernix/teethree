package com.example.teethree.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.ConnectionStore
import com.example.teethree.data.*
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PullRequestScreen(
    repository: T3Repository,
    initial: WorkspacePullRequestDetail,
    onBack: () -> Unit,
) {
    val appearance = LocalClientAppearance.current
    val state by repository.state.collectAsStateWithLifecycle()
    val connectionId = repository.connectionId
    val connectionGeneration = repository.connectionGeneration
    val key = "$connectionId:${initial.request.ref()}"
    val tools = remember(repository) { PullRequestReviewTools(repository) }
    val workspace = remember(repository) { WorkspaceTools(repository) }
    val scope = rememberCoroutineScope()
    var detail by remember(key) { mutableStateOf(initial) }
    val request = detail.request
    val permissions = remember(detail.rawDetail) { pullRequestPermissions(detail.rawDetail) }
    val canWrite =
        state.connectionStatus == ConnectionStatus.Connected &&
            "source-control:write" in state.scopes
    var tab by rememberSaveable(key) { mutableIntStateOf(0) }
    var busy by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var notice by remember(key) { mutableStateOf<String?>(null) }
    var patch by remember(key) { mutableStateOf<WorkspacePatch?>(null) }
    var patchHeadSha by remember(key) { mutableStateOf<String?>(null) }
    val files = remember(patch?.text) { patch?.let { parseReviewDiff(it.text) }.orEmpty() }
    var fileQuery by rememberSaveable(key) { mutableStateOf("") }
    var hideViewed by rememberSaveable(key) { mutableStateOf(false) }
    var hideTests by rememberSaveable(key) { mutableStateOf(false) }
    var viewed by remember(key) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var stack by remember(key) { mutableStateOf<JsonObject?>(null) }
    var dialog by remember(key) { mutableStateOf<String?>(null) }
    var form by rememberSaveable(key) { mutableStateOf("") }
    var title by rememberSaveable(key) { mutableStateOf("") }
    var target by remember(key) { mutableStateOf<JsonObject?>(null) }
    var candidates by remember(key) { mutableStateOf<JsonObject?>(null) }
    var mergeMethod by remember(key) { mutableStateOf<String?>(null) }
    var updateMethod by remember(key) { mutableStateOf<String?>(null) }
    var applyStack by remember(key) { mutableStateOf(false) }
    var drafts by remember(key) { mutableStateOf<List<String>>(emptyList()) }
    var reviewBody by remember(key) { mutableStateOf("") }
    var commentBody by remember(key) { mutableStateOf("") }
    var draftsLoaded by remember(key) { mutableStateOf(false) }
    var inline by remember(key) { mutableStateOf<Pair<ReviewDiffFile, ReviewDiffLine>?>(null) }
    var inlineHeadSha by remember(key) { mutableStateOf<String?>(null) }
    var rangeStart by remember(key) { mutableStateOf<ReviewDiffLine?>(null) }
    var side by remember(key) { mutableStateOf("right") }
    var guide by remember(key) { mutableStateOf<JsonObject?>(null) }
    val context = LocalContext.current.applicationContext
    val draftStore =
        remember(key, connectionGeneration) {
            connectionId?.let { captured ->
                PullRequestDraftPersistence(
                    ConnectionStore(context),
                    captured,
                    initial.request,
                    {
                        repository.connectionId == captured &&
                            repository.connectionGeneration == connectionGeneration
                    },
                    { error = it },
                )
            }
        }
    fun savedDraft() = buildJsonObject {
        put("summary", reviewBody)
        put("comment", commentBody)
        put("comments", JsonArray(drafts.map(Json::parseToJsonElement)))
    }
    LaunchedEffect(draftStore) {
        if (draftsLoaded) return@LaunchedEffect
        try {
            val saved = draftStore?.load()
            reviewBody = saved?.string("summary").orEmpty()
            commentBody = saved?.string("comment").orEmpty()
            drafts =
                saved?.array("comments")?.mapNotNull { (it as? JsonObject)?.toString() }.orEmpty()
            draftsLoaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
            draftsLoaded = true
        }
    }
    LaunchedEffect(draftStore, draftsLoaded, drafts, reviewBody, commentBody) {
        if (draftsLoaded) draftStore?.save(savedDraft())
    }
    DisposableEffect(draftStore) {
        onDispose {
            if (draftsLoaded) draftStore?.save(savedDraft())
            draftStore?.close()
        }
    }
    suspend fun reload() {
        val updated = workspace.pullRequestDetail(request)
        detail =
            parseWorkspacePullRequestDetail(
                request,
                tools.freshDetail(request),
                updated.rawActivity,
            )
        if (patchHeadSha != detail.rawDetail.nullableString("headSha")) {
            patch = null
            patchHeadSha = null
        }
    }
    suspend fun loadPatch(cursor: String? = null) {
        val before = tools.freshDetail(request)
        val head = before.nullableString("headSha")
        if (cursor != null && head != patchHeadSha) {
            patch = null
            patchHeadSha = null
            throw IllegalStateException(
                "This pull request changed. Refresh the diff before continuing."
            )
        }
        val next = workspace.pullRequestDiff(request, cursor)
        val after = tools.freshDetail(request)
        detail = parseWorkspacePullRequestDetail(request, after, detail.rawActivity)
        require(head == after.nullableString("headSha")) {
            "This pull request changed while loading. Refresh the diff."
        }
        patch = if (cursor == null) next else next.copy(text = patch!!.text + "\n" + next.text)
        patchHeadSha = head
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "This request could not be completed."
            } finally {
                busy = false
            }
        }
    }
    fun edit(kind: String, value: JsonObject? = null) {
        target = value
        form = value?.string("body") ?: if (kind == "comment") commentBody else ""
        dialog = kind
    }
    fun mutate(method: String, values: JsonObject) = perform {
        tools.mutate(request, method, values)
        if (dialog == "comment" && commentBody == form) commentBody = ""
        dialog = null
        form = ""
        notice = "Saved"
        reload()
    }
    BackHandler { if (!busy) onBack() }
    LaunchedEffect(key, tab, patch == null) {
        try {
            if (tab == 1) {
                if (patch == null) loadPatch()
                if (permissions.viewedFiles)
                    viewed =
                        tools.read(request, "pullRequests.filesViewed").array("files").associate {
                            it.jsonObject.let { file ->
                                file.string("path") to (file.string("state") == "viewed")
                            }
                        }
            }
            if (tab == 0 && detail.rawDetail.obj("capabilities").bool("stacks"))
                stack = tools.read(request, "pullRequests.stack").takeIf { it.isNotEmpty() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message
        }
    }
    LaunchedEffect(key, tab, state.connectionStatus, guide?.obj("run")?.string("_tag")) {
        if (
            tab == 0 &&
                state.connectionStatus == ConnectionStatus.Connected &&
                guide?.obj("run")?.string("_tag") == "Running"
        ) {
            try {
                while (guide?.obj("run")?.string("_tag") == "Running") {
                    delay(5_000)
                    guide = tools.read(request, "pullRequests.reviewGuide")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "The review guide could not be refreshed."
            }
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "#${request.number} ${request.title}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            perform {
                                reload()
                                if (tab == 1) loadPatch()
                                if (detail.rawDetail.obj("capabilities").bool("stacks"))
                                    stack =
                                        tools.read(request, "pullRequests.stack").takeIf {
                                            it.isNotEmpty()
                                        }
                                if (guide != null)
                                    guide = tools.read(request, "pullRequests.reviewGuide")
                            }
                        },
                        enabled = !busy && state.connectionStatus == ConnectionStatus.Connected,
                    ) {
                        Icon(Icons.Outlined.Refresh, "Refresh pull request")
                    }
                },
            )
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).widthIn(max = 1100.dp)) {
            PrimaryTabRow(tab) {
                listOf("Overview", "Changes", "Discussion").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            notice?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (tab) {
                    0 -> {
                        item {
                            Text(request.title, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "${request.state} · ${request.author} · ${request.branch} → ${detail.baseBranch}"
                            )
                            Text(
                                "+${detail.additions} −${detail.deletions}",
                                color = MaterialTheme.colorScheme.primary,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (permissions.edit)
                                    TextButton(
                                        onClick = {
                                            title = request.title
                                            form = detail.body
                                            dialog = "edit"
                                        },
                                        enabled = canWrite && !busy,
                                    ) {
                                        Text("Edit")
                                    }
                                (permissions.actions +
                                        if (permissions.stackRebase) setOf("update-branch")
                                        else emptySet())
                                    .forEach { action ->
                                        TextButton(
                                            onClick = {
                                                perform {
                                                    detail =
                                                        parseWorkspacePullRequestDetail(
                                                            request,
                                                            tools.freshDetail(request),
                                                            detail.rawActivity,
                                                        )
                                                    val currentPermissions =
                                                        pullRequestPermissions(detail.rawDetail)
                                                    if (
                                                        detail.rawDetail
                                                            .obj("capabilities")
                                                            .bool("stackActions") &&
                                                            action in
                                                                setOf("merge", "update-branch")
                                                    ) {
                                                        stack =
                                                            tools
                                                                .read(request, "pullRequests.stack")
                                                                .takeIf { it.isNotEmpty() }
                                                    }
                                                    mergeMethod =
                                                        currentPermissions.mergeMethods
                                                            .firstOrNull()
                                                    val rebaseOnly =
                                                        currentPermissions.updateMethods
                                                            .isEmpty() &&
                                                            currentPermissions.stackRebase &&
                                                            stack != null
                                                    updateMethod =
                                                        if (rebaseOnly) "rebase"
                                                        else
                                                            currentPermissions.updateMethods
                                                                .firstOrNull()
                                                    applyStack =
                                                        stack != null &&
                                                            (action == "merge" || rebaseOnly)
                                                    dialog = "action:$action"
                                                }
                                            },
                                            enabled = canWrite && !busy,
                                        ) {
                                            Text(prActionLabel(action))
                                        }
                                    }
                            }
                            if (permissions.maintainerEdits)
                                Row {
                                    Checkbox(
                                        detail.rawDetail.bool("maintainerCanModify"),
                                        enabled = canWrite && !busy,
                                        onCheckedChange = { checked ->
                                            mutate(
                                                "pullRequests.update",
                                                buildJsonObject {
                                                    put("maintainerCanModify", checked)
                                                },
                                            )
                                        },
                                    )
                                    Text(
                                        "Allow maintainers to edit this branch",
                                        Modifier.padding(top = 12.dp),
                                    )
                                }
                        }
                        if (detail.body.isNotBlank()) item { MarkdownMessage(detail.body) }
                        item {
                            Text("Checks", style = MaterialTheme.typography.titleMedium)
                            if (detail.checks.isEmpty()) Text("No checks reported")
                            detail.checks.forEach { (name, status) -> Text("$name · $status") }
                        }
                        item {
                            Text("Reviewers", style = MaterialTheme.typography.titleMedium)
                            Text(
                                detail.rawDetail
                                    .array("reviewers")
                                    .joinToString { it.jsonObject.string("login") }
                                    .ifEmpty { "No reviewers requested" }
                            )
                            if (permissions.reviewers)
                                TextButton(
                                    enabled = canWrite && !busy,
                                    onClick = {
                                        perform {
                                            candidates =
                                                tools.read(
                                                    request,
                                                    "pullRequests.reviewerCandidates",
                                                )
                                            dialog = "reviewers"
                                        }
                                    },
                                ) {
                                    Text("Manage reviewers")
                                }
                            Text("Labels", style = MaterialTheme.typography.titleMedium)
                            Text(
                                detail.rawDetail
                                    .array("labels")
                                    .joinToString { it.jsonObject.string("name") }
                                    .ifEmpty { "No labels" }
                            )
                            if (permissions.labels)
                                TextButton(
                                    enabled = canWrite && !busy,
                                    onClick = {
                                        perform {
                                            candidates =
                                                tools.read(request, "pullRequests.labelCandidates")
                                            dialog = "labels"
                                        }
                                    },
                                ) {
                                    Text("Manage labels")
                                }
                        }
                        stack?.let { current ->
                            item {
                                Text(
                                    "Stack #${current.long("number")}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                current.array("layers").forEach { raw ->
                                    val layer = raw.jsonObject
                                    Text(
                                        "#${layer.long("number")} ${layer.string("title", layer.string("headBranch"))} · ${layer.string("state")}"
                                    )
                                }
                            }
                        }
                        if (detail.rawActivity.array("commits").isNotEmpty())
                            item {
                                Text("Commits", style = MaterialTheme.typography.titleMedium)
                                detail.rawActivity.array("commits").forEach { raw ->
                                    val commit = raw.jsonObject
                                    Text(
                                        "${commit.string("oid").take(8)} ${commit.string("messageHeadline")}"
                                    )
                                }
                            }
                        if (
                            state.serverConfig?.obj("settings")?.bool("reviewGuidesEnabled") == true
                        )
                            item {
                                TextButton(
                                    onClick = {
                                        perform {
                                            guide = tools.read(request, "pullRequests.reviewGuide")
                                        }
                                    },
                                    enabled =
                                        !busy &&
                                            state.connectionStatus == ConnectionStatus.Connected,
                                ) {
                                    Text("Open review guide")
                                }
                                val storedGuide = guide?.obj("guide")
                                val guideRun = guide?.obj("run")
                                val currentHead = guide?.nullableString("currentHeadSha")
                                val outdated =
                                    storedGuide?.nullableString("headSha") != null &&
                                        storedGuide.nullableString("headSha") != currentHead
                                if (outdated)
                                    Text(
                                        "This guide describes an older revision. Generate an update before relying on it.",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                if (guideRun?.string("_tag") == "Running")
                                    Text(
                                        "A review guide is being generated. Refresh to check its progress."
                                    )
                                if (
                                    guideRun?.string("_tag") == "Failed" &&
                                        guideRun.nullableString("headSha") == currentHead
                                )
                                    Text(
                                        guideRun.string("detail"),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                if (guide != null)
                                    TextButton(
                                        enabled =
                                            !busy &&
                                                state.connectionStatus ==
                                                    ConnectionStatus.Connected &&
                                                "orchestration:operate" in state.scopes &&
                                                guideRun?.string("_tag") != "Running",
                                        onClick = {
                                            perform {
                                                guide =
                                                    tools
                                                        .mutate(
                                                            request,
                                                            "pullRequests.generateReviewGuide",
                                                            JsonObject(emptyMap()),
                                                        )
                                                        .jsonObject
                                            }
                                        },
                                    ) {
                                        Text(
                                            if (storedGuide.isNullOrEmpty()) "Generate review guide"
                                            else "Regenerate review guide"
                                        )
                                    }
                                storedGuide?.array("stops")?.forEach { raw ->
                                    val stop = raw.jsonObject
                                    Text(
                                        stop.string("title"),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    MarkdownMessage(stop.string("summary"))
                                    stop["attention"]
                                        ?.let { it as? JsonObject }
                                        ?.let { attention ->
                                            Text(
                                                "${attention.string("kind")}: ${attention.string("note")}",
                                                color = MaterialTheme.colorScheme.tertiary,
                                            )
                                        }
                                    stop.array("files").forEach { rawFile ->
                                        val file = rawFile.jsonObject
                                        Text(
                                            "${file.string("path")}${file.nullableString("note")?.let { ": $it" }.orEmpty()}",
                                            fontFamily = T3MonoFontFamily,
                                        )
                                    }
                                    stop.array("excerpts").forEach { rawExcerpt ->
                                        val excerpt = rawExcerpt.jsonObject
                                        TextButton(
                                            onClick = {
                                                fileQuery = excerpt.string("path")
                                                tab = 1
                                            }
                                        ) {
                                            Text(
                                                "${excerpt.string("path")}:${excerpt.long("start")}–${excerpt.long("end")} (${if (excerpt.string("side") == "old") "original" else "updated"})",
                                                fontFamily = T3MonoFontFamily,
                                            )
                                        }
                                        MarkdownMessage(excerpt.string("caption"))
                                    }
                                    stop.array("notes").forEach { note ->
                                        val annotated = note.jsonObject
                                        Text(
                                            "${annotated.string("path")}:${annotated.long("line")} · ${annotated.string("title")}",
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        MarkdownMessage(annotated.string("body"))
                                    }
                                    stop.array("questions").forEach { question ->
                                        Text("• ${question.jsonPrimitive.content}")
                                    }
                                }
                            }
                    }
                    1 -> {
                        item {
                            OutlinedTextField(
                                fileQuery,
                                { fileQuery = it },
                                label = { Text("Filter files") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    hideViewed,
                                    { hideViewed = !hideViewed },
                                    label = { Text("Hide viewed") },
                                    enabled = permissions.viewedFiles,
                                )
                                FilterChip(
                                    hideTests,
                                    { hideTests = !hideTests },
                                    label = { Text("Hide tests and fixtures") },
                                )
                            }
                            Text(
                                "${files.count { viewed[it.path] == true }} of ${files.size} loaded files viewed${if (patch?.nextCursor != null) " · more files available" else ""}"
                            )
                        }
                        if (patch == null) item { Text("Loading changes…") }
                        val visibleFiles = files.filter { file ->
                            file.path.contains(fileQuery, ignoreCase = true) &&
                                (!hideViewed || viewed[file.path] != true) &&
                                (!hideTests || !isReviewTestFile(file.path))
                        }
                        visibleFiles.forEach { file ->
                            item(key = "file:${file.path}") {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        file.path,
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (permissions.viewedFiles)
                                        Checkbox(
                                            checked = viewed[file.path] == true,
                                            enabled = canWrite && !busy,
                                            onCheckedChange = { checked ->
                                                perform {
                                                    tools.mutate(
                                                        request,
                                                        "pullRequests.setFilesViewed",
                                                        buildJsonObject {
                                                            put(
                                                                "files",
                                                                buildJsonArray {
                                                                    add(
                                                                        buildJsonObject {
                                                                            put("path", file.path)
                                                                            put("viewed", checked)
                                                                        }
                                                                    )
                                                                },
                                                            )
                                                        },
                                                    )
                                                    viewed = viewed + (file.path to checked)
                                                }
                                            },
                                        )
                                }
                            }
                            items(file.lines.indices.toList(), key = { "${file.path}:$it" }) { index
                                ->
                                val line = file.lines[index]
                                val color =
                                    when (line.kind) {
                                        "added" -> T3Colors.successContainer
                                        "deleted" -> MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.surfaceContainerLow
                                    }
                                Text(
                                    "${line.oldLine?.toString().orEmpty().padStart(4)} ${line.newLine?.toString().orEmpty().padStart(4)}  ${line.text}",
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .background(color)
                                            .clickable(
                                                enabled =
                                                    canWrite &&
                                                        permissions.inlineComment &&
                                                        file.commentable &&
                                                        !patchHeadSha.isNullOrBlank() &&
                                                        draftsLoaded &&
                                                        line.kind != null &&
                                                        !busy
                                            ) {
                                                inline = file to line
                                                inlineHeadSha = patchHeadSha
                                                rangeStart = null
                                                side =
                                                    if (line.kind == "deleted") "left" else "right"
                                                form = ""
                                                dialog = "inline"
                                            }
                                            .then(
                                                if (appearance.codeWordBreak) Modifier
                                                else
                                                    Modifier.horizontalScroll(rememberScrollState())
                                            )
                                            .padding(6.dp),
                                    fontFamily = T3MonoFontFamily,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = appearance.codeFontSize.sp,
                                    lineHeight = (appearance.codeFontSize + 6).sp,
                                    softWrap = appearance.codeWordBreak,
                                )
                            }
                        }
                        if (files.isEmpty() && patch != null)
                            item {
                                Text(
                                    patch!!.text.ifBlank { "No text diff available" },
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .then(
                                                if (appearance.codeWordBreak) Modifier
                                                else
                                                    Modifier.horizontalScroll(rememberScrollState())
                                            ),
                                    fontFamily = T3MonoFontFamily,
                                    fontSize = appearance.codeFontSize.sp,
                                    lineHeight = (appearance.codeFontSize + 6).sp,
                                    softWrap = appearance.codeWordBreak,
                                )
                            }
                        if (files.isNotEmpty() && visibleFiles.isEmpty())
                            item { Text("No files match these filters.") }
                        patch?.nextCursor?.let { cursor ->
                            item {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        perform {
                                            loadPatch(cursor)
                                        }
                                    },
                                ) {
                                    Text("Load more changes")
                                }
                            }
                        }
                        if (patch?.truncated == true && patch?.nextCursor == null)
                            item { Text("The server omitted part of this diff.") }
                        if (drafts.isNotEmpty())
                            item {
                                Text("${drafts.size} pending line comments")
                                drafts.forEachIndexed { index, raw ->
                                    val draft = Json.parseToJsonElement(raw).jsonObject
                                    Text("${draft.string("path")}: ${draft.string("body")}")
                                    if (
                                        draft.nullableString("headSha") !=
                                            detail.rawDetail.nullableString("headSha") ||
                                            draft.nullableString("headSha") == null
                                    )
                                        Text(
                                            "Outdated revision · remove and add this comment on the latest changes",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            drafts = drafts.filterIndexed { i, _ -> i != index }
                                        },
                                    ) {
                                        Text("Remove comment")
                                    }
                                }
                            }
                        if (permissions.verdicts.isNotEmpty())
                            item {
                                FilledTonalButton(
                                    enabled = canWrite && !busy && draftsLoaded,
                                    onClick = {
                                        form = reviewBody
                                        dialog = "review"
                                    },
                                ) {
                                    Text(
                                        "Submit review${if (drafts.isEmpty()) "" else " (${drafts.size})"}"
                                    )
                                }
                            }
                    }
                    2 -> {
                        if (detail.canComment)
                            item {
                                OutlinedButton(
                                    enabled = canWrite && !busy && draftsLoaded,
                                    onClick = { edit("comment") },
                                ) {
                                    Text("Add comment")
                                }
                            }
                        items(
                            detail.rawActivity.array("comments").map { it.jsonObject },
                            key = { "comment:${it.string("id")}" },
                        ) { comment ->
                            PrDiscussionComment(
                                comment,
                                permissions.reactions && canWrite && !busy,
                                canEditReviewComment(detail.rawDetail, comment) &&
                                    canWrite &&
                                    !busy,
                                onEdit = { edit("edit-comment", comment) },
                                onReaction = { content, reacted ->
                                    mutate(
                                        "pullRequests.setReaction",
                                        buildJsonObject {
                                            put("subjectId", comment.string("id"))
                                            put("content", content)
                                            put("reacted", reacted)
                                        },
                                    )
                                },
                            )
                        }
                        if (detail.commentsTruncated)
                            item { Text("The server limited this discussion preview.") }
                        items(
                            detail.rawActivity.array("reviewThreads").map { it.jsonObject },
                            key = { "thread:${it.string("id")}" },
                        ) { thread ->
                            Card {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "${thread.string("path")}:${thread.nullableString("line").orEmpty()} · ${if (thread.bool("isResolved")) "Resolved" else "Open"}",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    thread.array("comments").forEach { raw ->
                                        val comment = raw.jsonObject
                                        PrDiscussionComment(
                                            comment,
                                            permissions.reactions && canWrite && !busy,
                                            canEditReviewComment(
                                                detail.rawDetail,
                                                JsonObject(
                                                    comment +
                                                        ("kind" to JsonPrimitive("review-comment"))
                                                ),
                                            ) && canWrite && !busy,
                                            onEdit = {
                                                edit(
                                                    "edit-comment",
                                                    buildJsonObject {
                                                        comment.forEach { (k, v) -> put(k, v) }
                                                        put("kind", "review-comment")
                                                    },
                                                )
                                            },
                                            onReaction = { content, reacted ->
                                                mutate(
                                                    "pullRequests.setReaction",
                                                    buildJsonObject {
                                                        put("subjectId", comment.string("id"))
                                                        put("content", content)
                                                        put("reacted", reacted)
                                                    },
                                                )
                                            },
                                        )
                                    }
                                    FlowRow {
                                        if (permissions.reply)
                                            TextButton(
                                                enabled = canWrite && !busy,
                                                onClick = {
                                                    edit("reply", thread)
                                                    form = ""
                                                },
                                            ) {
                                                Text("Reply")
                                            }
                                        if (
                                            permissions.resolve &&
                                                (!thread.containsKey("canResolve") ||
                                                    thread.bool("canResolve"))
                                        )
                                            TextButton(
                                                enabled = canWrite && !busy,
                                                onClick = {
                                                    mutate(
                                                        "pullRequests.setThreadResolution",
                                                        buildJsonObject {
                                                            put("threadId", thread.string("id"))
                                                            put(
                                                                "resolved",
                                                                !thread.bool("isResolved"),
                                                            )
                                                        },
                                                    )
                                                },
                                            ) {
                                                Text(
                                                    if (thread.bool("isResolved")) "Reopen"
                                                    else "Resolve"
                                                )
                                            }
                                        thread.nullableString("nextCommentsCursor")?.let { cursor ->
                                            TextButton(
                                                enabled = !busy,
                                                onClick = {
                                                    perform {
                                                        val page =
                                                            tools
                                                                .mutate(
                                                                    request,
                                                                    "pullRequests.threadComments",
                                                                    buildJsonObject {
                                                                        put(
                                                                            "threadId",
                                                                            thread.string("id"),
                                                                        )
                                                                        put("cursor", cursor)
                                                                    },
                                                                )
                                                                .jsonObject
                                                        val updated = buildJsonObject {
                                                            thread.forEach { (k, v) ->
                                                                if (k != "nextCommentsCursor")
                                                                    put(k, v)
                                                            }
                                                            put(
                                                                "comments",
                                                                JsonArray(
                                                                    (thread.array("comments") +
                                                                            page.array("comments"))
                                                                        .distinctBy {
                                                                            it.jsonObject.string(
                                                                                "id"
                                                                            )
                                                                        }
                                                                ),
                                                            )
                                                            page.nullableString("nextCursor")?.let {
                                                                put("nextCommentsCursor", it)
                                                            }
                                                        }
                                                        detail =
                                                            detail.copy(
                                                                rawActivity =
                                                                    buildJsonObject {
                                                                        detail.rawActivity
                                                                            .forEach { (k, v) ->
                                                                                put(k, v)
                                                                            }
                                                                        put(
                                                                            "reviewThreads",
                                                                            JsonArray(
                                                                                detail.rawActivity
                                                                                    .array(
                                                                                        "reviewThreads"
                                                                                    )
                                                                                    .map {
                                                                                        if (
                                                                                            it
                                                                                                .jsonObject
                                                                                                .string(
                                                                                                    "id"
                                                                                                ) ==
                                                                                                thread
                                                                                                    .string(
                                                                                                        "id"
                                                                                                    )
                                                                                        )
                                                                                            updated
                                                                                        else it
                                                                                    }
                                                                            ),
                                                                        )
                                                                    }
                                                            )
                                                    }
                                                },
                                            ) {
                                                Text("Load more replies")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (detail.rawActivity.bool("reviewThreadsTruncated"))
                            item { Text("The server limited this review thread listing.") }
                    }
                }
            }
        }
    }
    val kind = dialog
    if (kind != null) {
        var verdict by
            remember(key, kind) { mutableStateOf(permissions.verdicts.firstOrNull() ?: "comment") }
        val selectedVerdict =
            verdict.takeIf { it in permissions.verdicts }
                ?: permissions.verdicts.firstOrNull().orEmpty()
        val reviewValidation =
            if (kind == "review")
                runCatching {
                    pullRequestReviewInput(
                        detail.rawDetail,
                        selectedVerdict,
                        form,
                        drafts.map { Json.parseToJsonElement(it).jsonObject },
                    )
                }
            else null
        val actionValidation =
            if (kind.startsWith("action:"))
                runCatching {
                    pullRequestActionInput(
                        detail.rawDetail,
                        kind.substringAfter(':'),
                        mergeMethod,
                        updateMethod,
                        if (applyStack) stack else null,
                    )
                }
            else null
        AlertDialog(
            onDismissRequest = { if (!busy) dialog = null },
            title = {
                Text(
                    when {
                        kind.startsWith("action:") -> prActionLabel(kind.substringAfter(':'))
                        kind == "inline" -> "Add line comment"
                        kind == "review" -> "Submit review"
                        kind == "edit" -> "Edit pull request"
                        kind == "edit-comment" -> "Edit comment"
                        kind == "reviewers" -> "Reviewers"
                        kind == "labels" -> "Labels"
                        kind == "reply" -> "Reply"
                        else -> "Add comment"
                    }
                )
            },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        kind.startsWith("action:") -> {
                            val action = kind.substringAfter(':')
                            Text(
                                "Apply this action to #${request.number}? The environment will check the current pull request state."
                            )
                            if (action in setOf("merge", "enable-auto-merge"))
                                FlowRow {
                                    permissions.mergeMethods.forEach { method ->
                                        FilterChip(
                                            selected = mergeMethod == method,
                                            onClick = { mergeMethod = method },
                                            label = { Text(method) },
                                        )
                                    }
                                }
                            if (action == "update-branch")
                                FlowRow {
                                    (if (applyStack) setOf("rebase") else permissions.updateMethods)
                                        .forEach { method ->
                                            FilterChip(
                                                selected = updateMethod == method,
                                                onClick = { updateMethod = method },
                                                label = { Text(method) },
                                            )
                                        }
                                }
                            if (
                                action == "update-branch" &&
                                    stack != null &&
                                    permissions.stackRebase &&
                                    stack
                                        ?.array("layers")
                                        ?.lastOrNull()
                                        ?.jsonObject
                                        ?.long("number") == request.number.toLong()
                            )
                                Row {
                                    Checkbox(
                                        applyStack,
                                        {
                                            applyStack = it
                                            updateMethod =
                                                if (it) "rebase"
                                                else permissions.updateMethods.firstOrNull()
                                        },
                                    )
                                    Text("Rebase the entire stack")
                                }
                            if (action == "merge" && applyStack)
                                Text(
                                    "Merges every open layer through #${request.number}. Layers above this pull request remain open."
                                )
                            actionValidation?.exceptionOrNull()?.message?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        kind in setOf("reviewers", "labels") -> {
                            candidates?.array("candidates")?.forEach { raw ->
                                val candidate = raw.jsonObject
                                val selected =
                                    candidate.bool(
                                        if (kind == "reviewers") "isRequested" else "isApplied"
                                    )
                                Row {
                                    Checkbox(
                                        selected,
                                        enabled = canWrite && !busy,
                                        onCheckedChange = { checked ->
                                            perform {
                                                tools.mutate(
                                                    request,
                                                    if (kind == "reviewers")
                                                        "pullRequests.requestReviewers"
                                                    else "pullRequests.setLabels",
                                                    buildJsonObject {
                                                        if (kind == "reviewers") {
                                                            put(
                                                                "reviewers",
                                                                buildJsonArray {
                                                                    add(
                                                                        buildJsonObject {
                                                                            put(
                                                                                "id",
                                                                                candidate.string(
                                                                                    "id"
                                                                                ),
                                                                            )
                                                                            put(
                                                                                "kind",
                                                                                candidate.string(
                                                                                    "kind"
                                                                                ),
                                                                            )
                                                                        }
                                                                    )
                                                                },
                                                            )
                                                            put("requested", checked)
                                                        } else {
                                                            put(
                                                                "labels",
                                                                buildJsonArray {
                                                                    add(candidate.string("name"))
                                                                },
                                                            )
                                                            put("applied", checked)
                                                        }
                                                    },
                                                )
                                                candidates =
                                                    tools.read(
                                                        request,
                                                        if (kind == "reviewers")
                                                            "pullRequests.reviewerCandidates"
                                                        else "pullRequests.labelCandidates",
                                                    )
                                                reload()
                                            }
                                        },
                                    )
                                    Text(
                                        candidate.string(
                                            if (kind == "reviewers") "login" else "name"
                                        )
                                    )
                                }
                            }
                            if (candidates?.bool("truncated") == true)
                                Text("The server limited this candidate list.")
                        }
                        else -> {
                            if (kind == "edit")
                                OutlinedTextField(
                                    title,
                                    { title = it },
                                    label = { Text("Title") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy,
                                )
                            if (kind == "inline") {
                                val selection = inline!!
                                Text(
                                    "${selection.first.path}:${if (side == "left") selection.second.oldLine else selection.second.newLine}"
                                )
                                if (selection.second.kind == "context")
                                    FlowRow {
                                        listOf("left", "right").forEach { option ->
                                            FilterChip(
                                                side == option,
                                                {
                                                    side = option
                                                    rangeStart = null
                                                },
                                                label = {
                                                    Text(
                                                        if (option == "left") "Original"
                                                        else "Updated"
                                                    )
                                                },
                                            )
                                        }
                                    }
                                if (permissions.rangeComment) {
                                    val end =
                                        if (side == "left") selection.second.oldLine
                                        else selection.second.newLine
                                    var expanded by remember(key) { mutableStateOf(false) }
                                    Box {
                                        TextButton(onClick = { expanded = true }) {
                                            Text(
                                                "Range starts at ${rangeStart?.let { if (side == "left") it.oldLine else it.newLine } ?: end}"
                                            )
                                        }
                                        DropdownMenu(expanded, { expanded = false }) {
                                            selection.first.lines
                                                .filter {
                                                    val number =
                                                        if (side == "left") it.oldLine
                                                        else it.newLine
                                                    number != null &&
                                                        end != null &&
                                                        number <= end &&
                                                        number > 0
                                                }
                                                .takeLast(100)
                                                .forEach { candidate ->
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                (if (side == "left")
                                                                        candidate.oldLine
                                                                    else candidate.newLine)
                                                                    .toString()
                                                            )
                                                        },
                                                        onClick = {
                                                            rangeStart = candidate
                                                            expanded = false
                                                        },
                                                    )
                                                }
                                        }
                                    }
                                }
                            }
                            if (kind == "review")
                                FlowRow {
                                    permissions.verdicts.forEach { option ->
                                        FilterChip(
                                            selectedVerdict == option,
                                            { verdict = option },
                                            label = { Text(option.replace('-', ' ')) },
                                        )
                                    }
                                }
                            OutlinedTextField(
                                form,
                                {
                                    form = it
                                    if (kind == "review") reviewBody = it
                                    else if (kind == "comment") commentBody = it
                                },
                                label = { Text(if (kind == "edit") "Description" else "Comment") },
                                minLines = 3,
                                maxLines = 12,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                            )
                            if (kind == "review" && drafts.isNotEmpty())
                                Text("Includes ${drafts.size} line comments")
                            reviewValidation?.exceptionOrNull()?.message?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (kind !in setOf("reviewers", "labels"))
                    TextButton(
                        enabled =
                            canWrite &&
                                !busy &&
                                when {
                                    kind.startsWith("action:") ->
                                        actionValidation?.isSuccess == true
                                    kind == "review" ->
                                        draftsLoaded && reviewValidation?.isSuccess == true
                                    kind == "edit" ->
                                        title.isNotBlank() &&
                                            title.length <= 1024 &&
                                            form.length <= 65_536
                                    else -> form.isNotBlank() && form.length <= 65_536
                                },
                        onClick = {
                            when {
                                kind.startsWith("action:") ->
                                    perform {
                                        tools.action(
                                            request,
                                            kind.substringAfter(':'),
                                            mergeMethod,
                                            updateMethod,
                                            if (applyStack) stack else null,
                                        )
                                        dialog = null
                                        notice = "Action completed"
                                        reload()
                                    }
                                kind == "inline" -> {
                                    try {
                                        val selected = inline!!
                                        drafts =
                                            drafts +
                                                pendingReviewDraft(
                                                        inlineReviewDraft(
                                                            selected.first,
                                                            selected.second,
                                                            form,
                                                            side,
                                                            rangeStart,
                                                        ),
                                                        inlineHeadSha,
                                                    )
                                                    .toString()
                                        dialog = null
                                        form = ""
                                    } catch (failure: IllegalArgumentException) {
                                        error = failure.message
                                    }
                                }
                                kind == "review" ->
                                    perform {
                                        val sentDrafts = drafts
                                        val sentBody = form
                                        detail =
                                            parseWorkspacePullRequestDetail(
                                                request,
                                                tools.freshDetail(request),
                                                detail.rawActivity,
                                            )
                                        val input =
                                            pullRequestReviewInput(
                                                detail.rawDetail,
                                                selectedVerdict,
                                                sentBody,
                                                sentDrafts.map {
                                                    Json.parseToJsonElement(it).jsonObject
                                                },
                                            )
                                        tools.mutate(request, "pullRequests.submitReview", input)
                                        drafts = drafts.filterNot { it in sentDrafts }
                                        if (reviewBody == sentBody) reviewBody = ""
                                        if (form == sentBody) form = ""
                                        dialog = null
                                        notice = "Review submitted"
                                        reload()
                                    }
                                kind == "edit" ->
                                    mutate(
                                        "pullRequests.update",
                                        buildJsonObject {
                                            put("title", title)
                                            put("body", form)
                                        },
                                    )
                                kind == "edit-comment" ->
                                    mutate(
                                        "pullRequests.updateComment",
                                        buildJsonObject {
                                            put("commentId", target!!.string("id"))
                                            put("kind", target!!.string("kind"))
                                            put("body", form)
                                        },
                                    )
                                kind == "reply" ->
                                    mutate(
                                        "pullRequests.replyToThread",
                                        buildJsonObject {
                                            put("threadId", target!!.string("id"))
                                            put("body", form)
                                        },
                                    )
                                else ->
                                    mutate(
                                        "pullRequests.comment",
                                        buildJsonObject { put("body", form) },
                                    )
                            }
                        },
                    ) {
                        Text(if (kind == "inline") "Add to review" else "Confirm")
                    }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }, enabled = !busy) {
                    Text(if (kind in setOf("reviewers", "labels")) "Done" else "Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrDiscussionComment(
    comment: JsonObject,
    canReact: Boolean,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onReaction: (String, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            comment.obj("author").string("login", "Unknown author"),
            style = MaterialTheme.typography.labelLarge,
        )
        MarkdownMessage(comment.string("body"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canEdit) TextButton(onClick = onEdit) { Text("Edit") }
            if (canReact)
                listOf(
                        "thumbs-up" to "👍",
                        "thumbs-down" to "👎",
                        "laugh" to "😄",
                        "hooray" to "🎉",
                        "confused" to "😕",
                        "heart" to "❤️",
                        "rocket" to "🚀",
                        "eyes" to "👀",
                    )
                    .forEach { (content, label) ->
                        val reaction =
                            comment
                                .array("reactions")
                                .map { it.jsonObject }
                                .firstOrNull { it.string("content") == content }
                        FilterChip(
                            selected = reaction?.bool("viewerHasReacted") == true,
                            onClick = {
                                onReaction(content, reaction?.bool("viewerHasReacted") != true)
                            },
                            label = {
                                Text("$label ${reaction?.long("count")?.takeIf { it > 0 } ?: ""}")
                            },
                        )
                    }
        }
    }
}

private fun prActionLabel(action: String) =
    when (action) {
        "ready" -> "Mark ready"
        "draft" -> "Convert to draft"
        "update-branch" -> "Update branch"
        "enable-auto-merge" -> "Enable auto-merge"
        "disable-auto-merge" -> "Disable auto-merge"
        "delete-branch" -> "Delete branch"
        "approve-workflows" -> "Approve workflows"
        "revert" -> "Create revert"
        else -> action.replaceFirstChar { it.uppercase() }
    }
