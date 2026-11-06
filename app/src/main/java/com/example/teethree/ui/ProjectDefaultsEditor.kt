package com.example.teethree.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.ProjectAction
import com.example.teethree.data.ProjectDefaultsTools
import com.example.teethree.data.T3Repository
import com.example.teethree.data.newId
import com.example.teethree.data.normalizeProviderOptions
import com.example.teethree.data.projectActionIcons
import com.example.teethree.data.projectDefaultModelOptions
import com.example.teethree.data.string
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProjectDefaultsEditor(repository: T3Repository, projectId: String?) {
    val state by repository.state.collectAsStateWithLifecycle()
    val tools = remember(repository) { ProjectDefaultsTools(repository) }
    val scope = rememberCoroutineScope()
    var busy by remember(projectId) { mutableStateOf(false) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    var editing by remember(projectId) { mutableStateOf<ProjectAction?>(null) }
    var deleting by remember(projectId) { mutableStateOf<ProjectAction?>(null) }
    val connected = state.connectionStatus == ConnectionStatus.Connected
    val canWrite = connected && "settings:write" in state.scopes && !busy
    val modelReason = tools.managedReason("defaultModelSelection", projectId)
    val scriptReason = tools.managedReason("defaultProjectScripts", projectId)
    val options = projectDefaultModelOptions(tools.config(), projectId)
    val selection = tools.currentModel(projectId)
    val currentIndex = options.indexOfFirst {
        it.instanceId == selection?.string("instanceId") && it.model == selection?.string("model")
    }
    fun action(block: suspend () -> Unit) {
        if (busy || !connected) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "The setting could not be saved."
            } finally {
                busy = false
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Preferred model", style = MaterialTheme.typography.titleMedium)
        Text(
            "Used when starting a new task. Provider options stay with the selected model.",
            style = MaterialTheme.typography.bodySmall,
        )
        modelReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        ChoiceField(
            "Model",
            options.getOrNull(currentIndex)?.label
                ?: if (selection == null) "No preferred model" else selection.string("model"),
            if (canWrite && modelReason == null)
                listOf("" to "No preferred model") +
                    options.mapIndexed { index, model -> index.toString() to model.label }
            else emptyList(),
        ) { key ->
            val selected = key.toIntOrNull()?.let(options::getOrNull)
            action { tools.model(projectId, selected?.instanceId, selected?.model) }
        }
        val provider =
            state.providers.firstOrNull { it.instanceId == selection?.string("instanceId") }
        val model = provider?.models?.firstOrNull { it.slug == selection?.string("model") }
        if (model != null)
            ModelOptionControls(
                model,
                normalizeProviderOptions(selection?.get("options")),
                canWrite && modelReason == null,
            ) { values ->
                action { tools.modelOptions(projectId, values) }
            }
        if (projectId != null)
            TextButton(
                onClick = { action { tools.inherit(projectId, "defaultModelSelection") } },
                enabled = canWrite && modelReason == null,
            ) {
                Text("Use environment model default")
            }
        Text("Project actions", style = MaterialTheme.typography.titleMedium)
        Text(
            "Commands run on the environment. A setup action runs when a worktree is created; a settle action runs when its thread settles.",
            style = MaterialTheme.typography.bodySmall,
        )
        scriptReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val actions = tools.actions(projectId)
        actions.forEachIndexed { index, item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    Text(item.command, style = MaterialTheme.typography.bodySmall)
                    val flags =
                        listOfNotNull(
                            if (item.setup)
                                if (item.waitForSetup) "Setup · wait for completion"
                                else "Setup · start agent in parallel"
                            else null,
                            if (item.onSettle) "On settle" else null,
                        )
                    if (flags.isNotEmpty())
                        Text(flags.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                    item.previewUrl?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(
                            onClick = { editing = item },
                            enabled = canWrite && scriptReason == null,
                        ) {
                            Text("Edit")
                        }
                        TextButton(
                            onClick = { action { tools.moveAction(projectId, item.id, -1) } },
                            enabled = canWrite && scriptReason == null && index > 0,
                        ) {
                            Text("Move up")
                        }
                        TextButton(
                            onClick = { action { tools.moveAction(projectId, item.id, 1) } },
                            enabled = canWrite && scriptReason == null && index < actions.lastIndex,
                        ) {
                            Text("Move down")
                        }
                        TextButton(
                            onClick = { deleting = item },
                            enabled = canWrite && scriptReason == null,
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
        if (actions.isEmpty())
            Text("No actions configured.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = { editing = ProjectAction(newId(), "", "") },
            enabled = canWrite && scriptReason == null,
        ) {
            Text("Add action")
        }
        if (projectId != null) {
            TextButton(
                onClick = { action { tools.inherit(projectId, "defaultProjectScripts") } },
                enabled = canWrite && scriptReason == null,
            ) {
                Text("Use environment actions")
            }
            TextButton(
                onClick = {
                    action {
                        val project = tools.project(projectId) ?: error("Project is unavailable.")
                        val file =
                            tools.projectFile(project) ?: error("This project has no t3.json.")
                        tools.importActions(projectId, file)
                    }
                },
                enabled = canWrite && scriptReason == null && "filesystem:read" in state.scopes,
            ) {
                Text("Import missing actions from t3.json")
            }
        }
        if (busy) Text("Saving…", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    editing?.let { original ->
        ProjectActionDialog(
            original,
            onDismiss = { editing = null },
            onSave = { edited ->
                editing = null
                action { tools.saveAction(projectId, edited) }
            },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${item.name}?") },
            text = { Text("The action will be removed from these defaults.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        action { tools.removeAction(projectId, item.id) }
                    },
                    enabled = canWrite,
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProjectActionDialog(
    original: ProjectAction,
    onDismiss: () -> Unit,
    onSave: (ProjectAction) -> Unit,
) {
    var edited by remember(original) { mutableStateOf(original) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original.name.isBlank()) "Add action" else "Edit action") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    edited.name,
                    { edited = edited.copy(name = it) },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    edited.command,
                    { edited = edited.copy(command = it) },
                    label = { Text("Shell command") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                ChoiceField("Icon", edited.icon, projectActionIcons.map { it to it }) {
                    edited = edited.copy(icon = it)
                }
                ActionToggle("Run when a worktree is created", edited.setup) {
                    edited = edited.copy(setup = it)
                }
                if (edited.setup)
                    ActionToggle("Wait before starting the agent", edited.waitForSetup) {
                        edited = edited.copy(waitForSetup = it)
                    }
                ActionToggle("Run when the thread settles", edited.onSettle) {
                    edited = edited.copy(onSettle = it)
                }
                OutlinedTextField(
                    edited.previewUrl.orEmpty(),
                    { edited = edited.copy(previewUrl = it.takeIf(String::isNotBlank)) },
                    label = { Text("Preview URL (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!edited.previewUrl.isNullOrBlank())
                    ActionToggle("Open preview automatically", edited.autoOpenPreview) {
                        edited = edited.copy(autoOpenPreview = it)
                    }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(edited) },
                enabled = edited.name.isNotBlank() && edited.command.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
