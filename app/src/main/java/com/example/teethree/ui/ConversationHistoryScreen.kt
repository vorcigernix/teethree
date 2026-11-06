package com.example.teethree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.ConversationCheckpoint
import com.example.teethree.data.ConversationHistory
import com.example.teethree.data.ConversationTools
import com.example.teethree.data.QueuedMessage
import com.example.teethree.data.T3Repository
import com.example.teethree.data.T3Thread
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import com.example.teethree.ui.adaptive.AdaptiveDialog as Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class HistoryPageState(
    val history: ConversationHistory?,
    val connected: Boolean,
    val canOperate: Boolean,
    val canRestore: Boolean,
    val loading: Boolean,
    val hasOlder: Boolean,
    val loadingOlder: Boolean,
    val error: String?,
)

private sealed interface HistoryConfirmation {
    data class Restore(val checkpoint: ConversationCheckpoint) : HistoryConfirmation

    data class Fork(val checkpoint: ConversationCheckpoint?) : HistoryConfirmation

    data class Cancel(val queued: QueuedMessage) : HistoryConfirmation

    data class Steer(val queued: QueuedMessage) : HistoryConfirmation
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationHistoryScreen(
    repository: T3Repository,
    thread: T3Thread,
    onBack: () -> Unit,
    onForked: (String) -> Unit,
) {
    val pageFlow =
        remember(repository, thread.id, thread.modelSelection.instanceId) {
            repository.state
                .map { state ->
                    val connected = state.connectionStatus == ConnectionStatus.Connected
                    HistoryPageState(
                        state.history?.takeIf { it.threadId == thread.id },
                        connected,
                        connected && "orchestration:operate" in state.scopes,
                        state.providers
                            .firstOrNull { it.instanceId == thread.modelSelection.instanceId }
                            ?.supportsConversationRollback == true,
                        state.threadLoading,
                        state.hasOlderMessages,
                        state.historyLoading,
                        state.error,
                    )
                }
                .distinctUntilChanged()
        }
    val page by
        pageFlow.collectAsStateWithLifecycle(
            initialValue = HistoryPageState(null, false, false, false, true, false, false, null)
        )
    val tools = remember(repository) { ConversationTools(repository) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<HistoryConfirmation?>(null) }
    var editing by remember { mutableStateOf<QueuedMessage?>(null) }
    var diff by remember { mutableStateOf<String?>(null) }
    val canWrite = page.canOperate && !busy && !thread.archived
    fun perform(write: Boolean = true, operation: suspend () -> Unit) {
        if (busy || !page.connected || (write && !canWrite)) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                operation()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "The operation could not be completed."
            } finally {
                busy = false
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History & queue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to conversation")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(thread.title, style = MaterialTheme.typography.titleLarge)
                if (!page.connected)
                    Text(
                        "Connect to this environment to make changes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (busy || page.loading)
                    Text(
                        if (page.loading) "Loading saved history…" else "Working…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                (error ?: page.history?.restoreFailure ?: page.error)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { perform(write = false) { diff = tools.fullDiff(thread.id) } },
                        enabled = page.connected && !busy && page.history != null,
                    ) {
                        Text("Review saved changes")
                    }
                    TextButton(
                        onClick = { confirmation = HistoryConfirmation.Fork(null) },
                        enabled = canWrite && page.history != null,
                    ) {
                        Text("Fork latest")
                    }
                }
                HorizontalDivider()
                Text(
                    "Queued messages",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (page.history?.queue?.any { it.held } == true)
                    TextButton(
                        onClick = {
                            perform {
                                tools.resumeQueue(thread.id)
                                notice = "Queue resumed."
                            }
                        },
                        enabled = canWrite,
                    ) {
                        Text("Resume queue")
                    }
                if (page.history?.queue.isNullOrEmpty() && !page.loading)
                    Text("No queued messages.", style = MaterialTheme.typography.bodyMedium)
            }
            itemsIndexed(
                page.history?.queue.orEmpty(),
                key = { _, queued -> "queue:${queued.runId}" },
            ) { index, queued ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${index + 1}. ${if (queued.held) "Paused" else "Queued"}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            queued.text.ifBlank {
                                if (queued.attachmentCount > 0)
                                    "${queued.attachmentCount} attachments"
                                else "Automatic message"
                            },
                            maxLines = 5,
                        )
                        if (queued.attachmentCount > 0 && queued.text.isNotBlank())
                            Text(
                                "${queued.attachmentCount} attachments",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        if (queued.editable) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editing = queued }, enabled = canWrite) {
                                    Text("Edit")
                                }
                                TextButton(
                                    onClick = { confirmation = HistoryConfirmation.Cancel(queued) },
                                    enabled = canWrite,
                                ) {
                                    Text("Remove")
                                }
                                TextButton(
                                    onClick = {
                                        val previous = page.history?.queue?.getOrNull(index - 1)
                                        if (previous != null)
                                            perform {
                                                tools.moveQueued(
                                                    thread.id,
                                                    queued.runId,
                                                    previous.runId,
                                                )
                                            }
                                    },
                                    enabled =
                                        canWrite &&
                                            index > 0 &&
                                            page.history?.queue?.getOrNull(index - 1)?.editable ==
                                                true,
                                ) {
                                    Text("Move up")
                                }
                            }
                            if (page.history?.activeRunId == null)
                                Button(
                                    onClick = {
                                        perform {
                                            tools.startQueued(thread.id, queued.runId)
                                            notice = "Message released to the agent."
                                        }
                                    },
                                    enabled = canWrite,
                                ) {
                                    Text("Send now")
                                }
                            else
                                TextButton(
                                    onClick = { confirmation = HistoryConfirmation.Steer(queued) },
                                    enabled = canWrite,
                                ) {
                                    Text("Steer running agent")
                                }
                        } else
                            Text(
                                "This automatic message is managed by the agent.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                }
            }
            item {
                HorizontalDivider()
                Text(
                    "Checkpoints",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (!page.canRestore)
                    Text(
                        "Conversation restore is not available for this provider.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                if (page.history?.checkpoints.isNullOrEmpty() && !page.loading)
                    Text(
                        "No checkpoints in the loaded history.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
            }
            items(page.history?.checkpoints.orEmpty(), key = { "checkpoint:${it.id}" }) { checkpoint
                ->
                CheckpointCard(
                    checkpoint,
                    canWrite,
                    canRestore = canWrite && page.canRestore && checkpoint.canRequestRestore,
                    canRead = page.connected && !busy,
                    onRestore = { confirmation = HistoryConfirmation.Restore(checkpoint) },
                    onFork = { confirmation = HistoryConfirmation.Fork(checkpoint) },
                    onDiff = {
                        perform(write = false) {
                            diff = tools.turnDiff(thread.id, checkNotNull(checkpoint.turnCount))
                        }
                    },
                )
            }
            if (page.hasOlder)
                item {
                    Button(
                        onClick = { perform(write = false) { repository.loadOlder() } },
                        enabled = page.connected && !busy && !page.loadingOlder,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (page.loadingOlder) "Loading earlier history…"
                            else "Load earlier checkpoints and messages"
                        )
                    }
                }
            item {
                HorizontalDivider()
                Text(
                    "Loaded turns",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            items(page.history?.runs.orEmpty().asReversed(), key = { "run:${it.id}" }) { run ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Turn ${run.ordinal} · ${run.status.replace('_', ' ')}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (run.prompt.isNotBlank())
                        Text(run.prompt, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    when (val pending = confirmation) {
        is HistoryConfirmation.Restore ->
            RestoreCheckpointDialog(
                onDismiss = { confirmation = null },
                onConfirm = { files ->
                    confirmation = null
                    perform {
                        tools.restore(thread.id, pending.checkpoint.id, files)
                        notice = "Restore requested. The conversation will update when it finishes."
                    }
                },
            )
        is HistoryConfirmation.Fork ->
            ConfirmHistoryAction(
                "Fork conversation?",
                if (pending.checkpoint == null)
                    "Create a new conversation from the latest stable point?"
                else "Create a new conversation from this checkpoint?",
                "Create fork",
                { confirmation = null },
            ) {
                confirmation = null
                perform { onForked(tools.fork(thread.id, pending.checkpoint?.id)) }
            }
        is HistoryConfirmation.Cancel ->
            ConfirmHistoryAction(
                "Remove queued message?",
                "This message will not be sent to the agent.",
                "Remove",
                { confirmation = null },
            ) {
                confirmation = null
                perform { tools.cancelQueued(thread.id, pending.queued.runId) }
            }
        is HistoryConfirmation.Steer ->
            ConfirmHistoryAction(
                "Steer the running agent?",
                "Send this queued message now. This may redirect or restart the current turn.",
                "Send now",
                { confirmation = null },
            ) {
                confirmation = null
                perform { tools.steerQueued(thread.id, pending.queued.runId) }
            }
        null -> Unit
    }
    editing?.let { queued ->
        EditQueuedMessageDialog(queued, onDismiss = { editing = null }) { text ->
            editing = null
            perform { tools.editQueued(thread.id, queued.runId, text) }
        }
    }
    diff?.let { patch -> SavedDiffDialog(patch, onDismiss = { diff = null }) }
}

@Composable
private fun CheckpointCard(
    checkpoint: ConversationCheckpoint,
    canWrite: Boolean,
    canRestore: Boolean,
    canRead: Boolean,
    onRestore: () -> Unit,
    onFork: () -> Unit,
    onDiff: () -> Unit,
) {
    var expanded by remember(checkpoint.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                checkpoint.turnCount?.let { "After turn $it" } ?: "Saved checkpoint",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "${checkpoint.fileCount} changed files · ${checkpoint.status ?: "availability checked when restoring"}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (checkpoint.capturedAt.isNotBlank())
                Text(checkpoint.capturedAt, style = MaterialTheme.typography.labelSmall)
            val files = if (expanded) checkpoint.files else checkpoint.files.take(4)
            files.forEach { file ->
                Text(
                    "${file.path}  +${file.additions} −${file.deletions}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (checkpoint.files.size > 4)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show fewer files" else "Show loaded files")
                }
            if (checkpoint.fileCount > checkpoint.files.size)
                Text(
                    "${checkpoint.files.size} of ${checkpoint.fileCount} files shown. Open the diff for the full saved changes.",
                    style = MaterialTheme.typography.bodySmall,
                )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (checkpoint.turnCount != null && checkpoint.turnCount > 0)
                    TextButton(onClick = onDiff, enabled = canRead) { Text("Review diff") }
                TextButton(onClick = onFork, enabled = canWrite && checkpoint.canRequestRestore) {
                    Text("Fork")
                }
                TextButton(onClick = onRestore, enabled = canRestore) { Text("Restore") }
            }
        }
    }
}

@Composable
private fun RestoreCheckpointDialog(onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var files by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore this checkpoint?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Later conversation history will be rolled back. Restoring files replaces workspace contents and may discard uncommitted work."
                )
                Row {
                    Checkbox(checked = files, onCheckedChange = { files = it })
                    Text("Restore workspace files", Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(files) }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmHistoryAction(
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditQueuedMessageDialog(
    queued: QueuedMessage,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(queued.runId) { mutableStateOf(queued.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit queued message") },
        text = {
            Column {
                OutlinedTextField(
                    text,
                    { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 10,
                )
                if (queued.attachmentCount > 0)
                    Text(
                        "${queued.attachmentCount} attachments",
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled =
                    (text.isNotBlank() || queued.attachmentCount > 0) && text.length <= 120_000,
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedDiffDialog(patch: String, onDismiss: () -> Unit) {
    val lines = remember(patch) { patch.lines() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Saved changes") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close diff")
                            }
                        },
                    )
                }
            ) { padding ->
                SelectionContainer(Modifier.padding(padding)) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    ) {
                        if (patch.isBlank()) item { Text("No file changes in this saved range.") }
                        else
                            items(lines) { line ->
                                Text(
                                    line.ifEmpty { " " },
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        when {
                                            line.startsWith("+") && !line.startsWith("+++") ->
                                                MaterialTheme.colorScheme.tertiary
                                            line.startsWith("-") && !line.startsWith("---") ->
                                                MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                )
                            }
                    }
                }
            }
        }
    }
}
