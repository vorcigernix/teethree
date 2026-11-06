package com.example.teethree.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.teethree.data.ComposerContextSelection
import com.example.teethree.data.ComposerContextTools
import com.example.teethree.data.ComposerFileMatches
import com.example.teethree.data.ComposerSkill
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.T3Project
import com.example.teethree.data.T3Repository
import com.example.teethree.data.T3Thread
import com.example.teethree.data.mentionComposerContext
import com.example.teethree.data.obj
import com.example.teethree.data.skillComposerContext
import com.example.teethree.data.string
import com.example.teethree.data.threadComposerContext
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

@Composable
fun ComposerContextPicker(
    repository: T3Repository,
    project: T3Project?,
    thread: T3Thread?,
    onAdd: (ComposerContextSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by repository.state.collectAsState()
    val tools = remember(repository) { ComposerContextTools(repository) }
    val connection = remember { repository.connectionId }
    val version = remember { repository.connectionGeneration }
    val latestDismiss = rememberUpdatedState(onDismiss)
    var tab by rememberSaveable { mutableStateOf("Files") }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var files by remember { mutableStateOf<ComposerFileMatches?>(null) }
    var skills by remember { mutableStateOf<List<ComposerSkill>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cwd = thread?.worktreePath ?: project?.workspaceRoot
    val provider =
        thread?.modelSelection?.instanceId
            ?: state.providers.firstOrNull { it.available }?.instanceId
    val environmentId = state.serverConfig?.obj("environment")?.string("environmentId")
    val current =
        connection != null &&
            repository.connectionId == connection &&
            repository.connectionGeneration == version &&
            state.connectionStatus == ConnectionStatus.Connected
    LaunchedEffect(current) { if (!current) latestDismiss.value() }
    LaunchedEffect(tab, submitted, cwd, provider, current, attempt) {
        if (!current) return@LaunchedEffect
        files = null
        skills = emptyList()
        error = null
        val requested = submitted
        if (tab == "Chats" || (tab == "Files" && requested == null)) {
            busy = false
            return@LaunchedEffect
        }
        busy = true
        try {
            require(!cwd.isNullOrBlank()) { "Choose a project to add workspace context." }
            if (tab == "Files") {
                require("filesystem:read" in state.scopes) {
                    "This connection cannot search workspace files."
                }
                files = tools.files(cwd, requested.orEmpty())
            } else {
                require(!provider.isNullOrBlank()) { "Choose a provider before adding a skill." }
                skills = tools.skills(provider, cwd)
            }
            if (repository.connectionId != connection || repository.connectionGeneration != version)
                latestDismiss.value()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "Context could not be loaded."
        } finally {
            busy = false
        }
    }
    fun add(record: JsonObject) {
        try {
            if (connection != null)
                onAdd(tools.selection(connection, version, thread?.id, listOf(record)))
        } catch (failure: Exception) {
            error = failure.message ?: "Context could not be attached."
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add context") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (label in listOf("Files", "Skills", "Chats")) FilterChip(
                        tab == label,
                        onClick = {
                            tab = label
                            query = ""
                            submitted = null
                        },
                        label = { Text(label) },
                    )
                }
                Text(
                    if (tab == "Files")
                        "Workspace paths on ${project?.title ?: "this environment"}. Files stay on the environment."
                    else if (tab == "Skills")
                        "Invocable skills from the selected provider and workspace."
                    else "Chats on this environment.",
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedTextField(
                    query,
                    {
                        if (it.length <= 256) {
                            query = it
                            if (tab == "Files") submitted = null
                        }
                    },
                    label = {
                        Text(
                            if (tab == "Files") "Find a workspace path"
                            else "Filter ${tab.lowercase()}"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (tab == "Files")
                    TextButton(
                        onClick = {
                            submitted = query
                            attempt++
                        },
                        enabled = current && !busy && cwd != null,
                    ) {
                        Text("Search workspace")
                    }
                if (busy) Text("Loading context…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (error != null)
                    TextButton(onClick = { attempt++ }, enabled = current && !busy) {
                        Text("Retry")
                    }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    when (tab) {
                        "Files" -> {
                            items(files?.paths.orEmpty(), key = { it }) { path ->
                                TextButton(
                                    onClick = { add(mentionComposerContext(path)) },
                                    enabled = current,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) {
                                    Text(path, Modifier.fillMaxWidth())
                                }
                            }
                            if (files?.truncated == true)
                                item { Text("More paths match. Narrow the query.") }
                            if (files?.paths?.isEmpty() == true)
                                item { Text("No matching workspace paths.") }
                        }
                        "Skills" -> {
                            val visible = skills.filter {
                                query.isBlank() ||
                                    it.name.contains(query, true) ||
                                    it.label.contains(query, true) ||
                                    it.description.contains(query, true)
                            }
                            items(visible, key = { it.name }) { skill ->
                                TextButton(
                                    onClick = {
                                        add(skillComposerContext(skill.name, skill.label))
                                    },
                                    enabled = current,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(skill.label)
                                        if (skill.description.isNotBlank())
                                            Text(
                                                skill.description,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                    }
                                }
                            }
                            if (!busy && error == null && visible.isEmpty())
                                item { Text("No invocable skills match this workspace.") }
                        }
                        else -> {
                            val threads =
                                state.threads
                                    .filter {
                                        it.id != thread?.id &&
                                            !it.archived &&
                                            (query.isBlank() || it.title.contains(query, true))
                                    }
                                    .sortedByDescending { it.updatedAt }
                            items(threads, key = { it.id }) { candidate ->
                                TextButton(
                                    onClick = {
                                        environmentId?.takeIf(String::isNotBlank)?.let {
                                            add(threadComposerContext(it, candidate))
                                        }
                                    },
                                    enabled = current && !environmentId.isNullOrBlank(),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) {
                                    Text(candidate.title, Modifier.fillMaxWidth())
                                }
                            }
                            if (environmentId.isNullOrBlank())
                                item {
                                    Text(
                                        "The environment identity is not ready. Reconnect to add a chat reference."
                                    )
                                }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
