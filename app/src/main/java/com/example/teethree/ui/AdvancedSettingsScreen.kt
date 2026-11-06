package com.example.teethree.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.ClientAppearancePreferences
import com.example.teethree.data.NativeSetting
import com.example.teethree.data.SettingsPage
import com.example.teethree.data.SettingsTools
import com.example.teethree.data.T3Project
import com.example.teethree.data.T3Repository
import com.example.teethree.data.WorktreeSetupStatus
import com.example.teethree.data.array
import com.example.teethree.data.bool
import com.example.teethree.data.long
import com.example.teethree.data.nativeSettings
import com.example.teethree.data.obj
import com.example.teethree.data.retainWorktreeSetup
import com.example.teethree.data.string
import com.example.teethree.data.worktreeSetupCanCancel
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ClientSettingsControls(
    val followUpBehavior: String,
    val projectGrouping: String,
    val cacheBytes: Long,
    val onFollowUpBehavior: (String) -> Unit,
    val onProjectGrouping: (String) -> Unit,
    val onClearCache: () -> Unit,
    val composerEnterBehavior: String = "send",
    val onComposerEnterBehavior: (String) -> Unit = {},
    val appearance: ClientAppearancePreferences = ClientAppearancePreferences(),
    val onAppearanceChange: (ClientAppearancePreferences) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    repository: T3Repository,
    onBack: () -> Unit,
    client: ClientSettingsControls? = null,
    initialPage: SettingsPage = SettingsPage.General,
    onProjectRemoved: (String) -> Unit = {},
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val tools = remember(repository) { SettingsTools(repository) }
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var renamedProject by remember { mutableStateOf<T3Project?>(null) }
    var deleteProject by remember { mutableStateOf<T3Project?>(null) }
    var cleanupConfirmation by remember { mutableStateOf(false) }
    var clearCacheConfirmation by remember { mutableStateOf(false) }
    var database by remember { mutableStateOf<JsonObject?>(null) }
    var report by remember { mutableStateOf<JsonObject?>(null) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "The operation failed."
            } finally {
                busy = false
            }
        }
    }
    DisposableEffect(repository, page) {
        val subscription =
            if (page == SettingsPage.Storage)
                runCatching {
                    tools.observeStorage(
                        onDatabase = { value -> scope.launch { database = value } },
                        onReport = { value -> scope.launch { report = value } },
                        onError = { failure ->
                            scope.launch {
                                error = failure.message ?: "Storage information is unavailable."
                            }
                        },
                    )
                }
                    .getOrElse {
                        error = it.message
                        null
                    }
            else null
        onDispose { subscription?.close() }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsPage.entries.forEach { candidate ->
                    FilterChip(
                        selected = page == candidate,
                        onClick = {
                            page = candidate
                            error = null
                        },
                        label = { Text(candidate.title) },
                    )
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                if (busy) Text("Saving…", style = MaterialTheme.typography.labelMedium)
                if (page == SettingsPage.General) {
                    if (client != null) {
                        Text(
                            "While the agent is running",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        ChoiceRow(
                            listOf(
                                "queue" to "Queue follow-up messages",
                                "steer" to "Steer immediately",
                            ),
                            client.followUpBehavior,
                            !busy,
                            client.onFollowUpBehavior,
                        )
                        ClientAppearanceSettings(client.appearance, client.onAppearanceChange)
                        Text("Project grouping", style = MaterialTheme.typography.titleMedium)
                        ChoiceRow(
                            listOf(
                                "repository" to "By repository",
                                "repository_path" to "By repository path",
                                "separate" to "Keep separate",
                            ),
                            client.projectGrouping,
                            !busy,
                            client.onProjectGrouping,
                        )
                        Text(
                            "Hardware keyboard Return",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        ChoiceRow(
                            listOf(
                                "send" to "Send message · Shift-Return adds a line",
                                "newline" to "New line · Ctrl/Cmd-Return sends",
                            ),
                            client.composerEnterBehavior,
                            !busy,
                            client.onComposerEnterBehavior,
                        )
                        Text(
                            "Client cache: ${formatStorageBytes(client.cacheBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = { clearCacheConfirmation = true },
                            enabled = !busy && client.cacheBytes > 0,
                        ) {
                            Text("Clear client cache")
                        }
                        Text(
                            "Saved connections, credentials, drafts and preferences are kept.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else Text("Client settings are not available yet.")
                } else if (page == SettingsPage.Providers) {
                    Text(
                        "Provider accounts and sign-in remain in Providers. These switches change which installed provider instances can run.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.serverConfig
                        ?.array("providers")
                        ?.filterIsInstance<JsonObject>()
                        ?.forEach { provider ->
                            val id = provider.string("instanceId")
                            val managed = tools.providerManagedReason(id)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        provider.string("displayName", provider.string("driver")),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        managed ?: provider.string("status"),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = provider.bool("enabled"),
                                    enabled =
                                        !busy &&
                                            "providers:manage" in state.scopes &&
                                            managed == null,
                                    onCheckedChange = { value ->
                                        run { tools.providerEnabled(id, value) }
                                    },
                                )
                            }
                        }
                } else if (page == SettingsPage.Defaults) {
                    if (tools.capability("projectSettingsOverrides")) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = projectId == null,
                                onClick = { projectId = null },
                                label = { Text("Environment defaults") },
                            )
                            state.projects.forEach { project ->
                                FilterChip(
                                    selected = projectId == project.id,
                                    onClick = { projectId = project.id },
                                    label = { Text(project.title) },
                                )
                            }
                        }
                    }
                    ProjectDefaultsEditor(repository, projectId)
                } else if (page == SettingsPage.Projects) {
                    state.projects.forEach { project ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(project.title, style = MaterialTheme.typography.titleMedium)
                                SelectionContainer {
                                    Text(
                                        project.workspaceRoot,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        projectId = project.id
                                        page = SettingsPage.Defaults
                                    }
                                ) {
                                    Text("Models and actions")
                                }
                                val allowed = !busy && "orchestration:operate" in state.scopes
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    TextButton(
                                        onClick = { renamedProject = project },
                                        enabled = allowed,
                                    ) {
                                        Text("Rename")
                                    }
                                    if (tools.capability("projectOrganization")) {
                                        TextButton(
                                            onClick = {
                                                run {
                                                    tools.organizeProject(
                                                        project.id,
                                                        pinned = !project.pinned,
                                                    )
                                                }
                                            },
                                            enabled = allowed,
                                        ) {
                                            Text(if (project.pinned) "Unpin" else "Pin")
                                        }
                                        TextButton(
                                            onClick = {
                                                run {
                                                    tools.organizeProject(
                                                        project.id,
                                                        archived = !project.archived,
                                                    )
                                                }
                                            },
                                            enabled = allowed,
                                        ) {
                                            Text(if (project.archived) "Unarchive" else "Archive")
                                        }
                                    }
                                    TextButton(
                                        onClick = { deleteProject = project },
                                        enabled = allowed,
                                    ) {
                                        Text("Delete", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                } else if (page == SettingsPage.Storage) {
                    if (!tools.capability("databaseStorage") && !tools.capability("storageCleanup"))
                        Text("This server does not report storage information.")
                    database?.let { value ->
                        Text("Database: ${formatStorageBytes(value.long("databaseBytes"))}")
                        Text("Reclaimable: ${formatStorageBytes(value.long("reclaimableBytes"))}")
                        if (
                            !value.bool("incrementalVacuumEnabled") &&
                                value.long("reclaimableBytes") >= 1_073_741_824L
                        )
                            SelectionContainer {
                                Text(
                                    "Run on the host while offline: ${value.string("compactCommand")}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                    }
                    report?.let { value ->
                        Text("Last cleanup freed ${formatStorageBytes(value.long("bytesFreed"))}")
                        val counts = value.obj("counts")
                        Text(
                            "${counts.long("removed")} removed · ${counts.long("kept")} kept · ${counts.long("failed")} failed",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        value.array("entries").filterIsInstance<JsonObject>().forEach { entry ->
                            Text(
                                "${entry.string("outcome")}: ${entry.string("reason")}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (tools.capability("storageCleanupRun"))
                        OutlinedButton(
                            onClick = { cleanupConfirmation = true },
                            enabled = !busy && "settings:write" in state.scopes,
                        ) {
                            Text("Run storage cleanup")
                        }
                } else {
                    if (tools.capability("projectSettingsOverrides")) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = projectId == null,
                                onClick = { projectId = null },
                                label = { Text("Environment defaults") },
                            )
                            state.projects.forEach { project ->
                                FilterChip(
                                    selected = projectId == project.id,
                                    onClick = { projectId = project.id },
                                    label = { Text(project.title) },
                                )
                            }
                        }
                    }
                    nativeSettings
                        .filter {
                            it.page == page &&
                                (projectId == null || it.projectScoped) &&
                                (it.capability == null || tools.capability(it.capability))
                        }
                        .forEach { setting ->
                            val managed = tools.settingManagedReason(setting, projectId)
                            val enabled =
                                !busy &&
                                    state.serverConfig != null &&
                                    "settings:write" in state.scopes &&
                                    managed == null
                            NativeSettingControl(
                                setting,
                                tools.settingValue(setting, projectId),
                                enabled,
                                isAllowed = { tools.settingAllowed(setting, it) },
                                onSave = { value -> run { tools.save(setting, value, projectId) } },
                            )
                            if (managed != null)
                                Text(managed, style = MaterialTheme.typography.bodySmall)
                            if (
                                projectId != null &&
                                    state.serverConfig
                                        ?.obj("settings")
                                        ?.obj("projectSettingsOverrides")
                                        ?.obj(projectId.orEmpty())
                                        ?.containsKey(setting.key) == true
                            )
                                TextButton(
                                    onClick = {
                                        projectId?.let { id -> run { tools.inherit(setting, id) } }
                                    },
                                    enabled = enabled,
                                ) {
                                    Text("Use environment default")
                                }
                            HorizontalDivider()
                        }
                }
            }
        }
    }
    renamedProject?.let { project ->
        var title by remember(project.id) { mutableStateOf(project.title) }
        AlertDialog(
            onDismissRequest = { if (!busy) renamedProject = null },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        run {
                            tools.renameProject(project.id, title)
                            renamedProject = null
                        }
                    },
                    enabled = !busy && title.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamedProject = null }, enabled = !busy) { Text("Cancel") }
            },
        )
    }
    deleteProject?.let { project ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleteProject = null },
            title = { Text("Delete ${project.title}?") },
            text = {
                Text(
                    "This removes the project and its thread history from this environment. Workspace files remain on the host. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        run {
                            tools.deleteProject(project.id)
                            deleteProject = null
                            onProjectRemoved(project.id)
                        }
                    },
                    enabled = !busy,
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteProject = null }, enabled = !busy) { Text("Cancel") }
            },
        )
    }
    if (cleanupConfirmation)
        AlertDialog(
            onDismissRequest = { cleanupConfirmation = false },
            title = { Text("Run storage cleanup?") },
            text = {
                Text(
                    "Remove eligible worktrees, browser artifacts and logs according to this server’s retention settings."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        cleanupConfirmation = false
                        run { report = tools.cleanup() }
                    }
                ) {
                    Text("Run cleanup")
                }
            },
            dismissButton = {
                TextButton(onClick = { cleanupConfirmation = false }) { Text("Cancel") }
            },
        )
    if (clearCacheConfirmation && client != null)
        AlertDialog(
            onDismissRequest = { clearCacheConfirmation = false },
            title = { Text("Clear client cache?") },
            text = {
                Text(
                    "Remove cached files while keeping connections, credentials, drafts and preferences."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearCacheConfirmation = false
                        client.onClearCache()
                    }
                ) {
                    Text("Clear cache")
                }
            },
            dismissButton = {
                TextButton(onClick = { clearCacheConfirmation = false }) { Text("Cancel") }
            },
        )
}

@Composable
private fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                enabled = enabled,
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun NativeSettingControl(
    setting: NativeSetting,
    value: JsonElement?,
    enabled: Boolean,
    isAllowed: (JsonElement) -> Boolean,
    onSave: (JsonElement) -> Unit,
) {
    if (setting.boolean)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(setting.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = value == JsonPrimitive(true),
                onCheckedChange = { onSave(JsonPrimitive(it)) },
                enabled = enabled,
            )
        }
    else if (setting.choices.isNotEmpty()) {
        Text(setting.title, style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            setting.choices.forEach { choice ->
                val raw = choice.value?.let(::JsonPrimitive) ?: JsonNull
                FilterChip(
                    selected = value == raw || value == null && raw == JsonNull,
                    onClick = { onSave(raw) },
                    enabled = enabled && isAllowed(raw),
                    label = { Text(choice.label) },
                )
            }
        }
    } else {
        val content = if (value == JsonNull) "" else (value as? JsonPrimitive)?.content.orEmpty()
        var draft by remember(setting.key, content) { mutableStateOf(content) }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            enabled = enabled,
            label = { Text(setting.title) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 5,
        )
        TextButton(
            onClick = {
                if (setting.number) draft.toDoubleOrNull()?.let { onSave(JsonPrimitive(it)) }
                else onSave(JsonPrimitive(draft.trim()))
            },
            enabled =
                enabled &&
                    draft.trim() != content &&
                    (!setting.number || draft.toDoubleOrNull()?.let { it in 1.0..90.0 } == true),
        ) {
            Text("Save")
        }
        if (setting.number)
            TextButton(onClick = { onSave(JsonNull) }, enabled = enabled && value != JsonNull) {
                Text("Off")
            }
    }
}

@Composable
fun WorktreeSetupPanel(
    repository: T3Repository,
    threadId: String,
    onOpenTerminal: (String) -> Unit,
    onWorkLocally: (() -> Unit)? = null,
) {
    val tools = remember(repository) { SettingsTools(repository) }
    val state by repository.state.collectAsStateWithLifecycle()
    if (
        state.serverConfig
            ?.obj("environment")
            ?.obj("capabilities")
            ?.bool("requiredWorktreeBootstrap") != true
    )
        return
    val scope = rememberCoroutineScope()
    var snapshot by remember(threadId) { mutableStateOf<WorktreeSetupStatus?>(null) }
    var expanded by rememberSaveable(threadId) { mutableStateOf(false) }
    var busy by remember(threadId) { mutableStateOf(false) }
    var error by remember(threadId) { mutableStateOf<String?>(null) }
    val observing = snapshot == null || snapshot?.phase == "running"
    DisposableEffect(repository, threadId, observing) {
        val subscription =
            if (observing)
                runCatching {
                    tools.observeWorktreeSetup(
                        threadId,
                        onError = { failure ->
                            scope.launch {
                                error = failure.message ?: "Worktree setup is unavailable."
                            }
                        },
                    ) { next ->
                        scope.launch {
                            snapshot = retainWorktreeSetup(snapshot, next, threadId)
                        }
                    }
                }
                    .getOrElse {
                        error = it.message
                        null
                    }
            else null
        onDispose { subscription?.close() }
    }
    val current = snapshot ?: return
    val canCancel = worktreeSetupCanCancel(current)
    val agentStarted = current.stages.any { it.id == "agent" && it.status == "done" }
    if (current.phase == "done" && agentStarted && current.stages.none { it.status == "failed" })
        return
    if (current.phase != "running" && state.timeline.count { it.type == "user" } > 1) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Worktree setup · ${current.phase}", style = MaterialTheme.typography.titleMedium)
            current.branch?.let { Text(it) }
            current.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "Show details")
            }
            if (expanded)
                current.stages.forEach { stage ->
                    Text(
                        "${stageLabel(stage.id)} · ${stage.status}${stage.percent?.let { " · $it%" }.orEmpty()}"
                    )
                    stage.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (stage.tail.isNotEmpty())
                        SelectionContainer {
                            Text(
                                stage.tail.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = T3MonoFontFamily,
                            )
                        }
                }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                current.terminalId?.let { terminal ->
                    TextButton(onClick = { onOpenTerminal(terminal) }) {
                        Text("Open setup terminal")
                    }
                }
                if (canCancel)
                    TextButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    tools.cancelWorktreeSetup(threadId)
                                } catch (failure: Exception) {
                                    if (failure is CancellationException) throw failure
                                    error = failure.message
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && "orchestration:operate" in state.scopes,
                    ) {
                        Text(if (busy) "Cancelling…" else "Cancel setup")
                    }
                if (onWorkLocally != null && canCancel)
                    TextButton(
                        onClick = onWorkLocally,
                        enabled = !busy && "orchestration:operate" in state.scopes,
                    ) {
                        Text("Work locally")
                    }
            }
        }
    }
}

private fun stageLabel(id: String) =
    when (id) {
        "fetch" -> "Fetch base branch"
        "checkout" -> "Check out files"
        "submodules" -> "Initialize submodules"
        "setup-script" -> "Run setup script"
        "agent" -> "Start agent"
        else -> id
    }

internal fun formatStorageBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1_048_576 -> "${bytes / 1024} KB"
        bytes < 1_073_741_824 -> "${bytes / 1_048_576} MB"
        else -> "${bytes / 1_073_741_824} GB"
    }
