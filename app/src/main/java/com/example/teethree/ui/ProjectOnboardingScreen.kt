package com.example.teethree.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.CreatedProject
import com.example.teethree.data.ProjectCloneStatus
import com.example.teethree.data.ProjectFolderListing
import com.example.teethree.data.ProjectOnboardingTools
import com.example.teethree.data.SourceControlHost
import com.example.teethree.data.SourceControlSignIn
import com.example.teethree.data.T3Repository
import com.example.teethree.data.bool
import com.example.teethree.data.cloneDestination
import com.example.teethree.data.mergeProjectClones
import com.example.teethree.data.obj
import com.example.teethree.data.projectCloneDirectoryName
import com.example.teethree.data.projectCloneSummary
import com.example.teethree.data.providerBrowserUrlAllowed
import com.example.teethree.data.string
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ProjectOnboardingMode(val title: String) {
    Clone("Clone repository"),
    New("New Git project"),
    Folder("Import folder"),
    Accounts("Source control"),
}

private data class ProjectOnboardingState(
    val hosts: List<SourceControlHost> = emptyList(),
    val clones: List<ProjectCloneStatus> = emptyList(),
    val folders: ProjectFolderListing? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val created: CreatedProject? = null,
    val createdReady: Boolean = false,
    val createdFromNew: Boolean = false,
    val published: Boolean = false,
    val signIn: SourceControlSignIn? = null,
    val signInProvider: String? = null,
    val signInHost: String? = null,
    val completedProjectId: String? = null,
)

private class ProjectOnboardingViewModel(private val repository: T3Repository) : ViewModel() {
    private val tools = ProjectOnboardingTools(repository)
    private val mutableState = MutableStateFlow(ProjectOnboardingState())
    val state = mutableState.asStateFlow()
    private var clones: AutoCloseable? = null
    private var waitSignIn: Job? = null
    private var signInAttempt = 0L

    fun attach() {
        if (clones != null) return
        try {
            if (tools.trackedClonesAvailable())
                clones =
                    tools.observeClones(
                        onError = { error ->
                            mutableState.update {
                                it.copy(error = error.message ?: "Clone progress is unavailable.")
                            }
                        }
                    ) { values ->
                        mutableState.update {
                            it.copy(
                                clones = mergeProjectClones(it.clones, values),
                                createdReady =
                                    it.createdReady ||
                                        values.any { clone ->
                                            clone.projectId == it.created?.projectId &&
                                                clone.phase == "done"
                                        },
                            )
                        }
                    }
        } catch (error: Exception) {
            mutableState.update { it.copy(error = error.message) }
        }
        refresh()
    }

    fun detach() {
        clones?.close()
        clones = null
    }

    fun refresh() = perform {
        val hosts = tools.discover()
        mutableState.update { it.copy(hosts = hosts) }
    }

    private fun perform(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(error = error.message ?: "The operation failed.") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun clone(
        title: String,
        destination: String,
        remote: String,
        provider: String?,
        protocol: String,
    ) = perform {
        val repository = if (provider == null) null else tools.lookup(provider, remote)
        val id =
            tools.startClone(
                title,
                destination,
                if (repository == null) remote else null,
                provider,
                repository?.name,
                protocol,
            )
        mutableState.update {
            it.copy(
                notice = "Cloning started. You can leave this screen and return to its progress.",
                created = CreatedProject(id, destination, null),
                createdReady =
                    it.clones.any { clone -> clone.projectId == id && clone.phase == "done" },
                createdFromNew = false,
                published = false,
            )
        }
    }

    fun cloneAction(projectId: String, retry: Boolean) = perform {
        if (!tools.cloneAction(projectId, retry))
            mutableState.update {
                it.copy(
                    notice =
                        "That clone has already changed state. Refresh to see its current status."
                )
            }
    }

    fun createNew(
        name: String,
        publishProvider: String?,
        repositoryName: String,
        visibility: String,
    ) = perform {
        val created = tools.createNew(name)
        mutableState.update {
            it.copy(
                created = created,
                createdReady = true,
                createdFromNew = true,
                published = false,
                notice =
                    created.warning?.let { warning -> "Created without a first commit: $warning" }
                        ?: "Project created at ${created.workspaceRoot}.",
            )
        }
        if (publishProvider != null)
            publish(created.workspaceRoot, publishProvider, repositoryName, visibility)
    }

    fun importFolder(title: String, path: String, createIfMissing: Boolean) = perform {
        val id = tools.importFolder(title, path, createIfMissing)
        mutableState.update {
            it.copy(
                created = CreatedProject(id, path, null),
                createdReady = true,
                createdFromNew = false,
                published = false,
                notice = "Project added.",
            )
        }
    }

    fun browse(path: String) = perform {
        val folders = tools.browse(path)
        mutableState.update { it.copy(folders = folders) }
    }

    private suspend fun publish(
        path: String,
        provider: String,
        repositoryName: String,
        visibility: String,
    ) {
        val published = tools.publish(path, provider, repositoryName, visibility)
        mutableState.update {
            it.copy(
                published = true,
                notice =
                    "Published ${published.name}. The project remains available if you leave this screen.",
            )
        }
    }

    fun publishCreated(provider: String, repositoryName: String, visibility: String) = perform {
        val created = mutableState.value.created ?: return@perform
        publish(created.workspaceRoot, provider, repositoryName, visibility)
    }

    fun signIn(provider: String, host: String) = perform {
        waitSignIn?.cancel()
        val attempt = ++signInAttempt
        val prompt = tools.startSignIn(provider, host)
        mutableState.update {
            it.copy(signIn = prompt, signInProvider = provider, signInHost = host)
        }
        waitSignIn = viewModelScope.launch {
            try {
                tools.waitForSignIn(provider, host)
                if (attempt == signInAttempt) {
                    val hosts = tools.discover()
                    if (attempt == signInAttempt)
                        mutableState.update {
                            it.copy(
                                signIn = null,
                                hosts = hosts,
                                notice = "Source-control sign-in completed.",
                            )
                        }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (attempt == signInAttempt)
                    mutableState.update {
                        it.copy(
                            signIn = null,
                            error = error.message ?: "Source-control sign-in did not finish.",
                        )
                    }
            }
        }
    }

    fun cancelSignIn() {
        val provider = mutableState.value.signInProvider ?: return
        val host = mutableState.value.signInHost ?: return
        signInAttempt++
        waitSignIn?.cancel()
        mutableState.update { it.copy(signIn = null) }
        perform { tools.cancelSignIn(provider, host) }
    }

    fun signOut(provider: String, host: String) = perform {
        tools.signOut(provider, host)
        val hosts = tools.discover()
        mutableState.update { it.copy(hosts = hosts) }
    }

    fun openProject(id: String) = perform {
        val arrived =
            withTimeoutOrNull(15_000L) {
                repository.state.first { state -> state.projects.any { it.id == id } }
            }
        if (arrived == null)
            mutableState.update {
                it.copy(
                    notice =
                        "The project exists on the environment and will appear when the connection catches up."
                )
            }
        else mutableState.update { it.copy(completedProjectId = id) }
    }

    fun consumeCompletion(id: String) {
        mutableState.update {
            if (it.completedProjectId == id) it.copy(completedProjectId = null) else it
        }
    }

    override fun onCleared() {
        detach()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectOnboardingScreen(
    repository: T3Repository,
    connectionKey: String,
    onBack: () -> Unit,
    onProjectReady: (String) -> Unit,
    initialMode: ProjectOnboardingMode = ProjectOnboardingMode.Clone,
) {
    val model: ProjectOnboardingViewModel =
        viewModel(
            key = "project-onboarding:$connectionKey",
            factory = viewModelFactory { initializer { ProjectOnboardingViewModel(repository) } },
        )
    val state by model.state.collectAsStateWithLifecycle()
    val environment by repository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var mode by rememberSaveable(connectionKey) { mutableStateOf(initialMode) }
    var title by rememberSaveable(connectionKey) { mutableStateOf("") }
    var remote by rememberSaveable(connectionKey) { mutableStateOf("") }
    var destination by rememberSaveable(connectionKey) { mutableStateOf("~/") }
    var cloneParent by rememberSaveable(connectionKey) { mutableStateOf("~/") }
    var provider by rememberSaveable(connectionKey) { mutableStateOf<String?>(null) }
    var protocol by rememberSaveable(connectionKey) { mutableStateOf("auto") }
    var createMissing by rememberSaveable(connectionKey) { mutableStateOf(false) }
    var publishNew by rememberSaveable(connectionKey) { mutableStateOf(false) }
    var publishProvider by rememberSaveable(connectionKey) { mutableStateOf<String?>(null) }
    var publishName by rememberSaveable(connectionKey) { mutableStateOf("") }
    var visibility by rememberSaveable(connectionKey) { mutableStateOf("private") }
    val connected = environment.connectionStatus == ConnectionStatus.Connected
    val canRead = connected && "orchestration:read" in environment.scopes
    val canClone = connected && "source-control:write" in environment.scopes
    val trackedClones =
        environment.serverConfig
            ?.obj("environment")
            ?.obj("capabilities")
            ?.bool("projectCloneTracking") == true
    val canCreate = connected && "orchestration:operate" in environment.scopes
    val canAuth = connected && "settings:write" in environment.scopes
    DisposableEffect(model, canRead, trackedClones) {
        if (canRead) model.attach()
        onDispose { model.detach() }
    }
    LaunchedEffect(state.completedProjectId) {
        state.completedProjectId?.let { id ->
            onProjectReady(id)
            model.consumeCompletion(id)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add project") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = model::refresh, enabled = canRead && !state.busy) {
                        Text("Refresh")
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProjectOnboardingMode.entries.forEach { choice ->
                    FilterChip(
                        selected = mode == choice,
                        onClick = { mode = choice },
                        enabled = !state.busy,
                        label = { Text(choice.title) },
                    )
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (!connected) Text("Reconnect to this environment to add projects.")
                when (mode) {
                    ProjectOnboardingMode.Clone -> {
                        Text("Clone into a folder on the connected environment.")
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = provider == null,
                                onClick = { provider = null },
                                label = { Text("Git URL") },
                            )
                            state.hosts.forEach { host ->
                                FilterChip(
                                    selected = provider == host.kind,
                                    onClick = { provider = host.kind },
                                    enabled = host.available && host.authenticated,
                                    label = { Text(host.label) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = remote,
                            onValueChange = { value ->
                                val oldName = projectCloneDirectoryName(remote)
                                val name = projectCloneDirectoryName(value)
                                if (
                                    destination.isBlank() ||
                                        destination == cloneDestination(cloneParent, oldName)
                                )
                                    destination = cloneDestination(cloneParent, name)
                                if (title.isBlank() || title == oldName) title = name
                                remote = value
                            },
                            label = {
                                Text(
                                    if (provider == null) "Git URL or owner/repository"
                                    else "Owner/repository"
                                )
                            },
                            enabled = !state.busy && canClone,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Project name") },
                            enabled = !state.busy && canClone,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = destination,
                            onValueChange = { destination = it },
                            label = { Text("Destination folder on environment") },
                            supportingText = {
                                Text(
                                    "Use the complete destination path, including the new repository folder."
                                )
                            },
                            enabled = !state.busy && canClone,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        HostFolderPicker(
                            state.folders,
                            cloneParent,
                            canRead && "filesystem:read" in environment.scopes && !state.busy,
                            model::browse,
                        ) {
                            cloneParent = it
                            destination = cloneDestination(it, projectCloneDirectoryName(remote))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("auto", "ssh", "https").forEach { value ->
                                FilterChip(
                                    selected = protocol == value,
                                    onClick = { protocol = value },
                                    label = { Text(value.uppercase()) },
                                )
                            }
                        }
                        Button(
                            onClick = {
                                model.clone(title, destination, remote, provider, protocol)
                            },
                            enabled =
                                !state.busy &&
                                    canClone &&
                                    trackedClones &&
                                    title.isNotBlank() &&
                                    remote.isNotBlank() &&
                                    destination.isNotBlank(),
                        ) {
                            Text(if (state.busy) "Starting clone…" else "Clone repository")
                        }
                        if (!canClone)
                            Text(
                                "This connection needs source-control write permission to clone.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        if (!trackedClones)
                            Text(
                                "This environment needs a newer server with clone progress support.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                    ProjectOnboardingMode.New -> {
                        Text(
                            "Create a Git repository in this environment’s configured new-project directory."
                        )
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Project name") },
                            enabled = !state.busy && canCreate,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Row {
                            Checkbox(
                                checked = publishNew,
                                onCheckedChange = { publishNew = it },
                                enabled = canClone && !state.busy,
                            )
                            Text("Publish the new repository")
                        }
                        if (publishNew)
                            PublishFields(
                                state.hosts,
                                publishProvider,
                                publishName,
                                visibility,
                                !state.busy && canClone,
                                { publishProvider = it },
                                { publishName = it },
                                { visibility = it },
                            )
                        Button(
                            onClick = {
                                model.createNew(
                                    title,
                                    if (publishNew) publishProvider else null,
                                    publishName,
                                    visibility,
                                )
                            },
                            enabled =
                                !state.busy &&
                                    canCreate &&
                                    title.isNotBlank() &&
                                    (!publishNew ||
                                        canClone &&
                                            publishProvider != null &&
                                            publishName.isNotBlank()),
                        ) {
                            Text(if (state.busy) "Creating project…" else "Create Git project")
                        }
                    }
                    ProjectOnboardingMode.Folder -> {
                        Text("Import a workspace on the environment. Files stay on that host.")
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Project name") },
                            enabled = !state.busy && canCreate,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = destination,
                            onValueChange = { destination = it },
                            label = { Text("Workspace folder") },
                            enabled = !state.busy && canCreate,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        HostFolderPicker(
                            state.folders,
                            destination,
                            canRead && "filesystem:read" in environment.scopes && !state.busy,
                            model::browse,
                        ) {
                            destination = it
                        }
                        Row {
                            Checkbox(
                                checked = createMissing,
                                onCheckedChange = { createMissing = it },
                                enabled = !state.busy && canCreate,
                            )
                            Text("Create the folder if it does not exist")
                        }
                        Button(
                            onClick = { model.importFolder(title, destination, createMissing) },
                            enabled =
                                !state.busy &&
                                    canCreate &&
                                    title.isNotBlank() &&
                                    destination.isNotBlank(),
                        ) {
                            Text(if (state.busy) "Adding project…" else "Import folder")
                        }
                    }
                    ProjectOnboardingMode.Accounts -> {
                        state.hosts.forEach { host ->
                            var hostName by
                                rememberSaveable(connectionKey, host.kind) {
                                    mutableStateOf(
                                        host.host
                                            ?: when (host.kind) {
                                                "github" -> "github.com"
                                                "gitlab" -> "gitlab.com"
                                                else -> ""
                                            }
                                    )
                                }
                            Text(host.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (host.authenticated)
                                    "Signed in${host.account?.let { " as $it" }.orEmpty()}"
                                else
                                    host.detail
                                        ?: if (host.available) "Not signed in"
                                        else host.installHint,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (host.appSignIn) {
                                val appSignedIn =
                                    environment.serverConfig
                                        ?.obj("settings")
                                        ?.obj("sourceControlHosts")
                                        ?.obj(host.kind)
                                        ?.obj("signIns")
                                        ?.string(hostName)
                                        ?.isNotBlank() == true
                                OutlinedTextField(
                                    value = hostName,
                                    onValueChange = { hostName = it },
                                    label = { Text("Host") },
                                    enabled = canAuth && !state.busy && state.signIn == null,
                                    singleLine = true,
                                )
                                if (appSignedIn)
                                    OutlinedButton(
                                        onClick = { model.signOut(host.kind, hostName) },
                                        enabled = canAuth && !state.busy,
                                    ) {
                                        Text("Sign out")
                                    }
                                else
                                    OutlinedButton(
                                        onClick = { model.signIn(host.kind, hostName) },
                                        enabled =
                                            canAuth &&
                                                !state.busy &&
                                                state.signIn == null &&
                                                hostName.isNotBlank(),
                                    ) {
                                        Text("Sign in through ${host.label}")
                                    }
                            }
                            HorizontalDivider()
                        }
                        state.signIn?.let { prompt ->
                            Card {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        "Enter this code in your browser",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    SelectionContainer {
                                        Text(
                                            prompt.code,
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontFamily = T3MonoFontFamily,
                                        )
                                    }
                                    Text(
                                        "Expires ${DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(prompt.expiresAt))}. Sign-in continues on the environment when you leave this screen.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row {
                                        TextButton(
                                            onClick = {
                                                context
                                                    .getSystemService(ClipboardManager::class.java)
                                                    .setPrimaryClip(
                                                        ClipData.newPlainText(
                                                            "Source-control sign-in code",
                                                            prompt.code,
                                                        )
                                                    )
                                            }
                                        ) {
                                            Text("Copy code")
                                        }
                                        TextButton(
                                            onClick = {
                                                if (
                                                    providerBrowserUrlAllowed(
                                                        prompt.verificationUri
                                                    )
                                                )
                                                    context.startActivity(
                                                        Intent(
                                                            Intent.ACTION_VIEW,
                                                            Uri.parse(prompt.verificationUri),
                                                        )
                                                    )
                                            }
                                        ) {
                                            Text("Open browser")
                                        }
                                        TextButton(
                                            onClick = model::cancelSignIn,
                                            enabled = !state.busy,
                                        ) {
                                            Text("Cancel")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.created != null) {
                    val created = state.created!!
                    SelectionContainer {
                        Text(
                            "Workspace: ${created.workspaceRoot}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.createdReady)
                        OutlinedButton(
                            onClick = { model.openProject(created.projectId) },
                            enabled = !state.busy,
                        ) {
                            Text("Open project")
                        }
                    if (
                        mode == ProjectOnboardingMode.New &&
                            state.createdFromNew &&
                            publishNew &&
                            publishProvider != null &&
                            !state.published
                    )
                        TextButton(
                            onClick = {
                                model.publishCreated(
                                    publishProvider.orEmpty(),
                                    publishName,
                                    visibility,
                                )
                            },
                            enabled = canClone && !state.busy && publishName.isNotBlank(),
                        ) {
                            Text("Publish created project")
                        }
                }
                state.clones.forEach { clone ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SelectionContainer { Text(clone.destination) }
                            Text(
                                "${clone.phase} · ${projectCloneSummary(clone)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            clone.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row {
                                if (clone.phase == "running")
                                    TextButton(
                                        onClick = { model.cloneAction(clone.projectId, false) },
                                        enabled = canClone && !state.busy,
                                    ) {
                                        Text("Cancel clone")
                                    }
                                if (clone.phase in setOf("failed", "cancelled"))
                                    TextButton(
                                        onClick = { model.cloneAction(clone.projectId, true) },
                                        enabled = canClone && !state.busy,
                                    ) {
                                        Text("Retry clone")
                                    }
                                if (clone.phase == "done")
                                    TextButton(
                                        onClick = { model.openProject(clone.projectId) },
                                        enabled = !state.busy,
                                    ) {
                                        Text("Open project")
                                    }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostFolderPicker(
    listing: ProjectFolderListing?,
    path: String,
    enabled: Boolean,
    onBrowse: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    OutlinedButton(onClick = { onBrowse(path.ifBlank { "~/" }) }, enabled = enabled) {
        Text("Browse folders on environment")
    }
    if (listing != null) {
        TextButton(onClick = { onSelect(listing.parent) }, enabled = enabled) {
            Text("Use ${listing.parent}")
        }
        listing.folders.forEach { folder ->
            TextButton(
                onClick = {
                    onSelect(folder.path)
                    onBrowse(folder.path)
                },
                enabled = enabled,
            ) {
                Text(folder.name)
            }
        }
    }
}

@Composable
private fun PublishFields(
    hosts: List<SourceControlHost>,
    selected: String?,
    name: String,
    visibility: String,
    enabled: Boolean,
    onProvider: (String) -> Unit,
    onName: (String) -> Unit,
    onVisibility: (String) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        hosts
            .filter { it.available && it.authenticated }
            .forEach { host ->
                FilterChip(
                    selected = selected == host.kind,
                    onClick = { onProvider(host.kind) },
                    enabled = enabled,
                    label = { Text(host.label) },
                )
            }
    }
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text("Repository name, including owner when required") },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("private", "public").forEach { value ->
            FilterChip(
                selected = visibility == value,
                onClick = { onVisibility(value) },
                enabled = enabled,
                label = { Text(value.replaceFirstChar(Char::uppercase)) },
            )
        }
    }
}
