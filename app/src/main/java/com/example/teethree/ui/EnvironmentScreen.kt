package com.example.teethree.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.EnvironmentAuthState
import com.example.teethree.data.EnvironmentProvider
import com.example.teethree.data.EnvironmentTask
import com.example.teethree.data.EnvironmentTools
import com.example.teethree.data.ModelSelection
import com.example.teethree.data.SettingsPage
import com.example.teethree.data.T3Model
import com.example.teethree.data.T3Project
import com.example.teethree.data.T3Provider
import com.example.teethree.data.T3Repository
import com.example.teethree.data.bool
import com.example.teethree.data.buildEnvironmentSchedule
import com.example.teethree.data.long
import com.example.teethree.data.providerBrowserUrlAllowed
import com.example.teethree.data.string
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import com.example.teethree.ui.adaptive.AdaptiveDatePickerDialog as DatePickerDialog
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentScreen(
    repository: T3Repository,
    projects: List<T3Project>,
    providers: List<T3Provider>,
    onBack: () -> Unit,
    onAdvancedSettings: ((SettingsPage) -> Unit)? = null,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val tools = remember(repository) { EnvironmentTools(repository) }
    val scope = rememberCoroutineScope()
    val connected = state.connectionStatus == ConnectionStatus.Connected
    val canRead = connected && "orchestration:read" in state.scopes
    val canManage = connected && "providers:manage" in state.scopes
    val canSchedule = connected && "orchestration:operate" in state.scopes
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var snapshots by remember { mutableStateOf<List<EnvironmentProvider>?>(null) }
    var tasks by remember { mutableStateOf<List<EnvironmentTask>?>(null) }
    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTask by remember { mutableStateOf<EnvironmentTask?>(null) }
    var creatingTask by rememberSaveable { mutableStateOf(false) }
    var deletingTask by remember { mutableStateOf<EnvironmentTask?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val selected = snapshots?.find { it.id == selectedProvider }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
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
    DisposableEffect(tools, canRead, connected) {
        val subscription =
            if (canRead)
                runCatching {
                    tools.subscribeTasks(onError = { error = it.message }, onValue = { tasks = it })
                }
                    .getOrElse {
                        error = it.message
                        null
                    }
            else null
        onDispose { subscription?.close() }
    }
    LaunchedEffect(tools, canRead, refresh) {
        if (!canRead) return@LaunchedEffect
        busy = true
        try {
            snapshots = tools.providers()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = failure.message
        } finally {
            busy = false
        }
    }
    BackHandler(selectedProvider != null) { selectedProvider = null }
    if (creatingTask || editingTask != null) {
        val choices =
            snapshots?.map {
                T3Provider(
                    it.id,
                    it.name,
                    it.models,
                    it.enabled && it.installed && it.status != "error",
                )
            } ?: providers
        EnvironmentTaskEditor(
            tools,
            editingTask,
            projects,
            choices,
            canSchedule,
            onSaved = {
                creatingTask = false
                editingTask = null
            },
            onBack = {
                creatingTask = false
                editingTask = null
            },
        )
        return
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            LargeTopAppBar(
                title = { Text(selected?.name ?: "Environment settings") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectedProvider != null) selectedProvider = null else onBack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (onAdvancedSettings != null)
                        TextButton(onClick = { onAdvancedSettings(SettingsPage.Providers) }) {
                            Text("Configure")
                        }
                    IconButton(
                        onClick = { perform { snapshots = tools.providers(fresh = true) } },
                        enabled = canRead && !busy,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh providers")
                    }
                },
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxSize()) {
                if (selected == null)
                    PrimaryTabRow(selectedTabIndex = tab) {
                        listOf("Providers", "Scheduled tasks").forEachIndexed { index, label ->
                            Tab(
                                selected = tab == index,
                                onClick = { tab = index },
                                text = { Text(label) },
                            )
                        }
                    }
                if (!connected)
                    Text(
                        "Reconnect to manage this environment.",
                        Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                error?.let {
                    Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error)
                }
                if (busy)
                    Text(
                        "Updating from your environment…",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                when {
                    selected != null ->
                        ProviderAuthPanel(
                            repository,
                            tools,
                            selected,
                            canManage,
                            onChanged = { refresh += 1 },
                        )
                    tab == 0 ->
                        LazyColumn(
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            if (snapshots == null && !busy)
                                item { Text("Provider information is not available yet.") }
                            items(snapshots.orEmpty(), key = { it.id }) { provider ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            provider.name,
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                        Text(
                                            when {
                                                !provider.enabled -> "Turned off"
                                                !provider.installed -> "Not installed"
                                                provider.authStatus == "authenticated" ->
                                                    "Signed in" +
                                                        (provider.account?.let { " · $it" } ?: "")
                                                else ->
                                                    provider.authStatus.ifBlank { provider.status }
                                            },
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        provider.message?.let {
                                            Text(it, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        Text(
                                            "${provider.models.size} models",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        Text(
                                            provider.models.joinToString { it.name },
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        OutlinedButton(
                                            onClick = { selectedProvider = provider.id },
                                            enabled = canManage,
                                        ) {
                                            Text("Manage sign-in")
                                        }
                                        if (!canManage)
                                            Text(
                                                "This connection can view providers but cannot manage sign-in.",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                    }
                                }
                            }
                        }
                    else ->
                        LazyColumn(
                            contentPadding = PaddingValues(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            item {
                                Button(
                                    onClick = { creatingTask = true },
                                    enabled = canSchedule && !busy && projects.isNotEmpty(),
                                ) {
                                    Icon(Icons.Outlined.Add, contentDescription = null)
                                    Text("New scheduled task")
                                }
                            }
                            if (tasks?.isEmpty() == true)
                                item { Text("No scheduled tasks in this environment.") }
                            items(tasks.orEmpty(), key = { it.id }) { task ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                task.title,
                                                Modifier.weight(1f),
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            Switch(
                                                task.enabled,
                                                onCheckedChange = { enabled ->
                                                    perform { tools.setTaskEnabled(task, enabled) }
                                                },
                                                enabled = canSchedule && !busy,
                                            )
                                        }
                                        Text(
                                            taskScheduleDescription(task),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(task.prompt, maxLines = 4)
                                        Text(
                                            "Last run: ${task.lastRunStatus}",
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        task.nextRunAt?.let {
                                            Text(
                                                "Next run: ${deviceDateTime(it)}",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                        task.lastRunError?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error)
                                        }
                                        task.blocked?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error)
                                        }
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = { editingTask = task },
                                                enabled =
                                                    canSchedule &&
                                                        !busy &&
                                                        task.schedule.string("type") != "webhook",
                                            ) {
                                                Text("Edit")
                                            }
                                            OutlinedButton(
                                                onClick = { perform { tools.runTask(task) } },
                                                enabled =
                                                    canSchedule &&
                                                        !busy &&
                                                        task.blocked == null &&
                                                        task.lastRunStatus != "running",
                                            ) {
                                                Text("Run now")
                                            }
                                            TextButton(
                                                onClick = { deletingTask = task },
                                                enabled = canSchedule && !busy,
                                            ) {
                                                Text("Delete")
                                            }
                                        }
                                        if (task.schedule.string("type") == "webhook")
                                            Text(
                                                "Webhook configuration cannot be edited here yet.",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                    }
                                }
                            }
                        }
                }
            }
        }
    }
    deletingTask?.let { task ->
        AlertDialog(
            onDismissRequest = { deletingTask = null },
            title = { Text("Delete scheduled task?") },
            text = { Text("Delete ${task.title}? Its conversation history is kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingTask = null
                        perform { tools.deleteTask(task) }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deletingTask = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProviderAuthPanel(
    repository: T3Repository,
    tools: EnvironmentTools,
    provider: EnvironmentProvider,
    canManage: Boolean,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var auth by remember(provider.id) { mutableStateOf<EnvironmentAuthState?>(null) }
    var authRevision by remember(provider.id) { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    val interaction = auth?.interaction
    var values by
        remember(auth?.flowId, interaction?.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var callback by remember(auth?.flowId) { mutableStateOf("") }
    val active = auth?.phase in setOf("starting", "waiting", "verifying")
    val browserUrl = interaction?.url ?: auth?.url

    fun perform(action: suspend () -> EnvironmentAuthState) {
        if (busy || !canManage) return
        scope.launch {
            busy = true
            error = null
            val revision = authRevision
            try {
                val response = action()
                if (authRevision == revision) auth = response
                onChanged()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "Provider sign-in failed."
            } finally {
                busy = false
            }
        }
    }
    fun openBrowser(url: String) {
        if (!providerBrowserUrlAllowed(url)) {
            error = "The provider returned an unsupported browser URL."
            return
        }
        try {
            uriHandler.openUri(url)
        } catch (_: IllegalArgumentException) {
            error = "No browser is available to open the sign-in page."
        }
    }
    DisposableEffect(tools, provider.id, canManage) {
        val subscription =
            if (canManage)
                runCatching {
                    tools.subscribeAuth(
                        provider.id,
                        onError = { failure -> scope.launch { error = failure.message } },
                        onValue = { snapshot ->
                            scope.launch {
                                authRevision += 1
                                auth = snapshot
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
    LaunchedEffect(auth?.phase) { if (auth?.phase == "succeeded") onChanged() }
    LazyColumn(
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                auth?.message
                    ?: provider.message
                    ?: "Manage this provider's account on your environment.",
                style = MaterialTheme.typography.bodyLarge,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) Text("Updating sign-in…", color = MaterialTheme.colorScheme.primary)
        }
        item { ProviderMaintenancePanel(repository, provider.id, onChanged) }
        if (!active) {
            if (provider.canAuthenticate && provider.enabled && provider.installed) {
                if (auth?.methods?.isNotEmpty() == true)
                    items(auth!!.methods, key = { it.id }) { method ->
                        Button(
                            onClick = { perform { tools.startAuth(provider.id, method.id) } },
                            enabled = canManage && !busy,
                        ) {
                            Text(method.accountEmail?.let { "${method.name} · $it" } ?: method.name)
                        }
                        method.description?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                else
                    item {
                        Button(
                            onClick = { perform { tools.startAuth(provider.id) } },
                            enabled = canManage && !busy && auth != null,
                        ) {
                            Text("Sign in")
                        }
                    }
            }
            if (provider.canLogout && provider.authStatus == "authenticated")
                item {
                    OutlinedButton(onClick = { signingOut = true }, enabled = canManage && !busy) {
                        Text("Sign out")
                    }
                }
            provider.documentationUrl?.takeIf(::providerBrowserUrlAllowed)?.let { url ->
                item {
                    TextButton(onClick = { openBrowser(url) }) { Text("Provider setup guide") }
                }
            }
        }
        interaction?.userCode?.let { code ->
            item {
                SelectionContainer {
                    Text(
                        "Enter code $code on the sign-in page.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        if (browserUrl != null && !providerBrowserUrlAllowed(browserUrl))
            item {
                Text(
                    "The provider returned a sign-in address that cannot be opened here.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        if (browserUrl != null && providerBrowserUrlAllowed(browserUrl))
            item {
                Button(
                    onClick = {
                        val current = auth
                        if (
                            interaction?.type == "browser" &&
                                interaction.requiresConsent &&
                                current != null
                        ) {
                            perform {
                                val updated =
                                    tools.respondAuth(
                                        provider.id,
                                        current,
                                        buildJsonObject {
                                            put("type", "browser")
                                            put("action", "accept")
                                        },
                                    )
                                openBrowser(browserUrl)
                                updated
                            }
                        } else openBrowser(browserUrl)
                    },
                    enabled = canManage && !busy,
                ) {
                    Text("Open sign-in page")
                }
            }
        if (interaction?.type == "credentials") {
            items(interaction.fields, key = { it.name }) { field ->
                OutlinedTextField(
                    values[field.name].orEmpty(),
                    { values = values + (field.name to it) },
                    label = { Text(field.label) },
                    enabled = canManage && !busy,
                    visualTransformation =
                        if (field.secret) PasswordVisualTransformation()
                        else VisualTransformation.None,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType =
                                if (field.secret) KeyboardType.Password else KeyboardType.Text,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    onClick = {
                        auth?.let { current ->
                            perform {
                                tools.respondAuth(
                                    provider.id,
                                    current,
                                    buildJsonObject {
                                        put("type", "credentials")
                                        put(
                                            "values",
                                            buildJsonObject {
                                                values.forEach { (key, value) -> put(key, value) }
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    },
                    enabled = canManage && !busy,
                ) {
                    Text("Submit credentials")
                }
            }
        }
        if (interaction?.type == "terminal")
            item {
                SelectionContainer {
                    Text(
                        interaction.output.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), ""),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    values["terminal"].orEmpty(),
                    { values = values + ("terminal" to it) },
                    label = { Text("Terminal response") },
                    enabled = canManage && !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        auth?.let { current ->
                            perform {
                                val response = values["terminal"].orEmpty()
                                require(response.length <= 4095) {
                                    "Terminal responses support at most 4,095 characters."
                                }
                                tools.respondAuth(
                                    provider.id,
                                    current,
                                    buildJsonObject {
                                        put("type", "terminal")
                                        put("data", response + "\r")
                                    },
                                )
                            }
                        }
                    },
                    enabled = canManage && !busy,
                ) {
                    Text("Send response")
                }
            }
        if (
            auth?.flowId != null &&
                browserUrl != null &&
                (interaction == null || interaction.acceptsCallback)
        )
            item {
                OutlinedTextField(
                    callback,
                    { callback = it },
                    label = { Text("Callback URL from browser") },
                    supportingText = {
                        Text(
                            "If the browser cannot return to your environment, paste its final address here."
                        )
                    },
                    enabled = canManage && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                        ),
                )
                OutlinedButton(
                    onClick = {
                        auth?.let { current ->
                            perform { tools.completeAuth(provider.id, current, callback) }
                        }
                    },
                    enabled = canManage && !busy && callback.isNotBlank(),
                ) {
                    Text("Complete sign-in")
                }
            }
        if (active && auth?.flowId != null)
            item {
                OutlinedButton(
                    onClick = {
                        auth?.let { current -> perform { tools.cancelAuth(provider.id, current) } }
                    },
                    enabled = canManage && !busy,
                ) {
                    Text("Cancel sign-in")
                }
            }
    }
    if (signingOut)
        AlertDialog(
            onDismissRequest = { signingOut = false },
            title = { Text("Sign out of ${provider.name}?") },
            text = {
                Text(
                    "Running agents that use this sign-in will stop. Conversation history is kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        signingOut = false
                        perform { tools.logout(provider.id) }
                    }
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = { TextButton(onClick = { signingOut = false }) { Text("Cancel") } },
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnvironmentTaskEditor(
    tools: EnvironmentTools,
    task: EnvironmentTask?,
    projects: List<T3Project>,
    providers: List<T3Provider>,
    canOperate: Boolean,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val deviceZone = remember { ZoneId.systemDefault() }
    var title by rememberSaveable(task?.id) { mutableStateOf(task?.title.orEmpty()) }
    var prompt by rememberSaveable(task?.id) { mutableStateOf(task?.prompt.orEmpty()) }
    var enabled by rememberSaveable(task?.id) { mutableStateOf(task?.enabled ?: true) }
    var projectId by rememberSaveable(task?.id) { mutableStateOf(task?.projectId) }
    var instanceId by rememberSaveable(task?.id) { mutableStateOf(task?.model?.instanceId) }
    var modelId by rememberSaveable(task?.id) { mutableStateOf(task?.model?.model) }
    val project =
        if (projectId == null) projects.firstOrNull() else projects.find { it.id == projectId }
    val savedProvider = task?.let { saved ->
        providers.find { it.instanceId == saved.model.instanceId }
            ?: T3Provider(
                saved.model.instanceId,
                saved.model.instanceId,
                emptyList(),
                false,
            )
    }
    val available =
        (providers.filter { it.available && it.models.isNotEmpty() } + listOfNotNull(savedProvider))
            .distinctBy { it.instanceId }
    val provider = available.find { it.instanceId == instanceId } ?: available.firstOrNull()
    val models =
        provider?.models.orEmpty() +
            if (
                task != null &&
                    provider?.instanceId == task.model.instanceId &&
                    provider.models.none { it.slug == task.model.model }
            ) {
                listOf(T3Model(task.model.model, task.model.model, false))
            } else emptyList()
    val model =
        models.find { it.slug == modelId } ?: models.find { it.isDefault } ?: models.firstOrNull()
    var type by
        rememberSaveable(task?.id) {
            mutableStateOf(task?.schedule?.string("type") ?: "fixed_time")
        }
    var interval by
        rememberSaveable(task?.id) {
            mutableStateOf(
                task
                    ?.schedule
                    ?.long("everyMs")
                    ?.takeIf { it > 0 }
                    ?.let { (it / 60_000.0).coerceAtLeast(1.0).toString() } ?: "15"
            )
        }
    var time by
        rememberSaveable(task?.id) {
            mutableStateOf(task?.schedule?.string("timeOfDay")?.ifBlank { null } ?: "09:00")
        }
    val initialOnce =
        remember(task?.id) {
            task
                ?.schedule
                ?.string("runAt")
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    runCatching { Instant.parse(it).atZone(deviceZone).toLocalDateTime() }
                        .getOrNull()
                } ?: LocalDate.now(deviceZone).plusDays(1).atTime(9, 0)
        }
    var date by rememberSaveable(task?.id) { mutableStateOf(initialOnce.toLocalDate().toString()) }
    var onceTime by
        rememberSaveable(task?.id) {
            mutableStateOf(initialOnce.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
        }
    var zone by rememberSaveable(task?.id) { mutableStateOf(deviceZone.id) }
    val initialDays =
        remember(task?.id) {
            (task?.schedule?.get("weekdays") as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                }
                ?.toSet() ?: if (task == null) setOf(1, 2, 3, 4, 5) else (0..6).toSet()
        }
    var weekdays by rememberSaveable(task?.id) { mutableStateOf(initialDays) }
    var workspaceType by
        rememberSaveable(task?.id) { mutableStateOf(task?.workspace?.string("type") ?: "worktree") }
    var baseRef by
        rememberSaveable(task?.id) {
            mutableStateOf(task?.workspace?.string("baseRef")?.ifBlank { null } ?: "main")
        }
    var checkoutPath by
        rememberSaveable(task?.id) {
            mutableStateOf(task?.workspace?.string("worktreePath").orEmpty())
        }
    var startFromOrigin by
        rememberSaveable(task?.id) {
            mutableStateOf(task?.workspace?.bool("startFromOrigin") ?: true)
        }
    var runtimeMode by
        rememberSaveable(task?.id) { mutableStateOf(task?.runtimeMode ?: "approval-required") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var chooseDate by remember { mutableStateOf(false) }
    var chooseTime by remember { mutableStateOf(false) }
    val fieldsEnabled = canOperate && !busy
    BackHandler { if (!busy) onBack() }

    fun save() {
        if (!fieldsEnabled || project == null || provider == null || model == null) return
        scope.launch {
            busy = true
            error = null
            try {
                val unchangedOnce =
                    task?.schedule?.string("type") == "once" &&
                        type == "once" &&
                        date == initialOnce.toLocalDate().toString() &&
                        onceTime ==
                            initialOnce
                                .toLocalTime()
                                .format(DateTimeFormatter.ofPattern("HH:mm")) &&
                        zone == deviceZone.id
                val schedule =
                    if (unchangedOnce) task!!.schedule
                    else
                        buildEnvironmentSchedule(
                            type,
                            interval,
                            time,
                            weekdays,
                            "$date $onceTime",
                            zone,
                        )
                val workspace = buildJsonObject {
                    if (task?.workspace?.string("type") == workspaceType)
                        task.workspace.forEach { (key, value) -> put(key, value) }
                    put("type", workspaceType)
                    when (workspaceType) {
                        "worktree" -> {
                            require(baseRef.isNotBlank()) { "Enter a base branch." }
                            put("baseRef", baseRef.trim())
                            put("startFromOrigin", startFromOrigin)
                        }
                        "existing_worktree" -> {
                            require(checkoutPath.isNotBlank()) {
                                "Enter an existing worktree path."
                            }
                            put("worktreePath", checkoutPath.trim())
                        }
                    }
                }
                tools.saveTask(
                    task,
                    title,
                    prompt,
                    enabled,
                    project.id,
                    ModelSelection(provider.instanceId, model.slug),
                    schedule,
                    workspace,
                    runtimeMode,
                )
                onSaved()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "Could not save this scheduled task."
            } finally {
                busy = false
            }
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            LargeTopAppBar(
                title = { Text(if (task == null) "New scheduled task" else "Edit scheduled task") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Cancel")
                    }
                },
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    OutlinedTextField(
                        title,
                        { title = it },
                        enabled = fieldsEnabled,
                        label = { Text("Task name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        prompt,
                        { prompt = it },
                        enabled = fieldsEnabled,
                        label = { Text("Prompt") },
                        minLines = 4,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    EnvironmentChoice(
                        "Project",
                        project?.title ?: "Choose a project",
                        projects.map { it.id to it.title },
                        fieldsEnabled,
                    ) {
                        projectId = it
                    }
                }
                item {
                    EnvironmentChoice(
                        "Provider",
                        provider?.displayName ?: "Choose a provider",
                        available.map { it.instanceId to it.displayName },
                        fieldsEnabled,
                    ) {
                        instanceId = it
                        modelId = null
                    }
                }
                item {
                    EnvironmentChoice(
                        "Model",
                        model?.name ?: "Choose a model",
                        models.map { it.slug to it.name },
                        fieldsEnabled,
                    ) {
                        modelId = it
                    }
                }
                if (provider?.available == false)
                    item {
                        Text(
                            "This task's saved provider is currently unavailable.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                item {
                    EnvironmentChoice(
                        "Schedule",
                        when (type) {
                            "interval" -> "Every interval"
                            "once" -> "Once"
                            else -> "At a fixed time"
                        },
                        listOf(
                            "fixed_time" to "At a fixed time",
                            "interval" to "Every interval",
                            "once" to "Once",
                        ),
                        fieldsEnabled,
                    ) {
                        type = it
                    }
                }
                when (type) {
                    "interval" ->
                        item {
                            OutlinedTextField(
                                interval,
                                { interval = it },
                                enabled = fieldsEnabled,
                                label = { Text("Minutes between runs") },
                                supportingText = { Text("At least one minute.") },
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    "fixed_time" ->
                        item {
                            OutlinedButton(
                                onClick = { chooseTime = true },
                                enabled = fieldsEnabled,
                            ) {
                                Text("Time: $time")
                            }
                            Text(
                                "Uses the environment's time zone, which may differ from this device.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items((0..6).toList()) { day ->
                                    FilterChip(
                                        day in weekdays,
                                        {
                                            weekdays =
                                                if (day in weekdays) weekdays - day
                                                else weekdays + day
                                        },
                                        enabled = fieldsEnabled,
                                        label = {
                                            Text(
                                                listOf(
                                                    "Sun",
                                                    "Mon",
                                                    "Tue",
                                                    "Wed",
                                                    "Thu",
                                                    "Fri",
                                                    "Sat",
                                                )[day]
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    "once" ->
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { chooseDate = true },
                                    enabled = fieldsEnabled,
                                ) {
                                    Text(date)
                                }
                                OutlinedButton(
                                    onClick = { chooseTime = true },
                                    enabled = fieldsEnabled,
                                ) {
                                    Text(onceTime)
                                }
                            }
                            OutlinedTextField(
                                zone,
                                { zone = it },
                                label = { Text("Time zone") },
                                supportingText = {
                                    Text(
                                        "Device default: ${deviceZone.id}. Ambiguous daylight-saving times use the earlier offset."
                                    )
                                },
                                enabled = fieldsEnabled,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(
                                        capitalization = KeyboardCapitalization.None,
                                        autoCorrectEnabled = false,
                                    ),
                            )
                        }
                }
                item {
                    EnvironmentChoice(
                        "Workspace",
                        when (workspaceType) {
                            "root" -> "Project folder"
                            "existing_worktree" -> "Existing worktree"
                            else -> "New worktree"
                        },
                        listOf(
                            "root" to "Project folder",
                            "worktree" to "New worktree",
                            "existing_worktree" to "Existing worktree",
                        ),
                        fieldsEnabled,
                    ) {
                        workspaceType = it
                    }
                }
                if (workspaceType == "worktree")
                    item {
                        OutlinedTextField(
                            baseRef,
                            { baseRef = it },
                            label = { Text("Base branch") },
                            enabled = fieldsEnabled,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Start from origin", Modifier.weight(1f))
                            Switch(
                                startFromOrigin,
                                { startFromOrigin = it },
                                enabled = fieldsEnabled,
                            )
                        }
                    }
                if (workspaceType == "existing_worktree")
                    item {
                        OutlinedTextField(
                            checkoutPath,
                            { checkoutPath = it },
                            label = { Text("Worktree path on environment") },
                            enabled = fieldsEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                item {
                    EnvironmentChoice(
                        "Permissions",
                        permissionLabel(runtimeMode),
                        listOf("approval-required", "auto-accept-edits", "auto", "full-access")
                            .map { it to permissionLabel(it) },
                        fieldsEnabled,
                    ) {
                        runtimeMode = it
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enabled", Modifier.weight(1f))
                        Switch(enabled, { enabled = it }, enabled = fieldsEnabled)
                    }
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                item {
                    Button(
                        onClick = { save() },
                        enabled =
                            fieldsEnabled &&
                                title.isNotBlank() &&
                                prompt.isNotBlank() &&
                                project != null &&
                                model != null,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(18.dp),
                    ) {
                        Text(if (busy) "Saving…" else "Save scheduled task")
                    }
                }
            }
        }
    }
    if (chooseDate) {
        val datePicker =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            )
        DatePickerDialog(
            onDismissRequest = { chooseDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePicker.selectedDateMillis?.let {
                            date =
                                Instant.ofEpochMilli(it)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toString()
                        }
                        chooseDate = false
                    }
                ) {
                    Text("Choose date")
                }
            },
            dismissButton = { TextButton(onClick = { chooseDate = false }) { Text("Cancel") } },
        ) {
            DatePicker(datePicker)
        }
    }
    if (chooseTime) {
        val initial = runCatching {
            LocalTime.parse(
                if (type == "once") onceTime else time,
                DateTimeFormatter.ofPattern("H:mm"),
            )
        }
            .getOrDefault(LocalTime.of(9, 0))
        val picker = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { chooseTime = false },
            title = { Text("Choose time") },
            text = { TimePicker(picker) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val chosen =
                            LocalTime.of(picker.hour, picker.minute)
                                .format(DateTimeFormatter.ofPattern("HH:mm"))
                        if (type == "once") onceTime = chosen else time = chosen
                        chooseTime = false
                    }
                ) {
                    Text("Choose time")
                }
            },
            dismissButton = { TextButton(onClick = { chooseTime = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EnvironmentChoice(
    label: String,
    value: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    onChoose: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled && choices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(value, Modifier.weight(1f))
                Icon(Icons.Outlined.ExpandMore, "Choose $label")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (id, title) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            expanded = false
                            onChoose(id)
                        },
                    )
                }
            }
        }
    }
}

private fun permissionLabel(value: String) =
    when (value) {
        "auto-accept-edits" -> "Accept edits"
        "auto" -> "Automatic"
        "full-access" -> "Full access"
        else -> "Ask for approval"
    }

private fun deviceDateTime(value: String): String = runCatching {
    Instant.parse(value)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm z"))
}
    .getOrDefault(value)

private fun taskScheduleDescription(task: EnvironmentTask) =
    when (task.schedule.string("type")) {
        "interval" -> "Every ${task.schedule.long("everyMs") / 60_000.0} minutes"
        "fixed_time" -> "At ${task.schedule.string("timeOfDay")} in environment time"
        "once" -> "Once · ${deviceDateTime(task.schedule.string("runAt"))}"
        "webhook" -> "Triggered by webhook"
        else -> "Unknown schedule"
    }
