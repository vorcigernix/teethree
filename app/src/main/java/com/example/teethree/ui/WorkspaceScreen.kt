package com.example.teethree.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.teethree.data.T3Project
import com.example.teethree.data.T3Thread
import com.example.teethree.data.WorkspaceEnvironmentPhase
import com.example.teethree.data.WorkspaceEnvironmentShell
import com.example.teethree.data.WorkspaceProjectRef
import com.example.teethree.data.WorkspaceThreadRef
import com.example.teethree.data.groupWorkspaceProjects
import com.example.teethree.data.hiddenThreadIds
import com.example.teethree.data.nullableString
import com.example.teethree.data.obj
import com.example.teethree.data.parsedInstant
import com.example.teethree.data.threadIsListed
import com.example.teethree.data.threadSnoozed
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private data class EnvironmentThreadItem(
    val ref: WorkspaceThreadRef,
    val projectRef: WorkspaceProjectRef,
    val thread: T3Thread,
    val projectTitle: String,
    val environmentLabel: String,
    val phase: WorkspaceEnvironmentPhase,
)

@Composable
fun WorkspaceScreen(
    projects: List<T3Project>,
    threads: List<T3Thread>,
    connectionLabel: String,
    isConnected: Boolean,
    loading: Boolean,
    error: String?,
    showArchived: Boolean,
    onShowArchivedChange: (Boolean) -> Unit,
    onThreadOpen: (T3Thread) -> Unit,
    onNewThread: () -> Unit,
    onSettings: () -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    canCreateThread: Boolean = true,
    onAddProject: () -> Unit = {},
    onTools: () -> Unit = {},
    selectedThreadId: String? = null,
    showNavigationActions: Boolean = true,
    onOpenNavigation: (() -> Unit)? = null,
    bottomComposer: (@Composable (T3Project?) -> Unit)? = null,
    compactLayout: Boolean = true,
    projectGrouping: String = "repository",
    serverConfig: JsonObject? = null,
    environments: List<WorkspaceEnvironmentShell>? = null,
    selectedConnectionId: String? = null,
    onEnvironmentThreadOpen: ((WorkspaceThreadRef) -> Unit)? = null,
    bottomEnvironmentComposer: (@Composable (WorkspaceProjectRef?) -> Unit)? = null,
    onEnvironmentNewThread: ((WorkspaceProjectRef?) -> Unit)? = null,
    canSettle: (WorkspaceThreadRef) -> Boolean = { false },
    onSetSettled: (WorkspaceThreadRef, T3Thread, Boolean) -> Unit = { _, _, _ -> },
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var projectGroupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showSnoozed by rememberSaveable { mutableStateOf(false) }
    // Two lists: what is still live, and what has been settled (done, out of the way).
    var settledTab by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // A swipe moves the row at once; the server's answer then replaces the guess.
    val settleGuesses = remember { mutableStateMapOf<String, Boolean>() }
    // Bumped on every settle and Undo, so a returning row gets a fresh, unswiped state.
    val swipeGenerations = remember { mutableStateMapOf<String, Int>() }
    var menuOpen by remember { mutableStateOf(false) }
    val sources =
        environments
            ?: listOf(
                WorkspaceEnvironmentShell(
                    selectedConnectionId.orEmpty(),
                    serverConfig?.obj("environment")?.nullableString("environmentId"),
                    connectionLabel,
                    if (isConnected) WorkspaceEnvironmentPhase.Live
                    else if (loading) WorkspaceEnvironmentPhase.Connecting
                    else WorkspaceEnvironmentPhase.Disconnected,
                    projects,
                    threads,
                    serverConfig = serverConfig,
                    error = error,
                    shellReady = !loading,
                )
            )
    val projectsByRef =
        remember(sources) {
            sources
                .flatMap { source ->
                    source.projects.map {
                        WorkspaceProjectRef(source.connectionId, source.environmentId, it.id) to it
                    }
                }
                .toMap()
        }
    val projectGroups =
        remember(sources, projectGrouping, showArchived) {
            groupWorkspaceProjects(sources, projectGrouping).filter { showArchived || !it.archived }
        }
    val selectedGroup = projectGroups.find { it.key == projectGroupKey }
    val selectedProjectRef = selectedGroup?.representative
    val selectedProject = selectedProjectRef?.let(projectsByRef::get)
    val defaultProjectRef =
        selectedProjectRef
            ?: sources
                .sortedBy { it.connectionId != selectedConnectionId }
                .firstNotNullOfOrNull { source ->
                    source.projects
                        .firstOrNull { !it.archived }
                        ?.let {
                            WorkspaceProjectRef(source.connectionId, source.environmentId, it.id)
                        }
                }
    val allThreads = sources.flatMap { it.threads }
    val canCreateAtDestination =
        if (environments == null) isConnected && canCreateThread
        else
            sources
                .find { it.connectionId == defaultProjectRef?.connectionId }
                ?.let {
                    it.phase == WorkspaceEnvironmentPhase.Live &&
                        "orchestration:operate" in it.scopes
                } == true
    var lifecycleNow by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(allThreads, lifecycleNow) {
        val current = Instant.now()
        val next =
            allThreads
                .mapNotNull { parsedInstant(it.snoozedUntil) }
                .filter { it.isAfter(current) }
                .minOrNull()
        if (next != null) {
            delay(Duration.between(current, next).toMillis())
            lifecycleNow = Instant.now()
        }
    }
    val hiddenIds =
        remember(sources) {
            sources.associate { it.connectionId to hiddenThreadIds(it.threads, it.serverConfig) }
        }
    val visibleThreads =
        remember(
            sources,
            projectsByRef,
            selectedGroup,
            query,
            showArchived,
            showSnoozed,
            hiddenIds,
            lifecycleNow,
        ) {
            sources
                .flatMap { source ->
                    source.threads.mapNotNull { thread ->
                        val projectRef =
                            WorkspaceProjectRef(
                                source.connectionId,
                                source.environmentId,
                                thread.projectId,
                            )
                        val projectTitle = projectsByRef[projectRef]?.title ?: "Project"
                        if (
                            threadIsListed(thread) &&
                                thread.id !in hiddenIds[source.connectionId].orEmpty() &&
                                thread.archived == showArchived &&
                                (showArchived ||
                                    threadSnoozed(thread, lifecycleNow) == showSnoozed) &&
                                (selectedGroup == null || projectRef in selectedGroup.members) &&
                                (query.isBlank() ||
                                    thread.title.contains(query, true) ||
                                    thread.preview.contains(query, true) ||
                                    projectTitle.contains(query, true) ||
                                    source.label.contains(query, true))
                        )
                            EnvironmentThreadItem(
                                WorkspaceThreadRef(
                                    source.connectionId,
                                    source.environmentId,
                                    thread.id,
                                ),
                                projectRef,
                                thread,
                                projectTitle,
                                source.label,
                                source.phase,
                            )
                        else null
                    }
                }
                .sortedWith(
                    compareByDescending<EnvironmentThreadItem> { it.thread.pinned }
                        .thenBy { it.thread.settled }
                        .thenByDescending { it.thread.updatedAt }
                )
        }
    fun settleKey(ref: WorkspaceThreadRef) = "${ref.connectionId}:${ref.threadId}"
    SideEffect {
        visibleThreads.forEach { row ->
            val key = settleKey(row.ref)
            if (settleGuesses[key] == row.thread.settled) settleGuesses.remove(key)
        }
    }
    fun EnvironmentThreadItem.isSettled() =
        !thread.needsAttention &&
            !thread.isWorking() &&
            (settleGuesses[settleKey(ref)] ?: thread.settled)
    val current = visibleThreads.filterNot { it.isSettled() }
    val settled = visibleThreads.filter { it.isSettled() }
    val sections =
        if (settledTab) listOf("" to settled)
        else
            listOf(
                "Needs you" to current.filter { it.thread.needsAttention },
                "Running" to current.filter { !it.thread.needsAttention && it.thread.isWorking() },
                "Earlier" to current.filter { !it.thread.needsAttention && !it.thread.isWorking() },
            )
    fun setSettled(row: EnvironmentThreadItem, target: Boolean) {
        val key = settleKey(row.ref)
        swipeGenerations[key] = (swipeGenerations[key] ?: 0) + 1
        settleGuesses[key] = target
        onSetSettled(row.ref, row.thread, target)
        scope.launch {
            // If the desktop never confirms (say the request failed), drop the guess.
            launch {
                delay(15_000)
                if (settleGuesses[key] == target) settleGuesses.remove(key)
            }
            snackbar.currentSnackbarData?.dismiss()
            val result =
                snackbar.showSnackbar(
                    if (target) "Chat settled" else "Moved back to Current",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short,
                )
            if (result == SnackbarResult.ActionPerformed) {
                swipeGenerations[key] = (swipeGenerations[key] ?: 0) + 1
                settleGuesses[key] = !target
                onSetSettled(row.ref, row.thread, !target)
            }
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            Surface {
                Column(Modifier.statusBarsPadding()) {
                    Row(
                        Modifier.fillMaxWidth().height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onOpenNavigation != null)
                            IconButton(onClick = onOpenNavigation) {
                                Icon(Icons.Outlined.Menu, "Open navigation")
                            }
                        Column(
                            Modifier.weight(1f)
                                .padding(start = if (onOpenNavigation == null) 20.dp else 0.dp)
                        ) {
                            Text(
                                when {
                                    showArchived -> "Archived"
                                    showSnoozed -> "Snoozed"
                                    else -> "Chats"
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                            // "Connecting to devbox…" sits where the subtitle goes, then gives way.
                            ConnectionStatusLine {
                                Text(
                                    if (visibleThreads.size == 1) "1 conversation"
                                    else "${visibleThreads.size} conversations",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (compactLayout)
                            IconButton(onClick = { searchVisible = !searchVisible }) {
                                Icon(Icons.Outlined.Search, "Search chats")
                            }
                        val link = LocalLink.current
                        if (link != null)
                            HostIndicator(
                                onClick = { if (link.phase.offline) link.help() else onConnect() }
                            )
                        else
                            IconButton(onClick = onConnect) {
                                Icon(
                                    Icons.Outlined.CloudQueue,
                                    "Connections: $connectionLabel. ${if (loading) "Connecting" else if (isConnected) "Connected" else "Disconnected"}",
                                )
                            }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreHoriz, "Chat list options")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("New task") },
                                    enabled = canCreateAtDestination,
                                    onClick = {
                                        menuOpen = false
                                        if (onEnvironmentNewThread != null)
                                            onEnvironmentNewThread(defaultProjectRef)
                                        else onNewThread()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Add project") },
                                    enabled = isConnected && canCreateThread,
                                    onClick = {
                                        menuOpen = false
                                        onAddProject()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (showArchived) "Show active chats"
                                            else "Show archived chats"
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onShowArchivedChange(!showArchived)
                                    },
                                )
                                if (!showArchived)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (showSnoozed) "Show unsnoozed chats"
                                                else "Show snoozed chats"
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            showSnoozed = !showSnoozed
                                        },
                                    )
                                DropdownMenuItem(
                                    text = { Text("Reconnect") },
                                    enabled = !loading,
                                    onClick = {
                                        menuOpen = false
                                        onRefresh()
                                    },
                                )
                                if (showNavigationActions) {
                                    DropdownMenuItem(
                                        text = { Text("Files, Git, and pull requests") },
                                        enabled = isConnected,
                                        onClick = {
                                            menuOpen = false
                                            onTools()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Settings") },
                                        onClick = {
                                            menuOpen = false
                                            onSettings()
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (!compactLayout || searchVisible || query.isNotBlank())
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier =
                                Modifier.fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                            placeholder = { Text("Search chats") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        query = ""
                                        searchVisible = false
                                    }
                                ) {
                                    Icon(Icons.Outlined.Close, "Close search")
                                }
                            },
                            shape = RoundedCornerShape(18.dp),
                        )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            FilterChip(
                                selected = selectedProject == null,
                                onClick = { projectGroupKey = null },
                                label = { Text("All") },
                            )
                        }
                        items(projectGroups, key = { it.key }) { group ->
                            FilterChip(
                                selected = selectedGroup?.key == group.key,
                                onClick = {
                                    projectGroupKey =
                                        if (selectedGroup?.key == group.key) null else group.key
                                },
                                label = { Text(group.label, maxLines = 1) },
                            )
                        }
                    }
                    if (!showArchived)
                        SecondaryTabRow(
                            selectedTabIndex = if (settledTab) 1 else 0,
                            containerColor = Color.Transparent,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Tab(
                                selected = !settledTab,
                                onClick = { settledTab = false },
                                text = { Text("Current") },
                                modifier = Modifier.testTag("tab-current"),
                            )
                            Tab(
                                selected = settledTab,
                                onClick = { settledTab = true },
                                text = {
                                    Text(if (settled.isEmpty()) "Settled" else "Settled · ${settled.size}")
                                },
                                modifier = Modifier.testTag("tab-settled"),
                            )
                        }
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { insets ->
        // The project chip on the dock names where a new task goes, so no caption above it.
        val composer: (@Composable () -> Unit)? =
            if (
                compactLayout &&
                    !showArchived &&
                    (bottomComposer != null || bottomEnvironmentComposer != null)
            )
                ({
                    if (bottomEnvironmentComposer != null)
                        bottomEnvironmentComposer(defaultProjectRef)
                    else bottomComposer?.invoke(selectedProject ?: projects.firstOrNull())
                })
            else null
        val link = LocalLink.current
        val linkTrouble = link != null && link.phase.offline
        val waiting = rememberLinkWaiting()
        val reducedMotion = rememberReducedMotion()
        // Threads that appeared since the last list we showed (e.g. started on the desktop while
        // this phone was away): they expand in with a fading tint. Filter changes don't count.
        val threadIds = threads.map { it.id }.toSet()
        var knownIds by remember { mutableStateOf<Set<String>?>(null) }
        val freshIds = remember { mutableStateMapOf<String, Unit>() }
        LaunchedEffect(threadIds) {
            knownIds?.let { known -> (threadIds - known).forEach { freshIds[it] = Unit } }
            knownIds = threadIds
            delay(1_000)
            freshIds.clear()
        }
        FloatingDockLayout(
            com.example.teethree.ui.adaptive.DockSlots.List,
            Modifier.fillMaxSize().padding(insets),
            dock = composer,
        ) { dockPadding ->
        LazyColumn(
            Modifier.fillMaxSize().contentSheen(waiting),
            contentPadding =
                PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom =
                        (if (composer != null) dockPadding
                        else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()) +
                            8.dp,
                ),
        ) {
            if (error != null && !linkTrouble)
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier =
                                    Modifier.semantics {
                                        contentDescription = "Connection error: $error"
                                    },
                            )
                            TextButton(onClick = onRefresh, enabled = !loading) {
                                Text("Try again")
                            }
                        }
                    }
                }
            sections.forEach { (heading, rows) ->
                if (rows.isNotEmpty()) {
                    if (heading.isNotEmpty()) item(key = "section:$heading") {
                        Text(
                            heading,
                            Modifier.padding(start = 8.dp, top = 18.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(
                        rows,
                        key = { Json.encodeToString(listOf(it.ref.connectionId, it.ref.threadId)) },
                    ) { row ->
                        val rowSettled = row.isSettled()
                        val swipeable =
                            !row.thread.needsAttention && !row.thread.isWorking() && canSettle(row.ref)
                        SettleSwipe(
                            settled = rowSettled,
                            generation = swipeGenerations[settleKey(row.ref)] ?: 0,
                            enabled = swipeable,
                            onSettle = { setSettled(row, !rowSettled) },
                            modifier =
                                if (reducedMotion) Modifier
                                else
                                    Modifier.animateItem(
                                        fadeInSpec = tween(300),
                                        placementSpec =
                                            spring(
                                                stiffness = Spring.StiffnessMediumLow,
                                                visibilityThreshold = IntOffset.VisibilityThreshold,
                                            ),
                                        fadeOutSpec = tween(150),
                                    ),
                        ) {
                        WorkspaceThreadRow(
                            row.thread,
                            buildString {
                                append(row.projectTitle)
                                if (sources.size > 1) append(" · ${row.environmentLabel}")
                                if (row.phase != WorkspaceEnvironmentPhase.Live) append(" · Saved")
                            },
                            row.thread.id == selectedThreadId &&
                                (selectedConnectionId == null ||
                                    row.ref.connectionId == selectedConnectionId),
                            onClick = {
                                if (onEnvironmentThreadOpen != null)
                                    onEnvironmentThreadOpen(row.ref)
                                else onThreadOpen(row.thread)
                            },
                            fresh = row.thread.id in freshIds,
                        )
                        }
                    }
                }
            }
            val tabEmpty = if (settledTab) settled.isEmpty() else current.isEmpty()
            if (tabEmpty && visibleThreads.isNotEmpty() && query.isBlank())
                item(key = "tab-empty") {
                    Text(
                        if (settledTab) "Nothing settled yet. Swipe a finished chat to settle it."
                        else "All caught up",
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            if (visibleThreads.isEmpty())
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            when {
                                loading -> "Connecting…"
                                query.isNotBlank() -> "No matching chats"
                                showArchived -> "No archived chats"
                                showSnoozed -> "No snoozed chats"
                                !isConnected -> "Connect to your workspace"
                                else -> "Your next idea starts here"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (!isConnected && !loading)
                            FilledTonalButton(onClick = onConnect) { Text("Choose a connection") }
                    }
                }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter)
                .padding(
                    bottom =
                        if (composer != null) dockPadding
                        else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                ),
        )
        // Layered over the top of the list, so it never pushes anything down.
        WaitingProgressLine(Modifier.align(Alignment.TopCenter).padding(horizontal = 20.dp))
        }
    }
}

@Composable
private fun WorkspaceThreadRow(
    thread: T3Thread,
    project: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fresh: Boolean = false,
) {
    val attention = thread.needsAttention
    val working = thread.isWorking()
    val tint =
        when {
            attention -> MaterialTheme.colorScheme.tertiary
            working -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color =
            when {
                // Neutral fill; the tertiary icon already flags it. A light tertiary (yellow)
                // fill on a whole row drowns the text.
                attention -> MaterialTheme.colorScheme.surfaceContainerHigh
                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                else -> Color.Transparent
            },
        modifier = modifier.fillMaxWidth().semantics { selected = isSelected },
    ) {
        val secondary =
            "$project · ${if (attention) "Needs your input" else thread.status.replace('_', ' ').replaceFirstChar { it.uppercase() }}"
        val time = relativeThreadTime(thread.updatedAt)
        val changeTint = rememberChangeTint(secondary, fresh)
        val tintColor = MaterialTheme.colorScheme.secondaryContainer
        Row(
            Modifier.drawBehind {
                if (changeTint > 0f) drawRect(tintColor.copy(alpha = changeTint))
            }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val icon =
                when {
                    attention -> Icons.Outlined.ErrorOutline
                    thread.pinned -> Icons.Outlined.PushPin
                    working -> Icons.Outlined.Code
                    thread.status == "completed" -> Icons.Outlined.Check
                    else -> Icons.Outlined.SmartToy
                }
            // A status change morphs the badge's shape and colour instead of swapping it.
            MorphingStatusBadge(icon, tint.copy(alpha = .09f)) {
                Crossfade(icon, animationSpec = tween(250), label = "status-icon") { shown ->
                Icon(
                    shown,
                    null,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    thread.title.ifBlank { "Untitled chat" },
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Same-size swap: the new text fades in over the old, nothing reflows.
                SameSizeSwap(secondary) { text ->
                    Text(
                        text,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SameSizeSwap(time) { text ->
                Text(
                    text,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Android's list swipe: the row follows the finger either way and, past the threshold, slides off
 * and leaves this list (settled, or back to Current); a snackbar offers Undo. Screen readers get
 * the same as a custom action.
 */
@Composable
private fun SettleSwipe(
    settled: Boolean,
    generation: Int,
    enabled: Boolean,
    onSettle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    // A fresh state per settled value: a row brought back by Undo must not arrive already swiped
    // off, or it would settle itself again.
    val state = androidx.compose.runtime.key(generation) { rememberSwipeToDismissBoxState() }
    val label = if (settled) "Move to Current" else "Settle"
    SwipeToDismissBox(
        state,
        backgroundContent = {
            val direction = state.dismissDirection
            val reached = state.targetValue != SwipeToDismissBoxValue.Settled
            val fill by androidx.compose.animation.animateColorAsState(
                if (reached) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "settle-fill",
            )
            val iconScale by androidx.compose.animation.core.animateFloatAsState(
                if (reached) 1.15f else 1f,
                label = "settle-icon",
            )
            Row(
                Modifier.fillMaxSize()
                    .background(fill, RoundedCornerShape(18.dp))
                    .padding(horizontal = 24.dp),
                horizontalArrangement =
                    if (direction == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start
                    else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (settled) Icons.Outlined.Unarchive else Icons.Outlined.Inventory2,
                    label,
                    Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
        modifier =
            modifier.testTag("settle-swipe").semantics {
                customActions = listOf(CustomAccessibilityAction(label) { onSettle(); true })
            },
        onDismiss = { onSettle() },
    ) {
        // Opaque, so the action colour only shows where the row has moved away.
        Box(Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))) {
            content()
        }
    }
}

private fun T3Thread.isWorking() =
    activeRunId != null || status in setOf("queued", "preparing", "starting", "running", "waiting")

private fun relativeThreadTime(value: String): String = runCatching {
    val minutes = Duration.between(Instant.parse(value), Instant.now()).toMinutes().coerceAtLeast(0)
    when {
        minutes < 1 -> "Now"
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h"
        else -> "${minutes / 1440}d"
    }
}
    .getOrDefault("")
