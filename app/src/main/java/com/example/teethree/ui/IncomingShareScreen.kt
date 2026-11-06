package com.example.teethree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.teethree.data.IncomingSharedDraft
import com.example.teethree.data.SavedConnection
import com.example.teethree.data.T3Project
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingShareScreen(
    draft: IncomingSharedDraft,
    connections: List<SavedConnection>,
    activeConnectionId: String?,
    projects: List<T3Project>,
    canImport: Boolean,
    busy: Boolean,
    error: String?,
    onDraftChange: (IncomingSharedDraft) -> Unit,
    onConnectionChange: (SavedConnection) -> Unit,
    onImport: (projectId: String) -> Unit,
    onClose: () -> Unit,
    onDiscard: () -> Unit,
) {
    var projectId by
        rememberSaveable(draft.id, activeConnectionId) { mutableStateOf<String?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val project = projects.find { it.id == projectId } ?: projects.firstOrNull()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shared with T3 Code") },
                navigationIcon = {
                    IconButton(onClick = onClose, enabled = !busy) {
                        Icon(Icons.Outlined.Close, "Keep share for later")
                    }
                },
                actions = {
                    TextButton(onClick = { confirmDiscard = true }, enabled = !busy) {
                        Text("Discard")
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                Text(
                    "Files are uploaded to the selected environment. The task starts only when you send it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { project?.let { onImport(it.id) } },
                    enabled =
                        canImport &&
                            !busy &&
                            project != null &&
                            (draft.text.isNotBlank() || draft.files.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Importing…" else "Use in new task")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChoiceField(
                "Environment",
                connections.find { it.id == activeConnectionId }?.baseUrl
                    ?: "Choose an environment",
                if (busy) emptyList() else connections.map { it.id to it.baseUrl },
            ) { id ->
                connections.find { it.id == id }?.let(onConnectionChange)
            }
            ChoiceField(
                "Project",
                project?.title ?: "No projects available",
                if (busy) emptyList() else projects.map { it.id to it.title },
            ) {
                projectId = it
            }
            OutlinedTextField(
                draft.text,
                { onDraftChange(draft.copy(text = it)) },
                enabled = !busy,
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 10,
            )
            draft.files.forEach { file ->
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name)
                        Text(
                            "${file.sizeBytes / 1024} KiB · ${file.mimeType}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    IconButton(
                        onClick = {
                            onDraftChange(
                                draft.copy(files = draft.files.filterNot { it.id == file.id })
                            )
                        },
                        enabled = !busy,
                    ) {
                        Icon(Icons.Outlined.Close, "Remove ${file.name}")
                    }
                }
            }
            draft.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (connections.isEmpty())
                Text("Add an environment in Settings, then return to this share.")
        }
    }
    if (confirmDiscard)
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this share?") },
            text = {
                Text(
                    "This removes only the copies staged in T3 Code. The originals in the sending app are kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDiscard()
                    }
                ) {
                    Text("Discard")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
}
