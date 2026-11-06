package com.example.teethree.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.T3Project
import com.example.teethree.data.T3Repository
import com.example.teethree.data.WorkspaceBranches
import com.example.teethree.data.WorkspaceDirectory
import com.example.teethree.data.WorkspaceFile
import com.example.teethree.data.WorkspaceGitStatus
import com.example.teethree.data.WorkspacePatch
import com.example.teethree.data.WorkspacePullRequestDetail
import com.example.teethree.data.WorkspacePullRequestList
import com.example.teethree.data.WorkspaceTools
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    repository: T3Repository,
    projects: List<T3Project>,
    onBack: () -> Unit,
    initialProjectId: String? = null,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val tools = remember(repository) { WorkspaceTools(repository) }
    val scope = rememberCoroutineScope()
    var projectId by rememberSaveable { mutableStateOf(initialProjectId) }
    val project = projects.find { it.id == projectId } ?: projects.firstOrNull()
    var projectMenu by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var directory by rememberSaveable(project?.id) { mutableStateOf("") }
    var entries by remember(project?.id) { mutableStateOf<WorkspaceDirectory?>(null) }
    var openedFile by remember(project?.id) { mutableStateOf<WorkspaceFile?>(null) }
    var git by remember(project?.id) { mutableStateOf<WorkspaceGitStatus?>(null) }
    var branches by remember(project?.id) { mutableStateOf<WorkspaceBranches?>(null) }
    var selected by remember(project?.id) { mutableStateOf<Set<String>>(emptySet()) }
    var commitMessage by rememberSaveable(project?.id) { mutableStateOf("") }
    var branchName by remember { mutableStateOf("") }
    var creatingBranch by remember { mutableStateOf(false) }
    var prState by rememberSaveable { mutableStateOf("open") }
    var requests by remember(project?.id) { mutableStateOf<WorkspacePullRequestList?>(null) }
    var detail by remember(project?.id) { mutableStateOf<WorkspacePullRequestDetail?>(null) }
    var patch by remember(project?.id) { mutableStateOf<WorkspacePatch?>(null) }
    var patchTitle by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val connected = state.connectionStatus == ConnectionStatus.Connected
    val canRead =
        if (tab == 0) "filesystem:read" in state.scopes else "orchestration:read" in state.scopes
    val canWrite = connected && "source-control:write" in state.scopes
    val canReadDiff = connected && "filesystem:read" in state.scopes

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            notice = null
            try {
                action()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "The environment could not complete this request."
            } finally {
                busy = false
            }
        }
    }

    fun back() {
        when {
            patch != null -> patch = null
            openedFile != null -> openedFile = null
            detail != null -> detail = null
            tab == 0 && directory.isNotEmpty() -> directory = directory.substringBeforeLast('/', "")
            else -> onBack()
        }
    }
    BackHandler(
        openedFile != null ||
            detail != null ||
            patch != null ||
            (tab == 0 && directory.isNotEmpty())
    ) {
        back()
    }
    detail?.let { current ->
        PullRequestScreen(
            repository,
            current,
            onBack = {
                detail = null
                refresh += 1
            },
        )
        return
    }
    LaunchedEffect(project?.id, tab, directory, prState, refresh, connected, canRead) {
        val current = project ?: return@LaunchedEffect
        if (!connected || !canRead) return@LaunchedEffect
        busy = true
        error = null
        try {
            when (tab) {
                0 -> {
                    entries = null
                    entries = tools.directory(current.workspaceRoot, directory)
                }
                1 ->
                    coroutineScope {
                        val status = async { tools.gitStatus(current.workspaceRoot) }
                        val refs = async { tools.branches(current.workspaceRoot) }
                        git = status.await()
                        branches = refs.await()
                        selected = selected.intersect(git?.files.orEmpty().map { it.path }.toSet())
                    }
                2 -> {
                    val request = detail?.request
                    if (request != null) detail = tools.pullRequestDetail(request)
                    else {
                        requests = null
                        requests = tools.pullRequests(current.id, prState)
                    }
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.message ?: "The environment could not load this view."
        } finally {
            busy = false
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        when {
                            patch != null -> patchTitle
                            openedFile != null -> openedFile!!.path.substringAfterLast('/')
                            detail != null -> "Pull request #${detail!!.request.number}"
                            else -> "Workspace tools"
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val file = openedFile
                            if (file != null && project != null)
                                perform {
                                    openedFile = tools.file(project.workspaceRoot, file.path)
                                }
                            else {
                                patch = null
                                refresh += 1
                            }
                        },
                        enabled = connected && canRead && !busy,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 1000.dp).fillMaxSize()) {
                if (openedFile == null && detail == null && patch == null) {
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        OutlinedButton(
                            onClick = { projectMenu = true },
                            enabled = projects.isNotEmpty() && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(project?.title ?: "No projects available", Modifier.weight(1f))
                            Icon(Icons.Outlined.ExpandMore, contentDescription = "Choose project")
                        }
                        DropdownMenu(
                            expanded = projectMenu,
                            onDismissRequest = { projectMenu = false },
                        ) {
                            projects.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(candidate.title) },
                                    onClick = {
                                        projectId = candidate.id
                                        projectMenu = false
                                        notice = null
                                    },
                                )
                            }
                        }
                    }
                    PrimaryTabRow(selectedTabIndex = tab) {
                        listOf("Files", "Git", "Pull requests").forEachIndexed { index, label ->
                            Tab(
                                selected = tab == index,
                                enabled = !busy,
                                onClick = {
                                    tab = index
                                    error = null
                                    notice = null
                                },
                                text = { Text(label) },
                            )
                        }
                    }
                }
                if (!connected)
                    Text(
                        "Reconnect to your environment to use workspace tools.",
                        Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                else if (!canRead)
                    Text(
                        "This connection does not have permission to read this view.",
                        Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                if (busy)
                    Text(
                        "Loading from your environment…",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                error?.let {
                    Text(
                        it,
                        Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                notice?.let {
                    Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.primary)
                }
                when {
                    patch != null ->
                        PatchContent(patch!!, Modifier.weight(1f), busy) {
                            val current = detail ?: return@PatchContent
                            val previous = patch ?: return@PatchContent
                            perform {
                                val next =
                                    tools.pullRequestDiff(current.request, previous.nextCursor)
                                patch = next.copy(text = previous.text + "\n" + next.text)
                            }
                        }
                    openedFile != null -> FileContent(openedFile!!, Modifier.weight(1f))
                    project == null ->
                        Text(
                            "Add a project to browse its files and source control.",
                            Modifier.padding(24.dp),
                        )
                    tab == 0 ->
                        LazyColumn(
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item {
                                Text(
                                    if (directory.isEmpty()) project.workspaceRoot else directory,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (directory.isNotEmpty())
                                    TextButton(
                                        onClick = {
                                            directory = directory.substringBeforeLast('/', "")
                                        },
                                        enabled = !busy,
                                    ) {
                                        Text("Parent folder")
                                    }
                            }
                            items(entries?.entries.orEmpty(), key = { it.path }) { entry ->
                                Card(
                                    onClick = {
                                        if (entry.directory) directory = entry.path
                                        else
                                            perform {
                                                openedFile =
                                                    tools.file(project.workspaceRoot, entry.path)
                                            }
                                    },
                                    enabled = connected && canRead && !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(
                                            if (entry.directory) Icons.Outlined.FolderOpen
                                            else Icons.Outlined.Description,
                                            contentDescription =
                                                if (entry.directory) "Folder" else "File",
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                entry.path.substringAfterLast('/'),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (entry.ignored)
                                                Text(
                                                    "Ignored by source control",
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                        }
                                    }
                                }
                            }
                            if (entries?.truncated == true)
                                item { Text("The server limited this folder listing.") }
                            if (entries?.entries?.isEmpty() == true && !busy)
                                item { Text("This folder is empty.") }
                        }
                    tab == 1 ->
                        LazyColumn(
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            item {
                                Text(
                                    git?.branch
                                        ?: if (git?.isRepository == true) "Detached HEAD"
                                        else "No repository found",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                if (git?.isRepository == true)
                                    Text(
                                        "${git!!.ahead} ahead · ${git!!.behind} behind",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                            }
                            if (git?.isRepository == true) {
                                item {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                perform {
                                                    tools.pull(project.workspaceRoot)
                                                    refresh += 1
                                                }
                                            },
                                            enabled = canWrite && !busy && git!!.hasRemote,
                                        ) {
                                            Text("Pull")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                perform {
                                                    notice =
                                                        tools.gitAction(
                                                            project.workspaceRoot,
                                                            "push",
                                                        )
                                                    refresh += 1
                                                }
                                            },
                                            enabled = canWrite && !busy && git!!.hasRemote,
                                        ) {
                                            Text("Push")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            perform {
                                                notice =
                                                    tools.gitAction(
                                                        project.workspaceRoot,
                                                        "create_pr",
                                                    )
                                                refresh += 1
                                            }
                                        },
                                        enabled = canWrite && !busy && git!!.hasRemote,
                                    ) {
                                        Text("Open pull request")
                                    }
                                }
                                item {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "Changed files",
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        TextButton(
                                            onClick = {
                                                selected =
                                                    if (selected.size == git!!.files.size)
                                                        emptySet()
                                                    else git!!.files.map { it.path }.toSet()
                                            },
                                            enabled = !busy && canWrite,
                                        ) {
                                            Text(
                                                if (selected.size == git!!.files.size) "Clear"
                                                else "Select all"
                                            )
                                        }
                                    }
                                    if (git!!.files.isEmpty())
                                        Text(
                                            "Working tree is clean.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                }
                                items(git!!.files, key = { it.path }) { file ->
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(end = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Checkbox(
                                                checked = file.path in selected,
                                                enabled = canWrite && !busy,
                                                onCheckedChange = { checked ->
                                                    selected =
                                                        if (checked) selected + file.path
                                                        else selected - file.path
                                                },
                                            )
                                            Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                                Text(
                                                    file.path,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    "+${file.insertions} −${file.deletions}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    perform {
                                                        patchTitle = file.path
                                                        patch =
                                                            tools.diff(
                                                                git!!.rootPath
                                                                    ?: project.workspaceRoot,
                                                                file.path,
                                                            )
                                                    }
                                                },
                                                enabled = !busy && canReadDiff,
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Code,
                                                    contentDescription =
                                                        "View diff for ${file.path}",
                                                )
                                            }
                                        }
                                    }
                                }
                                if (git!!.files.isNotEmpty())
                                    item {
                                        OutlinedTextField(
                                            commitMessage,
                                            { commitMessage = it },
                                            label = { Text("Commit message") },
                                            enabled = !busy && canWrite,
                                            modifier = Modifier.fillMaxWidth(),
                                            maxLines = 6,
                                        )
                                        Button(
                                            onClick = {
                                                perform {
                                                    notice =
                                                        tools.gitAction(
                                                            git!!.rootPath ?: project.workspaceRoot,
                                                            "commit",
                                                            commitMessage,
                                                            selected,
                                                        )
                                                    commitMessage = ""
                                                    selected = emptySet()
                                                    refresh += 1
                                                }
                                            },
                                            enabled =
                                                !busy &&
                                                    canWrite &&
                                                    selected.isNotEmpty() &&
                                                    commitMessage.isNotBlank(),
                                        ) {
                                            Text("Commit ${selected.size} selected files")
                                        }
                                    }
                                item {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "Branches",
                                            Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        TextButton(
                                            onClick = { creatingBranch = true },
                                            enabled = canWrite && !busy,
                                        ) {
                                            Text("New branch")
                                        }
                                    }
                                }
                                items(
                                    branches?.branches.orEmpty(),
                                    key = { "${it.remote}:${it.name}" },
                                ) { branch ->
                                    OutlinedButton(
                                        onClick = {
                                            perform {
                                                tools.switchBranch(
                                                    project.workspaceRoot,
                                                    branch.name,
                                                )
                                                refresh += 1
                                            }
                                        },
                                        enabled = canWrite && !busy && !branch.current,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            branch.name +
                                                if (branch.current) " · current"
                                                else if (branch.remote) " · remote" else "",
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                branches?.nextCursor?.let { cursor ->
                                    item {
                                        TextButton(
                                            onClick = {
                                                perform {
                                                    val next =
                                                        tools.branches(
                                                            project.workspaceRoot,
                                                            cursor,
                                                        )
                                                    branches =
                                                        next.copy(
                                                            branches =
                                                                (branches?.branches.orEmpty() +
                                                                        next.branches)
                                                                    .distinctBy {
                                                                        "${it.remote}:${it.name}"
                                                                    }
                                                        )
                                                }
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text("Load more branches")
                                        }
                                    }
                                }
                            }
                        }
                    else ->
                        LazyColumn(
                            Modifier.weight(1f),
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(listOf("open", "merged", "closed", "all")) { filter ->
                                        FilterChip(
                                            prState == filter,
                                            { prState = filter },
                                            enabled = !busy,
                                            label = {
                                                Text(filter.replaceFirstChar { it.uppercase() })
                                            },
                                        )
                                    }
                                }
                            }
                            requests?.errors.orEmpty().forEach { message ->
                                item { Text(message, color = MaterialTheme.colorScheme.error) }
                            }
                            items(
                                requests?.entries.orEmpty(),
                                key = { "${it.host}:${it.repository}:${it.number}" },
                            ) { request ->
                                Card(
                                    onClick = {
                                        perform { detail = tools.pullRequestDetail(request) }
                                    },
                                    enabled = connected && canRead && !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        Modifier.padding(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            "#${request.number} ${request.title}",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            "${request.repository} · ${request.author} · ${request.state}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (requests?.entries?.isEmpty() == true && !busy)
                                item { Text("No pull requests match this view.") }
                            if (
                                requests?.truncated == true &&
                                    requests?.nextCursors?.isEmpty() == true
                            )
                                item {
                                    Text("The server limited this pull request listing.")
                                }
                            if (requests?.nextCursors?.isNotEmpty() == true)
                                item {
                                    TextButton(
                                        onClick = {
                                            perform {
                                                val next =
                                                    tools.pullRequests(
                                                        project.id,
                                                        prState,
                                                        requests?.nextCursors,
                                                    )
                                                requests =
                                                    next.copy(
                                                        entries =
                                                            (requests?.entries.orEmpty() +
                                                                    next.entries)
                                                                .distinctBy {
                                                                    "${it.host}:${it.repository}:${it.number}"
                                                                }
                                                    )
                                            }
                                        },
                                        enabled = !busy,
                                    ) {
                                        Text("Load more pull requests")
                                    }
                                }
                        }
                }
            }
        }
    }
    if (creatingBranch && project != null)
        AlertDialog(
            onDismissRequest = { if (!busy) creatingBranch = false },
            title = { Text("Create branch") },
            text = {
                OutlinedTextField(
                    branchName,
                    { branchName = it },
                    label = { Text("Branch name") },
                    singleLine = true,
                    enabled = !busy,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        perform {
                            tools.createBranch(project.workspaceRoot, branchName)
                            branchName = ""
                            creatingBranch = false
                            refresh += 1
                        }
                    },
                    enabled = canWrite && !busy && branchName.isNotBlank(),
                ) {
                    Text("Create and switch")
                }
            },
            dismissButton = {
                TextButton(onClick = { creatingBranch = false }, enabled = !busy) { Text("Cancel") }
            },
        )
}

@Composable
private fun FileContent(file: WorkspaceFile, modifier: Modifier = Modifier) {
    val lines = remember(file.contents) { file.contents.lines() }
    val appearance = LocalClientAppearance.current
    SelectionContainer(modifier) {
        LazyColumn(contentPadding = PaddingValues(20.dp), modifier = Modifier.fillMaxSize()) {
            if (file.truncated)
                item {
                    Text(
                        "The server limited this file preview.",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            items(lines.size) { index ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "${index + 1}",
                        modifier = Modifier.widthIn(min = 32.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = appearance.codeFontSize.sp,
                        lineHeight = (appearance.codeFontSize + 6).sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        lines[index].ifEmpty { " " },
                        modifier =
                            Modifier.weight(1f)
                                .then(
                                    if (appearance.codeWordBreak) Modifier
                                    else Modifier.horizontalScroll(rememberScrollState())
                                ),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = appearance.codeFontSize.sp,
                        lineHeight = (appearance.codeFontSize + 6).sp,
                        softWrap = appearance.codeWordBreak,
                    )
                }
            }
        }
    }
}

@Composable
private fun PatchContent(
    patch: WorkspacePatch,
    modifier: Modifier,
    busy: Boolean,
    onLoadMore: () -> Unit,
) {
    val lines = remember(patch.text) { patch.text.lines() }
    val appearance = LocalClientAppearance.current
    SelectionContainer(modifier) {
        LazyColumn(contentPadding = PaddingValues(20.dp), modifier = Modifier.fillMaxSize()) {
            items(lines.size) { index ->
                val line = lines[index]
                Text(
                    line.ifEmpty { " " },
                    modifier =
                        Modifier.fillMaxWidth()
                            .then(
                                if (appearance.codeWordBreak) Modifier
                                else Modifier.horizontalScroll(rememberScrollState())
                            ),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = appearance.codeFontSize.sp,
                    lineHeight = (appearance.codeFontSize + 6).sp,
                    softWrap = appearance.codeWordBreak,
                    color =
                        when {
                            line.startsWith('+') -> MaterialTheme.colorScheme.primary
                            line.startsWith('-') -> MaterialTheme.colorScheme.error
                            line.startsWith("@@") -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                )
            }
            if (patch.truncated) item { Text("The server limited this diff preview.") }
            if (patch.nextCursor != null)
                item {
                    TextButton(onClick = onLoadMore, enabled = !busy) { Text("Load more changes") }
                }
        }
    }
}
