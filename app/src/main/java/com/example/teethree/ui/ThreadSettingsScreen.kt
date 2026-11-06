package com.example.teethree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.ModelSelection
import com.example.teethree.data.T3Provider
import com.example.teethree.data.T3Repository
import com.example.teethree.data.T3Thread
import com.example.teethree.data.ThreadControls
import com.example.teethree.data.providerManagedChild
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadSettingsScreen(
    repository: T3Repository,
    thread: T3Thread,
    providers: List<T3Provider>,
    onBack: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val controls = remember(repository) { ThreadControls(repository) }
    val scope = rememberCoroutineScope()
    var title by rememberSaveable(thread.id) { mutableStateOf(thread.title) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val canOperate =
        state.connectionStatus == ConnectionStatus.Connected &&
            "orchestration:operate" in state.scopes &&
            !busy
    val canEditProviderSettings = canOperate && !providerManagedChild(thread)
    val currentProvider = providers.find { it.instanceId == thread.modelSelection.instanceId }
    val currentModel = currentProvider?.models?.find { it.slug == thread.modelSelection.model }
    val modes =
        listOf(
            "approval-required" to "Ask for approval",
            "auto-accept-edits" to "Automatically accept edits",
            "auto" to "Automatic",
            "full-access" to "Full access",
        )
    fun action(block: suspend () -> Unit) {
        if (!canOperate) return
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "The request failed."
            } finally {
                busy = false
            }
        }
    }
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Conversation settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                title,
                { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                enabled = canOperate,
            )
            Button(
                onClick = { action { controls.rename(thread.id, title) } },
                enabled = canOperate && title.isNotBlank() && title != thread.title,
            ) {
                Text("Save title")
            }
            HorizontalDivider()
            ChoiceField(
                "Agent permissions",
                modes.find { it.first == thread.runtimeMode }?.second ?: thread.runtimeMode,
                if (canEditProviderSettings) modes else emptyList(),
            ) { value ->
                action { controls.setRuntimeMode(thread.id, value) }
            }
            ChoiceField(
                "Agent mode",
                if (thread.interactionMode == "plan") "Plan" else "Build",
                if (canEditProviderSettings) listOf("default" to "Build", "plan" to "Plan")
                else emptyList(),
            ) { value ->
                action { controls.setInteractionMode(thread.id, value) }
            }
            ChoiceField(
                "Provider",
                currentProvider?.displayName ?: thread.modelSelection.instanceId,
                if (canEditProviderSettings)
                    providers
                        .filter { it.available && it.models.isNotEmpty() }
                        .map { it.instanceId to it.displayName }
                else emptyList(),
            ) { id ->
                val provider = providers.first { it.instanceId == id }
                val model = provider.models.find { it.isDefault } ?: provider.models.first()
                action { controls.setModel(thread.id, ModelSelection(id, model.slug)) }
            }
            ChoiceField(
                "Model",
                currentModel?.name ?: thread.modelSelection.model,
                if (canEditProviderSettings)
                    currentProvider?.models.orEmpty().map { it.slug to it.name }
                else emptyList(),
            ) { slug ->
                action {
                    controls.setModel(
                        thread.id,
                        thread.modelSelection.copy(
                            model = slug,
                            options =
                                if (slug == thread.modelSelection.model)
                                    thread.modelSelection.options
                                else null,
                        ),
                    )
                }
            }
            currentModel?.let { model ->
                ModelOptionControls(
                    model,
                    thread.modelSelection.options,
                    canEditProviderSettings,
                ) { options ->
                    action {
                        controls.setModel(thread.id, thread.modelSelection.copy(options = options))
                    }
                }
            }
            thread.worktreePath?.let {
                Text("Worktree: $it", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            TextButton(
                onClick = { action { controls.setPinned(thread.id, !thread.pinned) } },
                enabled = canOperate,
            ) {
                Text(if (thread.pinned) "Unpin conversation" else "Pin conversation")
            }
            if (thread.snoozedUntil != null) {
                Text(
                    "Snoozed until ${thread.snoozedUntil}",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = { action { controls.unsnooze(thread.id) } },
                    enabled = canOperate,
                ) {
                    Text("Unsnooze conversation")
                }
            } else
                TextButton(
                    onClick = {
                        action {
                            controls.snooze(thread.id, Instant.now().plusSeconds(3600).toString())
                        }
                    },
                    enabled = canOperate,
                ) {
                    Text("Snooze for one hour")
                }
            TextButton(onClick = { confirmDelete = true }, enabled = canOperate) {
                Text("Delete conversation", color = MaterialTheme.colorScheme.error)
            }
            if (busy) Text("Saving…", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete conversation?") },
            text = { Text("The conversation and its history will be permanently removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        action {
                            controls.delete(thread.id)
                            repository.selectThread(null)
                            onBack()
                        }
                    },
                    enabled = canOperate,
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
}
