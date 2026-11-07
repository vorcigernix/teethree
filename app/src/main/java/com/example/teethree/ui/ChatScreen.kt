package com.example.teethree.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.teethree.data.ApprovalOption
import com.example.teethree.data.ChatAttachment
import com.example.teethree.data.ComposerDraft
import com.example.teethree.data.RuntimeRequest
import com.example.teethree.data.T3Thread
import com.example.teethree.data.ThreadSearchJump
import com.example.teethree.data.TimelineItem
import com.example.teethree.data.composerEditableText
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    thread: T3Thread,
    timeline: List<TimelineItem>,
    pendingRequests: List<RuntimeRequest>,
    loading: Boolean,
    hasOlderMessages: Boolean,
    historyLoading: Boolean,
    historyRevision: Long,
    draft: ComposerDraft,
    busy: Boolean,
    busyLabel: String?,
    canOperate: Boolean,
    connected: Boolean,
    error: String?,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onCancelTransfer: () -> Unit,
    onAttach: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onTools: () -> Unit,
    onSettings: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onArchive: () -> Unit,
    onRespond: (String, String) -> Unit,
    onAnswer: (String, Map<String, List<String>>) -> Unit,
    onReconnect: () -> Unit,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    onTerminal: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    onBrowserPreview: (() -> Unit)? = null,
    onDevicePreview: (() -> Unit)? = null,
    onOpenMcpApp: ((TimelineItem) -> Unit)? = null,
    renderMcpApp: (@Composable (TimelineItem) -> Unit)? = null,
    embedded: Boolean = false,
    onSearchHistory: (() -> Unit)? = null,
    searchJump: ThreadSearchJump? = null,
    onSearchJumpFinished: (String, String?) -> Unit = { _, _ -> },
    onLoadThrough: (String) -> Unit = { onLoadOlder() },
    onAddContext: (() -> Unit)? = null,
    onRemoveContext: (String) -> Unit = {},
    enterBehavior: String = "send",
    onCopyContext: (() -> Unit)? = null,
    onPasteContext: (() -> Unit)? = null,
    /** True while the desktop is away but this connection may send: replies queue up. */
    canQueue: Boolean = false,
    /** Requests already answered here, waiting for the link to send the reply. */
    pendingReplies: Set<String> = emptySet(),
    projectLabel: String? = null,
    queued: List<ComposerDraft> = emptyList(),
    onEditQueued: (Int) -> Unit = {},
    /** Opens status, agents, snooze and goal details. */
    onStatus: (() -> Unit)? = null,
    /** Settles a finished chat, or brings a settled one back. */
    onToggleSettled: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val hosted = LocalDockHost.current != null
    val inputMode = LocalComposerInputMode.current
    val composerEditable =
        !thread.archived && !com.example.teethree.data.providerManagedChild(thread)
    val conversation =
        rememberVoiceConversation(
            thread,
            timeline,
            pendingRequests,
            draft,
            editable = composerEditable,
            canAnswer = (canOperate || canQueue) && !busy,
            offlineHost = if (connected) null else LocalLink.current?.info?.host ?: "the desktop",
            onRespond = onRespond,
            onSend = onSend,
            onDraftChange = onDraftChange,
        )
    val voice = conversation.voice
    val handsFree = conversation.handsFree
    val approval = conversation.approval
    val speakingId = conversation.speakingId
    val handleVoice = conversation.handle
    val canvasColor =
        if (embedded) MaterialTheme.colorScheme.surfaceContainerLowest
        else MaterialTheme.colorScheme.surface
    Scaffold(
        // In a shared dock host the dock itself rises with the keyboard and the timeline pads by it.
        modifier = if (hosted) modifier else modifier.imePadding(),
        containerColor = canvasColor,
        topBar = {
            TopAppBar(
                colors =
                    androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = canvasColor
                    ),
                title = {
                    Column {
                        Text(
                            thread.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // While connecting, the status takes the subtitle's place; no extra height.
                        ConnectionStatusLine {
                            Text(
                                thread.modelSelection.model,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to conversations")
                    }
                },
                actions = {
                    if (composerEditable) HandsFreeToggle(handsFree, conversation.setHandsFree)
                    IconButton(onClick = onTools) {
                        Icon(Icons.Default.Code, "Files, Git, and pull requests")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, "More conversation actions")
                        }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            onSearchHistory?.let { open ->
                                DropdownMenuItem(
                                    text = { Text("Find in chat") },
                                    onClick = {
                                        menuOpen = false
                                        open()
                                    },
                                )
                            }
                            onBrowserPreview?.let { open ->
                                DropdownMenuItem(
                                    text = { Text("Browser preview") },
                                    onClick = {
                                        menuOpen = false
                                        open()
                                    },
                                )
                            }
                            onDevicePreview?.let { open ->
                                DropdownMenuItem(
                                    text = { Text("Device preview") },
                                    onClick = {
                                        menuOpen = false
                                        open()
                                    },
                                )
                            }
                            if (onTerminal != null)
                                DropdownMenuItem(
                                    text = { Text("Terminal") },
                                    leadingIcon = { Icon(Icons.Default.Terminal, null) },
                                    onClick = {
                                        menuOpen = false
                                        onTerminal()
                                    },
                                )
                            if (onHistory != null)
                                DropdownMenuItem(
                                    text = { Text("History, checkpoints, and queue") },
                                    onClick = {
                                        menuOpen = false
                                        onHistory()
                                    },
                                )
                            onStatus?.let { open ->
                                DropdownMenuItem(
                                    text = { Text("Status and agents") },
                                    onClick = {
                                        menuOpen = false
                                        open()
                                    },
                                )
                            }
                            onToggleSettled?.let { toggle ->
                                DropdownMenuItem(
                                    text = {
                                        Text(if (thread.settled) "Bring back from Settled" else "Settle")
                                    },
                                    onClick = {
                                        menuOpen = false
                                        toggle()
                                    },
                                    enabled = canOperate && !thread.isRunningForSettle(),
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Conversation settings") },
                                onClick = {
                                    menuOpen = false
                                    onSettings()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (thread.archived) "Restore conversation"
                                        else "Archive conversation"
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onArchive()
                                },
                                enabled = canOperate && !busy,
                            )
                        }
                    }
                },
                expandedHeight = 56.dp,
            )
        },
    ) { padding ->
        val link = LocalLink.current
        val host = link?.info?.host ?: "the desktop"
        val canSend = canOperate || canQueue
        // Typing an answer into a question card: the composer and voice controls step aside so
        // the keyboard and the card share the screen. The empty dock still carries the IME inset.
        var answeringInCard by remember { mutableStateOf(false) }
        val dock: @Composable () -> Unit = {
            if (answeringInCard) Spacer(Modifier.fillMaxWidth())
            else Column(Modifier.fillMaxWidth()) {
                if (thread.activeRunId != null) RunningPill(canOperate, onStop)
                TaskComposer(
                    draft = draft,
                    onDraftChange = onDraftChange,
                    onSubmit = onSend,
                    onAttach = onAttach,
                    onRemoveAttachment = onRemoveAttachment,
                    editable = composerEditable,
                    actionsEnabled = canOperate && !busy && composerEditable,
                    voiceEnabled = canSend && !busy && composerEditable,
                    attachmentRemovalEnabled =
                        !busy && !com.example.teethree.data.providerManagedChild(thread),
                    submitEnabled =
                        canSend &&
                            composerEditable &&
                            !busy &&
                            (draft.text.isNotBlank() || draft.attachments.isNotEmpty()),
                    busyLabel = busyLabel,
                    onCancelTransfer = onCancelTransfer,
                    modelLabel = thread.modelSelection.model,
                    // Same chips as the new-task dock, so the dock reads as one control.
                    projectLabel = projectLabel,
                    modeLabel =
                        when (thread.runtimeMode) {
                            "approval-required" -> "Ask first"
                            "auto-accept-edits" -> "Auto-edit"
                            "auto" -> "Automatic"
                            "full-access" -> "Full access"
                            else -> thread.runtimeMode
                        },
                    onModelOptions = onSettings,
                    onModeOptions = onSettings,
                    placeholder =
                        if (com.example.teethree.data.providerManagedChild(thread))
                            "Reply in the parent conversation"
                        else if (thread.archived) "Archived conversation"
                        else "Reply, or steer the agent…",
                    submitDescription =
                        if (!connected) "Queue message"
                        else if (thread.activeRunId != null) "Queue message"
                        else "Send message",
                    onAddContext = onAddContext,
                    onRemoveContext = onRemoveContext,
                    enterBehavior = enterBehavior,
                    onCopyContext = onCopyContext,
                    onPasteContext = onPasteContext,
                    voice = voice,
                    onVoiceResult = handleVoice,
                    voiceHint =
                        when {
                            approval?.options?.any { it.decision == "accept" } == true ->
                                "Tap to reply  ·  hold to talk  ·  or just say “allow”"
                            else -> "Tap to talk  ·  hold for push-to-talk"
                        },
                    voiceTarget =
                        when {
                            !connected -> "queued reply"
                            approval != null -> "answering approval"
                            thread.activeRunId != null -> "steering current run"
                            else -> "reply"
                        },
                    compactListening = handsFree,
                    compactListeningLabel =
                        if (approval != null && connected) "Listening for your answer…"
                        else "Listening…",
                )
                if (!canOperate && connected)
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp),
                    ) {
                        Text(
                            "This connection has read-only access.",
                            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
            }
        }
        FloatingDockLayout(
            com.example.teethree.ui.adaptive.DockSlots.Detail,
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            dock = dock,
        ) { dockPadding ->
            Column(Modifier.fillMaxSize()) {
                if (error != null && (link == null || (connected && link.phase == LinkPhase.Online)) ||
                    (link == null && !connected)
                ) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                error ?: "Disconnected. Your draft is saved.",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = onReconnect) { Text("Reconnect") }
                        }
                    }
                }
                ChatTimeline(
                    timeline,
                    // While a capture runs the conversation recedes so the transcript leads.
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .alpha(if (voice.listening && !handsFree) 0.45f else 1f),
                    speakingItemId = speakingId,
                    onSkipSpeaking = conversation.skipSpeaking,
                    collapseFinishedSteps = handsFree,
                    loading = loading,
                    hasOlderMessages = hasOlderMessages,
                    historyLoading = historyLoading,
                    historyRevision = historyRevision,
                    historyFailed = error != null && connected,
                    onLoadOlder = onLoadOlder,
                    onOpenAttachment = onOpenAttachment,
                    onOpenMcpApp = onOpenMcpApp,
                    renderMcpApp = renderMcpApp,
                    searchJump = searchJump?.takeIf { it.threadId == thread.id },
                    onSearchJumpFinished = onSearchJumpFinished,
                    onLoadThrough = onLoadThrough,
                    bottomPadding = dockPadding + 12.dp,
                    footer =
                        if (pendingRequests.isNotEmpty() || queued.isNotEmpty())
                            ({
                                Column(
                                    Modifier.fillMaxWidth()
                                        .padding(
                                            top = if (pendingRequests.isEmpty()) 0.dp else TimelineBlockGap
                                        ),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    pendingRequests.forEach { request ->
                                        key(request.id) {
                                            // Usable while connecting too; the reply waits for the link.
                                            RequestCard(
                                                request,
                                                (canOperate || canQueue) &&
                                                    !busy &&
                                                    request.canRespond &&
                                                    request.id !in pendingReplies,
                                                onRespond,
                                                onAnswer,
                                                voiceHints = inputMode.voice && composerEditable,
                                                spokenLabels = handsFree,
                                                onAnswerFocus = { answeringInCard = it },
                                                replyQueued = request.id in pendingReplies,
                                            )
                                        }
                                    }
                                    if (queued.isNotEmpty()) QueuedTail(queued, onEditQueued)
                                }
                            })
                        else null,
                )
            }
            // Over the top of the conversation, so it never pushes anything down.
            WaitingProgressLine(Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp))
        }
    }
}

/** Messages written while connecting; they go out on their own once connected. */
@Composable
private fun QueuedTail(queued: List<ComposerDraft>, onEdit: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = TimelineBlockGap)) {
        queued.forEachIndexed { index, message -> QueuedBubble(message, onEdit = { onEdit(index) }) }
    }
}

@Composable
private fun QueuedBubble(message: ComposerDraft, onEdit: () -> Unit) {
    val text = composerEditableText(message.text, message.context)
    val spokenMs = remember(message.text) { SpokenMessages.key(message.text) }.let(SpokenMessages::duration)
    val sending = message.pendingDispatch != null
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).testTag("queued-message"),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            onClick = onEdit,
            enabled = !sending,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape =
                if (spokenMs != null) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
                else RoundedCornerShape(20.dp),
            modifier = Modifier.padding(start = 32.dp).widthIn(max = 840.dp),
        ) {
            Column {
                if (spokenMs != null) SpokenMeta(spokenMs)
                Text(
                    text.ifBlank { message.attachments.joinToString { it.name } },
                    Modifier.padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = if (spokenMs != null) 4.dp else 12.dp,
                        bottom = 12.dp,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Row(
            Modifier.padding(top = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Outlined.Schedule,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (sending) "Sending…" else "Sends when connected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunningPill(canStop: Boolean, onStop: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier.padding(start = 12.dp, top = 8.dp).glass(CircleShape, shadow = false),
    ) {
        Row(
            Modifier.height(40.dp).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                Modifier.size(16.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
            Text(
                "Agent running",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                onClick = onStop,
                enabled = canStop,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    Modifier.height(32.dp).padding(start = 8.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Default.Stop, "Stop agent", Modifier.size(18.dp))
                    Text(
                        "Stop",
                        style =
                            MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChatTimeline(
    timeline: List<TimelineItem>,
    modifier: Modifier = Modifier,
    list: LazyListState = rememberLazyListState(),
    loading: Boolean = false,
    hasOlderMessages: Boolean = false,
    historyLoading: Boolean = false,
    historyRevision: Long = 0,
    historyFailed: Boolean = false,
    onLoadOlder: () -> Unit = {},
    onOpenAttachment: (ChatAttachment) -> Unit = {},
    onOpenMcpApp: ((TimelineItem) -> Unit)? = null,
    renderMcpApp: (@Composable (TimelineItem) -> Unit)? = null,
    searchJump: ThreadSearchJump? = null,
    onSearchJumpFinished: (String, String?) -> Unit = { _, _ -> },
    onLoadThrough: (String) -> Unit = { onLoadOlder() },
    speakingItemId: String? = null,
    onSkipSpeaking: () -> Unit = {},
    collapseFinishedSteps: Boolean = false,
    bottomPadding: Dp = 12.dp,
    footer: (@Composable () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var following by rememberSaveable { mutableStateOf(true) }
    var savedAnchor by rememberSaveable { mutableStateOf<String?>(null) }
    var savedOffset by rememberSaveable { mutableStateOf(0) }
    var restoring by remember { mutableStateOf(!following && savedAnchor != null) }
    var attemptedRevision by remember { mutableStateOf<Long?>(null) }
    var restoredPages by remember { mutableStateOf(0) }
    var finishedJumpId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedEntry by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedQuery by rememberSaveable { mutableStateOf("") }
    var focusedOccurrence by rememberSaveable { mutableStateOf(0) }
    var jumpPlacement by remember(searchJump?.id) { mutableStateOf<Pair<String, Float?>?>(null) }
    var jumpHistoryRevision by remember(searchJump?.id) { mutableStateOf<Long?>(null) }
    var materializedJumpId by remember { mutableStateOf<String?>(null) }
    val pendingJump = searchJump?.takeIf { it.id != finishedJumpId }
    val latestPendingJump = rememberUpdatedState(pendingJump)
    val atBottom by remember {
        derivedStateOf {
            list.layoutInfo.visibleItemsInfo.lastOrNull()?.let {
                // The bottom padding sits under the floating dock, so it doesn't count as visible.
                it.key == "chat-tail" &&
                    it.offset + it.size <=
                        list.layoutInfo.viewportEndOffset - list.layoutInfo.afterContentPadding
            } ?: true
        }
    }
    val newest = timeline.lastOrNull()
    val tailIndex =
        timeline.size + (if (hasOlderMessages) 1 else 0) + (if (footer != null) 1 else 0)
    val steps = remember(timeline) { activitySteps(timeline) }
    val summaries =
        remember(timeline, steps) {
            timeline.indices
                .groupBy { steps[it]?.segmentId }
                .filterKeys { it != null }
                .mapValues { (_, indices) -> activitySummary(indices.map(timeline::get)) }
        }
    val collapsedSegments = remember { mutableStateMapOf<String, Boolean>() }
    // A tap on a header wins; otherwise finished segments fold away in hands-free mode.
    val segmentCollapsed: (ActivityStep) -> Boolean = {
        collapsedSegments[it.segmentId] ?: (collapseFinishedSteps && it.working == 0)
    }

    DisposableEffect(list) {
        onDispose {
            if (!following && !restoring) {
                val first =
                    list.layoutInfo.visibleItemsInfo.firstOrNull {
                        it.index == list.firstVisibleItemIndex
                    }
                if (first != null && first.key != "load-older" && first.key != "chat-tail") {
                    savedAnchor = first.key as? String
                    savedOffset = list.firstVisibleItemScrollOffset
                }
            }
        }
    }

    // Only a scroll gesture changes follow mode; incoming items must not pull a reader to the
    // bottom.
    LaunchedEffect(list) {
        snapshotFlow {
            val layout = list.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull()
            Triple(
                list.isScrollInProgress,
                last?.key == "chat-tail" &&
                    last.offset + last.size <= layout.viewportEndOffset - layout.afterContentPadding,
                layout.totalItemsCount,
            )
        }
            .collect { (moving, atEnd, total) ->
                if (latestPendingJump.value != null) return@collect
                if (moving) {
                    following = atEnd
                    restoring = false
                } else if (!following && !restoring) {
                    val first =
                        list.layoutInfo.visibleItemsInfo.firstOrNull {
                            it.index == list.firstVisibleItemIndex
                        }
                    if (first != null && first.key != "load-older" && first.key != "chat-tail") {
                        savedAnchor = first.key as? String
                        savedOffset = list.firstVisibleItemScrollOffset
                    }
                } else if (following && !atEnd && total > 0) list.requestScrollToItem(total - 1)
            }
    }
    LaunchedEffect(newest, tailIndex, pendingJump?.id) {
        if (pendingJump == null && following && !list.isScrollInProgress && timeline.isNotEmpty())
            list.requestScrollToItem(tailIndex)
    }
    LaunchedEffect(
        searchJump?.id,
        timeline,
        loading,
        historyLoading,
        hasOlderMessages,
        historyRevision,
        historyFailed,
        jumpPlacement,
    ) {
        val jump = searchJump?.takeIf { it.id != finishedJumpId } ?: return@LaunchedEffect
        following = false
        restoring = false
        focusedEntry = jump.entryId
        focusedQuery = jump.query
        focusedOccurrence = jump.occurrence
        if (loading) return@LaunchedEffect
        val index = timeline.indexOfFirst { it.searchEntryId == jump.entryId }
        if (index >= 0) {
            val row = timeline[index]
            val placement = jumpPlacement?.takeIf { it.first == row.id }
            if (placement != null) {
                val offset = placement.second?.roundToInt()?.coerceAtLeast(0) ?: 0
                list.scrollToItem(index + if (hasOlderMessages) 1 else 0, offset)
                savedAnchor = row.id
                savedOffset = offset
                finishedJumpId = jump.id
                onSearchJumpFinished(
                    jump.id,
                    if (placement.second == null)
                        "Opened the matching message. Its exact occurrence uses content this renderer cannot highlight."
                    else null,
                )
            } else if (materializedJumpId != jump.id) {
                materializedJumpId = jump.id
                list.requestScrollToItem(index + if (hasOlderMessages) 1 else 0)
            }
        } else if (!historyLoading && (historyFailed || !hasOlderMessages)) {
            finishedJumpId = jump.id
            focusedEntry = null
            onSearchJumpFinished(
                jump.id,
                "The matching message is no longer available in this chat. Search again to refresh the results.",
            )
        } else if (!historyLoading && jumpHistoryRevision != historyRevision) {
            jumpHistoryRevision = historyRevision
            onLoadThrough(jump.entryId)
        }
    }
    LaunchedEffect(
        timeline,
        loading,
        hasOlderMessages,
        historyLoading,
        historyRevision,
        historyFailed,
        pendingJump?.id,
    ) {
        if (!restoring || loading || pendingJump != null) return@LaunchedEffect
        if (savedAnchor == "chat-footer") {
            list.requestScrollToItem(
                timeline.size + if (hasOlderMessages) 1 else 0,
                if (footer != null) savedOffset else 0,
            )
            restoring = false
            return@LaunchedEffect
        }
        val index = timeline.indexOfFirst { it.id == savedAnchor }
        if (index >= 0) {
            list.requestScrollToItem(index + if (hasOlderMessages) 1 else 0, savedOffset)
            restoring = false
        } else if (!historyLoading && hasOlderMessages && attemptedRevision != historyRevision) {
            if (restoredPages < 20) {
                attemptedRevision = historyRevision
                restoredPages += 1
                onLoadOlder()
            } else {
                restoring = false
            }
        } else if (!historyLoading && (!hasOlderMessages || historyFailed)) {
            restoring = false
        }
    }
    Box(modifier) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().testTag("chat-history"),
            contentPadding =
                PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding),
            // Spacing is applied per row so consecutive activity steps share one unbroken rail.
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (hasOlderMessages)
                item(key = "load-older", contentType = "history-control") {
                    TextButton(
                        onClick = onLoadOlder,
                        enabled = !historyLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (historyLoading) "Loading earlier messages…"
                            else "Load earlier messages"
                        )
                    }
                }
            itemsIndexed(timeline, key = { _, it -> it.id }, contentType = { _, it -> it.type }) {
                index,
                item ->
                val find =
                    if (focusedEntry != null && item.searchEntryId == focusedEntry)
                        MarkdownFind(focusedQuery, focusedOccurrence)
                    else null
                val step = steps[index]
                val topGap =
                    when {
                        index == 0 && !hasOlderMessages -> 0.dp
                        step != null && !step.first -> 0.dp
                        else -> TimelineBlockGap
                    }
                TimelineRow(
                    item,
                    onOpenAttachment,
                    onOpenMcpApp,
                    renderMcpApp,
                    find,
                    step = step,
                    segmentCollapsed = step != null && segmentCollapsed(step),
                    onToggleSegment = {
                        step?.let { collapsedSegments[it.segmentId] = !segmentCollapsed(it) }
                    },
                    segmentSummary = step?.let { summaries[it.segmentId] }.orEmpty(),
                    topGap = topGap,
                    readingAloud = item.id == speakingItemId,
                    onSkipReading = onSkipSpeaking,
                ) { top ->
                    jumpPlacement = item.id to top
                }
            }
            if (footer != null) item(key = "chat-footer", contentType = "footer") { footer() }
            item(key = "chat-tail", contentType = "history-anchor") {
                Spacer(Modifier.height(1.dp))
            }
        }
        if (timeline.isEmpty() && footer == null) {
            Text(
                if (loading) "Loading messages…" else "Messages will appear here.",
                Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (restoring)
            Surface(
                modifier = Modifier.align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    "Restoring reading position…",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        if (pendingJump != null)
            Surface(
                Modifier.align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Opening search result…",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(
                        onClick = {
                            finishedJumpId = pendingJump.id
                            focusedEntry = null
                            onSearchJumpFinished(pendingJump.id, null)
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            }
        if (!atBottom && timeline.isNotEmpty()) {
            // A small round ↓ just above the dock: with the chat showing through the glass, it
            // says there's more below.
            Surface(
                onClick = {
                    pendingJump?.let {
                        finishedJumpId = it.id
                        onSearchJumpFinished(it.id, null)
                    }
                    focusedEntry = null
                    scope.launch {
                        list.scrollToItem(tailIndex)
                        following = true
                        savedAnchor = null
                        restoring = false
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = MaterialTheme.colorScheme.primary,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier =
                    Modifier.align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = bottomPadding)
                        .dropShadow(
                            CircleShape,
                            androidx.compose.ui.graphics.shadow.Shadow(
                                radius = 8.dp,
                                color = Color(0xFF191C20).copy(alpha = 0.12f),
                                offset = androidx.compose.ui.unit.DpOffset(0.dp, 2.dp),
                            ),
                        )
                        .size(44.dp)
                        .testTag("jump-to-latest"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowDownward, "Jump to latest message", Modifier.size(20.dp))
                }
            }
        }
    }
}

private class TimelineFindPosition(private val onPosition: (Float?) -> Unit) {
    var row: LayoutCoordinates? = null
    var content: LayoutCoordinates? = null
    var top: Float? = null
    var ready = false

    fun report() {
        if (!ready) return
        val outer = row ?: return
        val inner = content ?: return
        if (outer.isAttached && inner.isAttached)
            onPosition(top?.let { outer.localPositionOf(inner, Offset.Zero).y + it })
    }
}

@Composable
private fun TimelineRow(
    item: TimelineItem,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onOpenMcpApp: ((TimelineItem) -> Unit)?,
    renderMcpApp: (@Composable (TimelineItem) -> Unit)?,
    find: MarkdownFind? = null,
    step: ActivityStep? = null,
    segmentCollapsed: Boolean = false,
    onToggleSegment: () -> Unit = {},
    topGap: Dp = 0.dp,
    readingAloud: Boolean = false,
    onSkipReading: () -> Unit = {},
    segmentSummary: List<String> = emptyList(),
    onFindPosition: (Float?) -> Unit = {},
) {
    val user = item.type == "user"
    val message = user || item.type == "assistant"
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val latestPosition = rememberUpdatedState(onFindPosition)
    val position = remember(item.id, find) { TimelineFindPosition { latestPosition.value(it) } }
    LaunchedEffect(item.id, item.text, find) {
        if (find != null && (item.text.isEmpty() || (!message && item.type != "plan")))
            latestPosition.value(null)
    }
    val findModifier =
        if (find == null) Modifier
        else
            Modifier.onGloballyPositioned {
                position.content = it
                position.report()
            }
    val reportPosition: (Float?) -> Unit = {
        position.top = it
        position.ready = true
        position.report()
    }
    Column(
        Modifier.fillMaxWidth()
            .testTag("message:${item.id}")
            .then(
                if (find == null) Modifier
                else
                    Modifier.onGloballyPositioned {
                        position.row = it
                        position.report()
                    }
            )
            .padding(top = topGap),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
    ) {
        if (message) {
            if (readingAloud) ReadingAloudRow(onSkipReading)
            val spokenKey = remember(user, item.text) { if (user) SpokenMessages.key(item.text) else null }
            val spokenMs = spokenKey?.let(SpokenMessages::duration)
            Surface(
                color =
                    if (user) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                shape =
                    if (spokenMs != null) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
                    else if (user) RoundedCornerShape(20.dp)
                    else RectangleShape,
                modifier =
                    Modifier.padding(start = if (user) 32.dp else 0.dp).widthIn(max = 840.dp),
            ) {
                Column {
                    if (spokenMs != null) SpokenMeta(spokenMs)
                    if (item.text.isNotEmpty() || item.streaming)
                        MarkdownMessage(
                            (if (user && item.context != null)
                                    composerEditableText(item.text, item.context)
                                else item.text)
                                .ifEmpty { if (item.streaming) "Working…" else "" },
                            Modifier.padding(
                                    start = if (user) 12.dp else 0.dp,
                                    end = if (user) 12.dp else 0.dp,
                                    top = if (spokenMs != null) 4.dp else if (user) 12.dp else 0.dp,
                                    bottom = if (user) 12.dp else 0.dp,
                                )
                                .then(findModifier),
                            find = find,
                            onFindPosition = reportPosition,
                        )
                }
            }
            ComposerContextStrip(item.context, enabled = false, onRemove = null)
            if (item.attachments.isNotEmpty())
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(item.attachments, key = { it.id }) { attachment ->
                        AssistChip(
                            onClick = { onOpenAttachment(attachment) },
                            label = { Text(attachment.name) },
                        )
                    }
                }
        } else {
            val segment = step ?: ActivityStep(item.id, first = true, count = 1, working = 0, failed = 0)
            if (segment.first && segment.count > 1)
                ActivitySegmentHeader(
                    segment,
                    segmentSummary,
                    collapsed = segmentCollapsed,
                    onToggle = onToggleSegment,
                )
            // A search hit inside a collapsed segment still reveals its own row.
            if (!segmentCollapsed || find != null)
                ActivityStepRow(
                    item,
                    expanded = expanded,
                    onToggleDetails = { expanded = !expanded },
                    find = find,
                    findModifier = findModifier,
                    reportPosition = reportPosition,
                )
        }
        if (item.mcpApp != null) {
            if (renderMcpApp != null) renderMcpApp(item)
            else if (onOpenMcpApp != null)
                TextButton(onClick = { onOpenMcpApp(item) }) {
                    Text("Open ${item.mcpApp.tool} app")
                }
        }
    }
}

/** Space between conversation blocks; steps inside one activity segment sit flush. */
private val TimelineBlockGap = 20.dp

/** Where a non-message timeline item sits inside its run of consecutive activity steps. */
@Immutable
internal data class ActivityStep(
    val segmentId: String,
    val first: Boolean,
    val count: Int,
    val working: Int,
    val failed: Int,
)

internal fun activitySteps(timeline: List<TimelineItem>): List<ActivityStep?> {
    val result = arrayOfNulls<ActivityStep>(timeline.size)
    var start = 0
    while (start < timeline.size) {
        if (timeline[start].isConversationMessage()) {
            start++
            continue
        }
        var end = start
        while (end < timeline.size && !timeline[end].isConversationMessage()) end++
        val run = timeline.subList(start, end)
        val working = run.count { it.stepState() == StepState.Working }
        val failed = run.count { it.stepState() == StepState.Failed }
        for (index in start until end)
            result[index] =
                ActivityStep(timeline[start].id, index == start, run.size, working, failed)
        start = end
    }
    return result.asList()
}

private fun TimelineItem.isConversationMessage() = type == "user" || type == "assistant"

internal enum class StepState {
    Working,
    Succeeded,
    Failed,
    Other,
}

internal fun TimelineItem.stepState(): StepState =
    when (status.lowercase()) {
        "completed",
        "success",
        "succeeded",
        "done" -> StepState.Succeeded
        "failed",
        "error" -> StepState.Failed
        "running",
        "in_progress",
        "inprogress",
        "started" -> StepState.Working
        else -> if (streaming) StepState.Working else StepState.Other
    }

private val StepVerbs =
    setOf(
        "Read",
        "Edit",
        "Create",
        "Write",
        "Delete",
        "Remove",
        "Run",
        "Search",
        "Grep",
        "Find",
        "Fetch",
        "List",
        "Open",
        "View",
        "Update",
        "Apply",
        "Patch",
        "Move",
        "Rename",
        "Browse",
        "Call",
    )

private val GenericCommandTitles = setOf("tool", "command", "terminal", "shell")

/** Splits a step into the bold verb and the monospaced target shown in the design. */
internal fun stepLabel(item: TimelineItem): Pair<String, String?> {
    val title = item.title?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    val detail = item.text.lineSequence().firstOrNull()?.trim().orEmpty()
    val head = title.substringBefore(' ')
    if (head in StepVerbs && title.length > head.length)
        return head to title.substring(head.length).trim()
    fun String.orNull() = takeIf(String::isNotBlank)
    return when (item.type) {
        // Some providers title every command "Tool"; the command itself leads the details.
        "command_execution" ->
            "Run" to
                (title.takeUnless { it.lowercase() in GenericCommandTitles }?.orNull()
                    ?: detail.orNull())
        "file_change" -> "Edit" to (detail.orNull() ?: title.orNull())
        "file_search" -> "Search" to (detail.orNull() ?: title.orNull())
        "web_search" -> "Search web" to (detail.orNull() ?: title.orNull())
        else ->
            (title.orNull()
                ?: item.type.replace('_', ' ').replace('-', ' ').replaceFirstChar {
                    it.uppercase()
                }) to null
    }
}

private fun stepIcon(type: String, verb: String): ImageVector =
    when {
        type == "command_execution" || verb == "Run" -> Icons.Outlined.Terminal
        verb == "Create" -> Icons.AutoMirrored.Outlined.NoteAdd
        type == "file_change" ||
            verb in setOf("Edit", "Write", "Update", "Apply", "Patch", "Move", "Rename") ->
            Icons.Outlined.EditNote
        type == "web_search" || verb == "Browse" || verb == "Fetch" -> Icons.Outlined.TravelExplore
        type == "file_search" || verb in setOf("Search", "Grep", "Find") -> Icons.Outlined.Search
        verb in setOf("Read", "View", "Open", "List") -> Icons.Outlined.Description
        type == "todo_list" -> Icons.Outlined.Checklist
        type == "plan" -> Icons.Outlined.Map
        type == "approval_request" -> Icons.Outlined.PanTool
        type == "user_input_request" -> Icons.AutoMirrored.Outlined.HelpOutline
        type == "reasoning" -> Icons.Outlined.Lightbulb
        else -> Icons.Outlined.Build
    }

/**
 * What a run of steps did, in the order it first did each kind of thing: "Explored 3 files",
 * "Edited 2 files", "Ran 1 command".
 */
internal fun activitySummary(run: List<TimelineItem>): List<String> {
    val counts = linkedMapOf<String, Int>()
    run.forEach { item ->
        val verb = stepLabel(item).first
        val kind =
            when {
                item.type == "command_execution" || verb == "Run" -> "ran"
                verb == "Create" -> "created"
                item.type == "file_change" ||
                    verb in setOf("Edit", "Write", "Update", "Apply", "Patch", "Move", "Rename") ->
                    "edited"
                item.type == "web_search" || verb in setOf("Search web", "Browse", "Fetch") ->
                    "browsed"
                item.type == "file_search" || verb in setOf("Search", "Grep", "Find") -> "searched"
                verb in setOf("Read", "View", "Open", "List") -> "explored"
                else -> "other"
            }
        counts[kind] = (counts[kind] ?: 0) + 1
    }
    fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
    fun times(n: Int) = if (n == 1) "once" else "$n times"
    return counts.map { (kind, n) ->
        when (kind) {
            "explored" -> "Explored ${count(n, "file")}"
            "edited" -> "Edited ${count(n, "file")}"
            "created" -> "Created ${count(n, "file")}"
            "ran" -> "Ran ${count(n, "command")}"
            "searched" -> "Searched ${times(n)}"
            "browsed" -> "Searched the web ${times(n)}"
            else -> count(n, "other step").replaceFirstChar { it.uppercase() }
        }
    }
}

/** "Explored 3 files · edited 2 files": one line for a whole segment. */
internal fun summaryLine(summary: List<String>) =
    summary
        .mapIndexed { index, part ->
            if (index == 0) part else part.replaceFirstChar { it.lowercase() }
        }
        .joinToString(" · ")

@Composable
private fun ActivitySegmentHeader(
    segment: ActivityStep,
    summary: List<String>,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    val label =
        listOfNotNull(
                "Working".takeIf { segment.working > 0 },
                summaryLine(summary).ifEmpty {
                    if (segment.count == 1) "1 step" else "${segment.count} steps"
                },
                "${segment.failed} failed".takeIf { segment.failed > 0 },
            )
            .joinToString(" · ")
    Row(
        Modifier.padding(bottom = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = if (collapsed) "Show steps" else "Hide steps",
                onClick = onToggle,
            )
            .padding(top = 2.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Collapsed, the segment's outcome leads, so the line reads on its own.
        if (collapsed)
            when {
                segment.working > 0 ->
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 1.5.dp,
                    )
                segment.failed > 0 ->
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                else ->
                    Icon(
                        Icons.Outlined.CheckCircle,
                        null,
                        Modifier.size(18.dp),
                        tint = T3Colors.success,
                    )
            }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
            null,
            Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActivityStepRow(
    item: TimelineItem,
    expanded: Boolean,
    onToggleDetails: () -> Unit,
    find: MarkdownFind?,
    findModifier: Modifier,
    reportPosition: (Float?) -> Unit,
) {
    val state = item.stepState()
    val (verb, target) = remember(item.type, item.title, item.text) { stepLabel(item) }
    val accent =
        if (state == StepState.Working) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    val rail = MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(rail, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (item.text.isNotBlank())
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = if (expanded) "Hide details" else "Show details",
                            onClick = onToggleDetails,
                        )
                    else Modifier
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(stepIcon(item.type, verb), null, Modifier.size(18.dp), tint = accent)
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    verb,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        if (state == StepState.Working) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (target != null)
                    Text(
                        target,
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = T3MonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
            }
            StepStatus(state, item.status)
        }
        if (find != null && item.type == "plan")
            MarkdownMessage(
                item.text,
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp).then(findModifier),
                find,
                reportPosition,
            )
        else if (expanded)
            SelectionContainer {
                Text(
                    item.text,
                    Modifier.padding(start = 10.dp, top = 2.dp, bottom = 6.dp)
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = T3MonoFontFamily,
                )
            }
    }
}

@Composable
private fun StepStatus(state: StepState, status: String) {
    val label = status.replace('_', ' ')
    when (state) {
        StepState.Succeeded ->
            Icon(Icons.Default.Check, label, Modifier.size(16.dp), tint = T3Colors.success)
        StepState.Failed ->
            Icon(
                Icons.Outlined.ErrorOutline,
                label,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        StepState.Working ->
            Box(
                Modifier.size(16.dp).semantics { contentDescription = label.ifBlank { "running" } },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    Modifier.size(13.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 1.5.dp,
                )
            }
        StepState.Other ->
            if (label.isNotBlank())
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
    }
}

@Composable
private fun RequestCard(
    request: RuntimeRequest,
    enabled: Boolean,
    onRespond: (String, String) -> Unit,
    onAnswer: (String, Map<String, List<String>>) -> Unit,
    voiceHints: Boolean = false,
    spokenLabels: Boolean = false,
    onAnswerFocus: (Boolean) -> Unit = {},
    replyQueued: Boolean = false,
) {
    var confirmation by remember { mutableStateOf<ApprovalOption?>(null) }
    // An answered card leaves while its field may still hold focus; bring the dock back.
    val latestFocus by rememberUpdatedState(onAnswerFocus)
    DisposableEffect(request.id) { onDispose { latestFocus(false) } }
    var answers by
        rememberSaveable(request.id) {
            mutableStateOf(
                request.questions.associate { question ->
                    question.id to listOfNotNull(question.initialAnswer?.takeIf(String::isNotBlank))
                }
            )
        }
    // Neutral surface for the large card; tertiary only as an accent, so a light
    // tertiary palette (e.g. yellow) never floods the chat or washes out text.
    val accent = MaterialTheme.colorScheme.tertiary
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        border = BorderStroke(1.dp, accent),
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (request.questions.isEmpty()) "Your approval is needed"
                else "Your agent has a question",
                style = MaterialTheme.typography.titleLarge,
            )
            if (request.prompt.isNotBlank())
                SelectionContainer {
                    Text(request.prompt, style = MaterialTheme.typography.bodyMedium)
                }
            request.questions.forEach { question ->
                Text(question.prompt, style = MaterialTheme.typography.labelLarge)
                question.options.forEach { option ->
                    FilterChip(
                        selected = option in answers[question.id].orEmpty(),
                        onClick = {
                            val previous = answers[question.id].orEmpty()
                            val next =
                                if (!question.multiple) listOf(option)
                                else if (option in previous) previous - option
                                else previous + option
                            answers = answers + (question.id to next)
                        },
                        label = { Text(option) },
                        enabled = enabled,
                    )
                }
                if (question.allowCustomAnswer) {
                    OutlinedTextField(
                        value =
                            answers[question.id]
                                .orEmpty()
                                .filterNot { it in question.options }
                                .joinToString("\n"),
                        onValueChange = { text ->
                            val selected =
                                if (question.multiple)
                                    answers[question.id].orEmpty().filter { it in question.options }
                                else emptyList()
                            answers =
                                answers +
                                    (question.id to
                                        (selected + listOfNotNull(text.takeIf(String::isNotBlank))))
                        },
                        label = { Text("Your answer") },
                        enabled = enabled,
                        modifier =
                            Modifier.fillMaxWidth().onFocusChanged { onAnswerFocus(it.isFocused) },
                    )
                }
            }
            if (request.questions.isNotEmpty())
                Button(
                    onClick = {
                        onAnswer(
                            request.id,
                            answers.filterValues { values -> values.any(String::isNotBlank) },
                        )
                    },
                    enabled =
                        enabled &&
                            request.questions
                                .filter { it.required }
                                .all { answers[it.id].orEmpty().any(String::isNotBlank) },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = accent,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                            disabledContainerColor =
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                ) {
                    Text("Send answers")
                }
            val choose: (ApprovalOption) -> Unit = { option ->
                if (option.warning != null) confirmation = option
                else onRespond(request.id, option.decision)
            }
            // Decline first, the one-time allow last and filled, as on the design's card.
            val options = request.options.sortedBy { approvalRank(it.decision) }
            val spoken =
                options.mapNotNull { option ->
                    approvalKeyword(option.decision)?.takeIf { option.warning == null }
                }
            if (spokenLabels && options.size <= 3)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        SpokenOptionButton(
                            option.label,
                            approvalKeyword(option.decision)?.takeIf { option.warning == null },
                            primary = option.decision == "accept",
                            enabled = enabled,
                            onClick = { choose(option) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            else {
                if (voiceHints && spoken.isNotEmpty())
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Default.Mic, null, Modifier.size(16.dp))
                        Text(spokenChoices(spoken), style = MaterialTheme.typography.bodyMedium)
                    }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { option ->
                        when (option.decision) {
                            "accept" ->
                                Button(
                                    onClick = { choose(option) },
                                    enabled = enabled,
                                    colors =
                                        ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiary,
                                            contentColor = MaterialTheme.colorScheme.onTertiary,
                                        ),
                                ) {
                                    Text(option.label)
                                }
                            "decline",
                            "cancel" ->
                                TextButton(
                                    onClick = { choose(option) },
                                    enabled = enabled,
                                    colors =
                                        ButtonDefaults.textButtonColors(
                                            contentColor = MaterialTheme.colorScheme.tertiary
                                        ),
                                ) {
                                    Text(option.label)
                                }
                            else ->
                                OutlinedButton(
                                    onClick = { choose(option) },
                                    enabled = enabled,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
                                    colors =
                                        ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.tertiary
                                        ),
                                ) {
                                    Text(option.label)
                                }
                        }
                    }
                }
            }
            if (replyQueued)
                Row(
                    Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        null,
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Sends when connected",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
    }
    confirmation?.let { option ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(option.label) },
            text = { Text(option.warning.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        onRespond(request.id, option.decision)
                    },
                    enabled = enabled,
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

private fun approvalRank(decision: String) =
    when (decision) {
        "decline" -> 0
        "cancel" -> 1
        "acceptForSession",
        "acceptAlways" -> 2
        "accept" -> 3
        else -> 4
    }

/** 'Say “allow”, “always” or “no”'. */
internal fun spokenChoices(words: List<String>): String {
    val quoted = words.reversed().map { "“$it”" }
    return "Say " +
        if (quoted.size == 1) quoted[0]
        else quoted.dropLast(1).joinToString(", ") + " or " + quoted.last()
}

/** An approval choice that also shows the word to say for it (hands-free mode). */
@Composable
private fun SpokenOptionButton(
    label: String,
    keyword: String?,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val content = if (primary) colors.onTertiary else colors.tertiary
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        color = if (primary) colors.tertiary else Color.Transparent,
        contentColor = content,
        border = if (primary) null else BorderStroke(1.dp, colors.tertiary),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (keyword != null)
                Text(
                    "say “$keyword”",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Normal,
                    color = content.copy(alpha = if (primary) 0.8f else 1f),
                )
        }
    }
}

/** Off: a headphones icon. On: the labeled "Hands-free" pill from the design. */
@Composable
internal fun HandsFreeToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    if (!on)
        IconButton(onClick = { onChange(true) }) {
            Icon(Icons.Outlined.Headphones, "Turn on hands-free")
        }
    else
        Surface(
            onClick = { onChange(false) },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier =
                Modifier.padding(horizontal = 4.dp).semantics {
                    contentDescription = "Hands-free on. Tap to turn off"
                },
        ) {
            Row(
                Modifier.height(36.dp).padding(start = 10.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.Headphones, null, Modifier.size(18.dp))
                Text(
                    "Hands-free",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
}

/** "Voice · 0:06" at the top of a message you spoke. */
@Composable
private fun SpokenMeta(durationMs: Long) {
    val seconds = durationMs / 1000
    Row(
        Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Default.Mic, null, Modifier.size(14.dp))
        Text(
            if (durationMs > 0) "Voice · %d:%02d".format(seconds / 60, seconds % 60) else "Voice",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun T3Thread.isRunningForSettle() =
    activeRunId != null || status in setOf("queued", "preparing", "starting", "running", "waiting")
