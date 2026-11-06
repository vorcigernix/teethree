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
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var projectGroupKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showSnoozed by rememberSaveable { mutableStateOf(false) }
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
    val statusSections =
        listOf(
            "Needs you" to visibleThreads.filter { it.thread.needsAttention },
            "Running" to
                visibleThreads.filter { !it.thread.needsAttention && it.thread.isWorking() },
            "Earlier" to
                visibleThreads.filter { !it.thread.needsAttention && !it.thread.isWorking() },
        )
    val sections = statusSections
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
                    item(key = "section:$heading") {
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
                            fresh = row.thread.id in freshIds,
                        )
                    }
                }
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
