package com.example.teethree

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.teethree.awareness.AgentAwarenessRegistration
import com.example.teethree.awareness.AgentNotifications
import com.example.teethree.awareness.AwarenessRoute
import com.example.teethree.awareness.SubscriptionWidgetPublisher
import com.example.teethree.awareness.buildSubscriptionUsageSnapshot
import com.example.teethree.data.AndroidContextClipboard
import com.example.teethree.data.AttachmentTransfers
import com.example.teethree.data.CachedEnvironment
import com.example.teethree.data.ChatAttachment
import com.example.teethree.data.ClipboardAttachmentSource
import com.example.teethree.data.ClipboardByteStore
import com.example.teethree.data.ClipboardSource
import com.example.teethree.data.ClipboardSourceBytes
import com.example.teethree.data.ClipboardTarget
import com.example.teethree.data.ComposerContextSelection
import com.example.teethree.data.ComposerDraft
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.ContextClipboardImporter
import com.example.teethree.data.EnvironmentTools
import com.example.teethree.data.IncomingShareImporter
import com.example.teethree.data.IncomingShareInbox
import com.example.teethree.data.IncomingSharedDraft
import com.example.teethree.data.ModelSelection
import com.example.teethree.data.PreparedContextPaste
import com.example.teethree.data.RepositoryState
import com.example.teethree.data.SavedConnection
import com.example.teethree.data.T3Repository
import com.example.teethree.data.ThreadLaunchPartialFailure
import com.example.teethree.data.WorkspaceEnvironmentManager
import com.example.teethree.data.WorkspaceProjectRef
import com.example.teethree.data.WorkspaceThreadRef
import com.example.teethree.data.appendComposerContext
import com.example.teethree.data.array
import com.example.teethree.data.auth.CloudHttpException
import com.example.teethree.data.auth.CloudIdentity
import com.example.teethree.data.auth.NativeCloudConnections
import com.example.teethree.data.clipboardCopyOfDraft
import com.example.teethree.data.composerEditableText
import com.example.teethree.data.decodeContextClipboard
import com.example.teethree.data.long
import com.example.teethree.data.mergeIncomingShareText
import com.example.teethree.data.mergePreparedContextPaste
import com.example.teethree.data.mergePreparedIncomingShare
import com.example.teethree.data.nullableString
import com.example.teethree.data.obj
import com.example.teethree.data.prepareDraftDispatch
import com.example.teethree.data.referencedComposerContext
import com.example.teethree.data.removePreparedContextPaste
import com.example.teethree.data.replaceComposerDraftText
import com.example.teethree.data.string
import com.example.teethree.data.updateBackgroundOfflineCache
import com.example.teethree.data.updateOfflineCache
import com.example.teethree.ui.AppTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

enum class AppAction {
    Pair,
    LaunchConversation,
    CreateProject,
    ImportShare,
    OpenWorkspaceTask,
}

data class AppActionCompletion(
    val id: String,
    val action: AppAction,
    val connectionId: String?,
    val projectId: String? = null,
)

data class AppState(
    val connections: List<SavedConnection> = emptyList(),
    val activeConnectionId: String? = null,
    val drafts: Map<String, ComposerDraft> = emptyMap(),
    val theme: AppTheme = AppTheme.System,
    val busy: Boolean = false,
    val busyLabel: String? = null,
    val initialized: Boolean = false,
    val error: String? = null,
    val actionCompletion: AppActionCompletion? = null,
    val restoringSelection: Boolean = false,
    val preferences: ClientPreferences = ClientPreferences(),
    val cacheBytes: Long = 0,
    val incomingShares: List<IncomingSharedDraft> = emptyList(),
    val selectedIncomingShareId: String? = null,
    val stagingShare: Boolean = false,
    /** Messages waiting for the environment to come back, oldest first, keyed like drafts. */
    val outbox: Map<String, List<ComposerDraft>> = emptyMap(),
    /** How many queued messages went out on the last reconnect. */
    val outboxSent: Int = 0,
    /** Approvals and answers given while connecting, keyed by request id; sent once connected. */
    val pendingReplies: Map<String, PendingReply> = emptyMap(),
)

/** A reply to an agent's request, held until the link is live. */
data class PendingReply(
    val threadId: String,
    val decision: String? = null,
    val answers: Map<String, List<String>>? = null,
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class AppViewModel(application: Application, private val savedState: SavedStateHandle) :
    AndroidViewModel(application) {
    private val store = ConnectionStore(application)
    val cloud =
        NativeCloudConnections(
            application,
            viewModelScope,
            store,
            beforeHttpAccess = { origin -> ensureLocalNetworkAccess(application, origin) },
        )
    val awareness =
        AgentAwarenessRegistration(application, viewModelScope, cloud.account.state, cloud.relay)
    private val mutableUsage =
        MutableStateFlow(SubscriptionWidgetPublisher.cachedSnapshot(application))
    val usage = mutableUsage.asStateFlow()
    private val usageConfigs = mutableMapOf<String, JsonObject>()
    val usageConfigurations: Map<String, JsonObject>
        get() = usageConfigs.toMap()

    private var usageRefreshJob: Job? = null
    private val environmentHttp = okhttp3.OkHttpClient()
    val repository =
        T3Repository(
            viewModelScope,
            client = environmentHttp,
            beforeHttpAccess = { origin -> ensureLocalNetworkAccess(application, origin) },
            authorization = cloud.authorization,
        )
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private val draftWrites = Channel<Map<String, ComposerDraft>>(Channel.CONFLATED)
    private val outboxWrites = Channel<Map<String, List<ComposerDraft>>>(Channel.CONFLATED)
    private val outboxLock = Mutex()
    private val preferenceWrites = Channel<ClientPreferences>(Channel.CONFLATED)
    private val draftWriteLock = Mutex()
    private val connectionWriteLock = Mutex()
    private val inbox = IncomingShareInbox(application)
    private val shareImporter = IncomingShareImporter(repository, inbox)
    private val shareDraftWrites = Channel<Unit>(Channel.CONFLATED)
    private val pendingShareWrites = mutableMapOf<String, IncomingSharedDraft>()
    private var pendingShareStages = 0
    private val transfers = AttachmentTransfers(repository)
    private val contextClipboard = AndroidContextClipboard(application)
    private val clipboardBytes =
        ClipboardByteStore(File(application.filesDir, "context-clipboard-bytes"))
    private val contextImporter by lazy {
        ContextClipboardImporter(
            repository,
            File(application.cacheDir, "context-clipboard-imports"),
            ClipboardAttachmentSource(::openClipboardSource),
        )
    }
    private var exclusiveJob: Job? = null
    private var selectionRestoreJob: Job? = null
    private var selectionRestoreVersion = 0L
    private val offlineCopies = mutableMapOf<String, CachedEnvironment>()
    private val workspaceForeground = MutableStateFlow(false)
    private val workspaceArchived = MutableStateFlow(false)
    private val cacheFailures = mutableSetOf<String>()
    val workspaces =
        WorkspaceEnvironmentManager(
            viewModelScope,
            repository,
            createBackgroundRepository = { scope, includeArchived ->
                T3Repository(
                    scope,
                    client = environmentHttp,
                    beforeHttpAccess = { origin -> ensureLocalNetworkAccess(application, origin) },
                    authorization = cloud.authorization,
                    includeArchived = includeArchived,
                )
            },
            loadCache = { connection ->
                offlineCopies[connection.id]
                    ?: withContext(Dispatchers.IO) { store.offlineCache(connection.id) }
            },
            onShell = ::persistWorkspaceShell,
            onRoutes = { connection, routes, stillOwned ->
                updateConnections { saved ->
                    if (!stillOwned()) saved
                    else
                        saved.map { current ->
                            if (
                                current.id == connection.id &&
                                    current.cloudAccountId == connection.cloudAccountId &&
                                    (current.environmentId == null ||
                                        current.environmentId == connection.environmentId)
                            )
                                current.copy(
                                    environmentId = connection.environmentId,
                                    learnedRoutes = routes,
                                )
                            else current
                        }
                }
            },
        )

    init {
        cloud.beforeSignOut = awareness::beforeSignOut
        viewModelScope.launch {
            combine(
                    state
                        .map { Triple(it.connections, it.activeConnectionId, it.initialized) }
                        .distinctUntilChanged(),
                    cloud.account.state.map { it.identity }.distinctUntilChanged(),
                    workspaceForeground,
                    workspaceArchived,
                ) { saved, account, foreground, archived ->
                    if (saved.third)
                        workspaces.update(saved.first, saved.second, account, foreground, archived)
                }
                .collect {}
        }
        viewModelScope.launch {
            try {
                val saved =
                    withContext(Dispatchers.IO) {
                        AppState(
                            connections = store.connections(),
                            activeConnectionId = store.activeConnectionId,
                            drafts = store.drafts(),
                            outbox = store.outbox(),
                            theme = store.theme,
                            preferences = store.clientPreferences(),
                            cacheBytes = store.offlineCacheBytes(),
                            incomingShares = inbox.list(),
                            selectedIncomingShareId = savedState.get<String>("incoming-share"),
                            initialized = true,
                        )
                    }
                val connection = saved.connections.find { it.id == saved.activeConnectionId }
                mutableState.value =
                    saved.copy(
                        restoringSelection =
                            connection != null &&
                                savedState.get<String>("selected-thread:${connection.id}") != null
                    )
                if (connection != null) connectRepository(connection)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(
                        initialized = true,
                        error =
                            if (error is CloudHttpException) error.message
                            else
                                "Saved app data could not be opened. Pair your environment again to recover.",
                    )
                }
            }
        }
        viewModelScope.launch {
            for (signal in shareDraftWrites) {
                val batch = pendingShareWrites.values.toList()
                pendingShareWrites.clear()
                try {
                    for (draft in batch) if (state.value.incomingShares.any { it.id == draft.id })
                        inbox.save(draft) { state.value.incomingShares.any { it.id == draft.id } }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mutableState.update {
                        it.copy(error = "Your shared draft could not be saved on this device.")
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (preferences in preferenceWrites) {
                try {
                    store.saveClientPreferences(preferences)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mutableState.update { it.copy(error = "Your preferences could not be saved.") }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (drafts in draftWrites) {
                try {
                    persistDrafts()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(error = "Your draft could not be saved on this device.")
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            for (outbox in outboxWrites) {
                try {
                    store.saveOutbox(outbox)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.update {
                        it.copy(error = "Your queued messages could not be saved on this device.")
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.state
                .map {
                    it.connectionStatus == ConnectionStatus.Connected && it.shellReady
                }
                .distinctUntilChanged()
                .collect { live ->
                    if (live) {
                        flushReplies()
                        flushOutbox()
                    } else if (state.value.outboxSent > 0)
                        mutableState.update { it.copy(outboxSent = 0) }
                }
        }
        viewModelScope.launch {
            repository.state
                .map { repository.workspaceObservation() }
                .filter {
                    it.state.shellReady && it.state.connectionStatus == ConnectionStatus.Connected
                }
                .sample(750)
                .collect { observation ->
                    val connection = observation.connection ?: return@collect
                    val connectionId = connection.id
                    val observed = observation.state
                    fun stillOwned() =
                        repository.connectionId == connectionId &&
                            repository.connectionGeneration == observation.generation &&
                            state.value.activeConnectionId == connectionId &&
                            (connection.cloudAccountId == null ||
                                cloud.account.state.value.identity?.accountId ==
                                    connection.cloudAccountId)
                    if (!stillOwned()) return@collect
                    val previous = offlineCopies[connectionId]
                    val cached =
                        withContext(Dispatchers.Default) { updateOfflineCache(previous, observed) }
                    if (!stillOwned()) return@collect
                    offlineCopies[connectionId] = cached
                    repository.retainOfflineCache(cached)
                    try {
                        withContext(Dispatchers.IO) {
                            store.saveOfflineCache(connectionId, cached, ::stillOwned)
                        }
                        val bytes = withContext(Dispatchers.IO) { store.offlineCacheBytes() }
                        mutableState.update { it.copy(cacheBytes = bytes) }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        mutableState.update {
                            it.copy(
                                error =
                                    "The offline conversation copy could not be saved on this device."
                            )
                        }
                    }
                }
        }
        viewModelScope.launch {
            repository.state
                .map {
                    repository.workspaceObservation().let {
                        Triple(it.connection, it.generation, it.state.serverConfig)
                    }
                }
                .distinctUntilChanged()
                .collect { (connection, version, config) ->
                    val id = connection?.id ?: return@collect
                    if (config == null) return@collect
                    val descriptor = config["environment"] as? JsonObject
                    val environmentId =
                        (descriptor?.get("environmentId")
                                as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content
                    if (!environmentId.isNullOrBlank()) {
                        updateConnections { saved ->
                            if (
                                repository.connectionId != id ||
                                    repository.connectionGeneration != version
                            )
                                saved
                            else
                                saved.map { connection ->
                                    if (connection.id == id && connection.environmentId == null)
                                        connection.copy(
                                            environmentId = environmentId,
                                            label =
                                                (descriptor["label"]
                                                        as?
                                                        kotlinx.serialization.json.JsonPrimitive)
                                                    ?.content,
                                        )
                                    else connection
                                }
                        }
                    }
                    if (repository.connectionId != id || repository.connectionGeneration != version)
                        return@collect
                    usageConfigs[id] = config
                    publishUsage()
                }
        }
        viewModelScope.launch {
            repository.learnedRoutes.collect { learned ->
                if (learned == null) return@collect
                updateConnections { saved ->
                    val authenticatedId =
                        repository.state.value.serverConfig
                            ?.obj("environment")
                            ?.nullableString("environmentId")
                    if (
                        repository.connectionId != learned.connectionId ||
                            repository.connectionGeneration != learned.generation ||
                            authenticatedId != learned.environmentId ||
                            (learned.cloudAccountId != null &&
                                cloud.account.state.value.identity?.accountId !=
                                    learned.cloudAccountId)
                    )
                        saved
                    else
                        saved.map { connection ->
                            if (
                                connection.id == learned.connectionId &&
                                    connection.cloudAccountId == learned.cloudAccountId &&
                                    (connection.environmentId == null ||
                                        connection.environmentId == learned.environmentId)
                            )
                                connection.copy(
                                    environmentId = learned.environmentId,
                                    learnedRoutes = learned.routes,
                                )
                            else connection
                        }
                }
            }
        }
        viewModelScope.launch {
            combine(cloud.account.state, state.map { it.initialized }.distinctUntilChanged()) {
                    account,
                    ready ->
                    account to ready
                }
                .filter { it.first.initialized && it.second }
                .map { it.first.identity?.accountId }
                .distinctUntilChanged()
                .collect { accountId ->
                    val active =
                        state.value.connections.find { it.id == state.value.activeConnectionId }
                    if (active?.cloudAccountId != null && active.cloudAccountId != accountId)
                        disconnect()
                    val staleIds =
                        state.value.connections
                            .filter { it.cloudAccountId != null && it.cloudAccountId != accountId }
                            .map { it.id }
                            .toSet()
                    staleIds.forEach {
                        offlineCopies.remove(it)
                        usageConfigs.remove(it)
                    }
                    state.value.connections
                        .filter { it.id in staleIds }
                        .forEach { clearClipboardOwner(it) }
                    try {
                        withContext(Dispatchers.IO) {
                            staleIds.forEach {
                                store.removeOfflineCache(it)
                                store.removePrefix("review-draft:$it:")
                            }
                        }
                        mutableState.update { current ->
                            current.copy(
                                drafts =
                                    current.drafts.filterKeys { key ->
                                        staleIds.none { key.startsWith("$it:") }
                                    }
                            )
                        }
                        draftWrites.trySend(state.value.drafts)
                        if (staleIds.isNotEmpty()) publishUsage()
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        mutableState.update {
                            it.copy(
                                error =
                                    "The previous account's offline data could not be cleared from this device."
                            )
                        }
                    }
                }
        }
    }

    private suspend fun persistDrafts() = draftWriteLock.withLock {
        withContext(Dispatchers.IO) { store.saveDrafts(state.value.drafts) }
    }

    private suspend fun persistWorkspaceShell(
        connection: SavedConnection,
        observed: RepositoryState,
        includeArchived: Boolean,
        stillOwned: () -> Boolean,
    ) {
        if (!stillOwned()) return
        try {
            val previous =
                offlineCopies[connection.id]
                    ?: withContext(Dispatchers.IO) { store.offlineCache(connection.id) }
            val cached =
                withContext(Dispatchers.Default) {
                    updateBackgroundOfflineCache(previous, observed, includeArchived)
                }
            if (!stillOwned()) return
            offlineCopies[connection.id] = cached
            withContext(Dispatchers.IO) {
                store.saveOfflineCache(connection.id, cached, stillOwned)
            }
            cacheFailures.remove(connection.id)
            if (stillOwned())
                observed.serverConfig
                    ?.takeIf { usageConfigs[connection.id] != it }
                    ?.let { config ->
                        usageConfigs[connection.id] = config
                        publishUsage()
                    }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (stillOwned() && cacheFailures.add(connection.id))
                mutableState.update {
                    it.copy(error = "An environment's offline copy could not be saved.")
                }
        }
    }

    private suspend fun updateConnections(
        transform: (List<SavedConnection>) -> List<SavedConnection>
    ): List<SavedConnection> = connectionWriteLock.withLock {
        val previous = state.value.connections
        val connections = transform(previous)
        if (connections != previous) {
            withContext(Dispatchers.IO) { store.saveConnections(connections) }
            mutableState.update { it.copy(connections = connections) }
        }
        connections
    }

    fun stageIncomingShare(intent: Intent) {
        val incoming = Intent(intent)
        viewModelScope.launch {
            state.first { it.initialized }
            pendingShareStages += 1
            mutableState.update { it.copy(stagingShare = true) }
            try {
                val draft = inbox.stage(incoming) ?: return@launch
                savedState["incoming-share"] = draft.id
                mutableState.update {
                    it.copy(
                        incomingShares =
                            (listOf(draft) + it.incomingShares).distinctBy(IncomingSharedDraft::id),
                        selectedIncomingShareId = draft.id,
                    )
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.update {
                    it.copy(error = failure.message ?: "This share could not be opened.")
                }
            } finally {
                pendingShareStages -= 1
                mutableState.update { it.copy(stagingShare = pendingShareStages > 0) }
            }
        }
    }

    fun selectIncomingShare(id: String?) {
        if (id != null && state.value.incomingShares.none { it.id == id }) return
        if (id == null) savedState.remove<String>("incoming-share")
        else savedState["incoming-share"] = id
        mutableState.update { it.copy(selectedIncomingShareId = id) }
    }

    fun updateIncomingShare(draft: IncomingSharedDraft) {
        if (draft.text.length > 1_000_000) {
            mutableState.update { it.copy(error = "Shared drafts support at most 1 MB of text.") }
            return
        }
        val current = state.value.incomingShares.find { it.id == draft.id } ?: return
        val updated = com.example.teethree.data.editIncomingShare(current, draft)
        mutableState.update {
            it.copy(
                incomingShares =
                    it.incomingShares.map { item -> if (item.id == updated.id) updated else item }
            )
        }
        pendingShareWrites[updated.id] = updated
        shareDraftWrites.trySend(Unit)
    }

    fun discardIncomingShare(id: String) = action {
        mutableState.update {
            it.copy(incomingShares = it.incomingShares.filterNot { draft -> draft.id == id })
        }
        if (state.value.selectedIncomingShareId == id) selectIncomingShare(null)
        pendingShareWrites.remove(id)
        inbox.discard(id)
    }

    fun importIncomingShare(projectId: String) =
        action(label = "Importing shared files…") {
            val source =
                state.value.incomingShares.find { it.id == state.value.selectedIncomingShareId }
                    ?: error("Choose a saved share to import.")
            check(repository.state.value.projects.any { it.id == projectId && !it.archived }) {
                "Choose an available project."
            }
            val connectionId = repository.connectionId ?: error("Connect to an environment first.")
            val generation = repository.connectionGeneration
            val key = draftKey(null)
            var prepared: com.example.teethree.data.PreparedIncomingShare? = null
            var merged = false
            try {
                val existing = state.value.drafts[key] ?: ComposerDraft()
                if (source.id !in existing.importedShareIds) {
                    require(mergeIncomingShareText(existing.text, source.text).length <= 120_000) {
                        "Shorten the shared text so the combined task fits within 120,000 characters."
                    }
                    require(existing.attachments.size + source.files.size <= 100) {
                        "A message can have up to 100 attachments."
                    }
                    prepared = shareImporter.prepare(source)
                    check(
                        repository.connectionId == connectionId &&
                            repository.connectionGeneration == generation &&
                            state.value.activeConnectionId == connectionId
                    ) {
                        "The active environment changed. Import the share again."
                    }
                    val current = state.value.drafts[key] ?: ComposerDraft()
                    saveDraft(key, mergePreparedIncomingShare(current, prepared))
                    merged = true
                }
                persistDrafts()
                check(
                    repository.connectionId == connectionId &&
                        repository.connectionGeneration == generation &&
                        state.value.drafts[key]?.importedShareIds?.contains(source.id) == true
                ) {
                    "The active environment changed. The share is still saved for later."
                }
                pendingShareWrites.remove(source.id)
                val selected = state.value.selectedIncomingShareId == source.id
                mutableState.update {
                    it.copy(
                        incomingShares =
                            it.incomingShares.filterNot { draft -> draft.id == source.id }
                    )
                }
                inbox.discard(source.id)
                if (selected) {
                    selectIncomingShare(null)
                    completeAction(AppAction.ImportShare, projectId)
                }
            } catch (failure: Throwable) {
                if (!merged && prepared != null)
                    withContext(NonCancellable) { shareImporter.rollback(prepared) }
                throw failure
            }
        }

    fun draftKey(threadId: String?) =
        "${state.value.activeConnectionId.orEmpty()}:${threadId ?: "new"}"

    fun updateDraft(threadId: String?, text: String) {
        val key = draftKey(threadId)
        val draft = state.value.drafts[key] ?: ComposerDraft()
        saveDraft(key, draft.copy(text = text))
    }

    fun insertComposerContext(selection: ComposerContextSelection) =
        action(exclusive = false) {
            if (
                repository.connectionId != selection.connectionId ||
                    repository.connectionGeneration != selection.connectionGeneration ||
                    (selection.ownerThreadId != null &&
                        repository.state.value.selectedThreadId != selection.ownerThreadId)
            )
                throw CancellationException("The conversation changed.")
            val key = draftKey(selection.ownerThreadId)
            check(
                repository.state.value.threads.none {
                    it.id == selection.ownerThreadId &&
                        com.example.teethree.data.providerManagedChild(it)
                }
            ) {
                "Reply in this agent's parent conversation."
            }
            saveDraft(
                key,
                appendComposerContext(
                    state.value.drafts[key] ?: ComposerDraft(),
                    selection.records,
                ),
            )
        }

    fun removeComposerContext(threadId: String?, contextId: String) {
        val key = draftKey(threadId)
        state.value.drafts[key]?.let { draft ->
            saveDraft(key, com.example.teethree.data.removeComposerContext(draft, contextId))
        }
    }

    private data class ClipboardOwner(
        val connection: SavedConnection,
        val target: ClipboardTarget,
        val key: String,
        val account: CloudIdentity?,
    )

    private fun clipboardOwner(threadId: String?): ClipboardOwner {
        val connection =
            state.value.connections.firstOrNull { it.id == state.value.activeConnectionId }
                ?: error("Choose an environment first.")
        check(
            connection.cloudAccountId == null ||
                connection.cloudAccountId == cloud.account.state.value.identity?.accountId
        ) {
            "The T3 Connect account changed."
        }
        check(
            repository.connectionId == connection.id &&
                (threadId == null || repository.state.value.selectedThreadId == threadId)
        ) {
            "The conversation changed."
        }
        val environmentId =
            repository.state.value.serverConfig?.obj("environment")?.nullableString("environmentId")
                ?: connection.environmentId
                ?: error("Reconnect to identify this environment.")
        return ClipboardOwner(
            connection,
            ClipboardTarget(
                connection.id,
                repository.connectionGeneration,
                threadId,
                environmentId,
            ),
            "${connection.id}:${threadId ?: "new"}",
            cloud.account.state.value.identity.takeIf { connection.cloudAccountId != null },
        )
    }

    private fun ensureClipboardOwner(owner: ClipboardOwner) {
        val current = clipboardOwner(owner.target.threadId)
        if (
            !clipboardSessionMatches(current.connection, owner.connection) ||
                current.target != owner.target ||
                current.account != owner.account
        )
            throw CancellationException("The clipboard destination changed.")
    }

    private fun clipboardSessionMatches(a: SavedConnection, b: SavedConnection): Boolean =
        a.id == b.id &&
            a.baseUrl == b.baseUrl &&
            a.wsBaseUrl == b.wsBaseUrl &&
            a.accessToken == b.accessToken &&
            a.tokenType == b.tokenType &&
            a.expiresAt == b.expiresAt &&
            a.cloudAccountId == b.cloudAccountId &&
            a.scopes == b.scopes

    private fun clipboardOwnerStillStored(owner: ClipboardOwner): Boolean =
        state.value.connections.any {
            clipboardSessionMatches(it, owner.connection) &&
                (it.environmentId == null || it.environmentId == owner.target.environmentId)
        } &&
            (owner.connection.cloudAccountId == null ||
                cloud.account.state.value.identity == owner.account)

    private fun clipboardOwnerBinding(connection: SavedConnection) =
        "${connection.cloudAccountId ?: "direct"}:${connection.id}"

    suspend fun clearClipboardOwner(connection: SavedConnection) {
        clipboardBytes.removeOwner(clipboardOwnerBinding(connection))
    }

    private suspend fun openClipboardSource(
        environmentId: String,
        attachment: ChatAttachment,
    ): ClipboardSourceBytes {
        val account = cloud.account.state.value.identity
        val mapped =
            workspaces.environments.value
                .filter { it.environmentId == environmentId }
                .flatMap { it.aliasConnectionIds + it.connectionId }
                .toSet()
        val candidates =
            state.value.connections
                .filter { connection ->
                    (connection.environmentId == environmentId || connection.id in mapped) &&
                        (connection.cloudAccountId == null ||
                            connection.cloudAccountId ==
                                cloud.account.state.value.identity?.accountId) &&
                        "orchestration:read" in connection.scopes
                }
                .sortedByDescending { it.id == state.value.activeConnectionId }
        for (connection in candidates) clipboardBytes
            .open(environmentId, attachment, clipboardOwnerBinding(connection))
            ?.let { bytes ->
                val current = state.value.connections.firstOrNull { it.id == connection.id }
                val owned =
                    current != null &&
                        clipboardSessionMatches(current, connection) &&
                        (current.environmentId == null || current.environmentId == environmentId) &&
                        "orchestration:read" in current.scopes &&
                        (connection.cloudAccountId == null ||
                            cloud.account.state.value.identity == account)
                if (!owned) {
                    bytes.close()
                    throw CancellationException("The source account or connection changed.")
                }
                return bytes
            }
        var failure: Exception? = null
        for (connection in candidates) {
            val directory =
                File(
                    getApplication<Application>().cacheDir,
                    "clipboard-source-${UUID.randomUUID()}",
                )
            try {
                return workspaces.withRepository(connection.id) { source ->
                    check(
                        source.state.value.serverConfig
                            ?.obj("environment")
                            ?.nullableString("environmentId") == environmentId
                    ) {
                        "The source environment identity changed."
                    }
                    check("orchestration:read" in source.state.value.scopes) {
                        "This source cannot read attachments."
                    }
                    val file = AttachmentTransfers(source).download(attachment, directory)
                    ClipboardSourceBytes(environmentId, attachment.id, file.inputStream()) {
                        directory.deleteRecursively()
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
                throw cancelled
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
                failure = error
            }
        }
        throw IllegalStateException(
            "Reconnect to the source environment to copy ${attachment.name}.",
            failure,
        )
    }

    fun copyComposerContext(threadId: String?) =
        action(label = "Copying task context…") {
            val owner = clipboardOwner(threadId)
            val draft = state.value.drafts[owner.key] ?: ComposerDraft()
            val payload =
                withContext(Dispatchers.Default) {
                    clipboardCopyOfDraft(
                        draft,
                        ClipboardSource(owner.target.environmentId, threadId),
                    )
                }
            val files =
                decodeContextClipboard(payload)
                    ?.records
                    .orEmpty()
                    .filter { it.string("kind") in setOf("image", "file") }
                    .map { record ->
                        ChatAttachment(
                            record.string("attachmentId"),
                            record.string("kind"),
                            record.string("name"),
                            record.string("mimeType"),
                            record.long("sizeBytes"),
                        )
                    }
            val binding = clipboardOwnerBinding(owner.connection)
            val previous = clipboardBytes.bindings(binding)
            var published = false
            val warnings = mutableListOf<String>()
            try {
                for (attachment in files) {
                    currentCoroutineContext().ensureActive()
                    ensureClipboardOwner(owner)
                    try {
                        clipboardBytes.retain(
                            owner.target.environmentId,
                            attachment,
                            binding,
                            openClipboardSource(owner.target.environmentId, attachment),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        warnings.add(
                            "${attachment.name} was copied as a reference. Reconnect to its source to paste its bytes."
                        )
                    }
                }
                ensureClipboardOwner(owner)
                contextClipboard.write(payload)
                published = true
                clipboardBytes.keepOnly(
                    binding,
                    files.mapTo(mutableSetOf()) { owner.target.environmentId to it.id },
                )
                if (warnings.isNotEmpty())
                    mutableState.update { it.copy(error = warnings.joinToString("\n")) }
            } finally {
                if (!published)
                    withContext(NonCancellable) { clipboardBytes.keepOnly(binding, previous) }
            }
        }

    fun pasteComposerContext(threadId: String?) =
        action(label = "Pasting task context…") {
            val owner = clipboardOwner(threadId)
            check(
                repository.state.value.threads.none {
                    it.id == threadId && com.example.teethree.data.providerManagedChild(it)
                }
            ) {
                "Reply in this agent's parent conversation."
            }
            val payload =
                contextClipboard.read()
                    ?: error("Use normal Paste for a clipboard with multiple items or no text.")
            val linked =
                if ("access:read" in repository.state.value.scopes)
                    repository
                        .callTool(
                            "peerLinks.list",
                            JsonObject(emptyMap()),
                            expectedConnectionId = owner.connection.id,
                        )
                        .jsonObject
                        .array("links")
                        .map { it.jsonObject }
                        .filter { it.string("status") != "expired" }
                        .mapNotNull { it.nullableString("environmentId") }
                        .toSet()
                else emptySet()
            ensureClipboardOwner(owner)
            var prepared: PreparedContextPaste? = null
            var accepted = false
            var merged: ComposerDraft? = null
            var previous: ComposerDraft? = null
            try {
                val snapshot = state.value.drafts[owner.key] ?: ComposerDraft()
                val imported = contextImporter.prepare(payload, owner.target, snapshot, linked)
                prepared = imported
                ensureClipboardOwner(owner)
                val before = state.value.drafts[owner.key] ?: ComposerDraft()
                previous = before
                val combined =
                    if (imported != null) mergePreparedContextPaste(before, imported)
                    else
                        before.copy(
                            text =
                                replaceComposerDraftText(
                                    before,
                                    mergeIncomingShareText(
                                        composerEditableText(before.text, before.context),
                                        payload.text,
                                    ),
                                )
                        )
                merged = combined
                com.example.teethree.data.validateMessageInput(
                    combined.text,
                    combined.attachments.map(ChatAttachment::wire),
                )
                saveDraft(owner.key, combined)
                persistDrafts()
                ensureClipboardOwner(owner)
                accepted = true
                imported
                    ?.warnings
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { warnings ->
                        mutableState.update { it.copy(error = warnings.joinToString("\n")) }
                    }
            } finally {
                val paste = prepared
                val combined = merged
                val before = previous
                var safeToDeleteUploads = combined == null
                if (!accepted && combined != null) {
                    val current = state.value.drafts[owner.key]
                    if (current == combined && before != null) saveDraft(owner.key, before)
                    else if (current != null && paste != null) {
                        saveDraft(owner.key, removePreparedContextPaste(current, paste))
                    }
                    val reverted = state.value.drafts[owner.key]
                    try {
                        withContext(NonCancellable) { persistDrafts() }
                        safeToDeleteUploads = true
                    } catch (failure: Exception) {
                        if (
                            current != null &&
                                clipboardOwnerStillStored(owner) &&
                                state.value.drafts[owner.key] == reverted
                        )
                            saveDraft(owner.key, current)
                        throw IllegalStateException(
                            "The paste could not be rolled back on this device. Its uploaded files were kept for draft recovery.",
                            failure,
                        )
                    }
                }
                if (!accepted && safeToDeleteUploads && paste != null)
                    withContext(NonCancellable) { contextImporter.rollback(paste) }
            }
        }

    private fun saveDraft(key: String, draft: ComposerDraft) {
        mutableState.update {
            it.copy(
                drafts =
                    if (draft.text.isEmpty() && draft.attachments.isEmpty()) it.drafts - key
                    else it.drafts + (key to draft)
            )
        }
        draftWrites.trySend(state.value.drafts)
    }

    fun attach(threadId: String?, uri: Uri) =
        action(label = "Uploading attachment…") {
            val key = draftKey(threadId)
            val connectionId = state.value.activeConnectionId
            val resolver = getApplication<Application>().contentResolver
            var stagedFile: File? = null
            try {
                val (file, name, mime) =
                    withContext(Dispatchers.IO) {
                        val file =
                            File.createTempFile(
                                "attachment-",
                                ".upload",
                                getApplication<Application>().cacheDir,
                            )
                        stagedFile = file
                        val name =
                            resolver
                                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { cursor ->
                                    if (cursor.moveToFirst()) cursor.getString(0) else null
                                }
                                ?.substringAfterLast('/')
                                ?.take(255)
                                ?.ifBlank { null } ?: "attachment"
                        val mime = resolver.getType(uri) ?: "application/octet-stream"
                        requireNotNull(resolver.openInputStream(uri)) {
                                "The selected file could not be opened."
                            }
                            .use { input ->
                                file.outputStream().use { output ->
                                    val buffer = ByteArray(8192)
                                    var copied = 0L
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        copied += read
                                        require(copied <= 50L * 1024 * 1024) {
                                            "Files must be 50 MiB or smaller."
                                        }
                                        output.write(buffer, 0, read)
                                    }
                                }
                            }
                        Triple(file, name, mime)
                    }
                check(state.value.activeConnectionId == connectionId) {
                    "The active environment changed. Attach the file again."
                }
                val draft = state.value.drafts[key] ?: ComposerDraft()
                require(draft.attachments.size < 100) {
                    "A message can have up to 100 attachments."
                }
                val attachment = transfers.upload(file, name, mime)
                val latest = state.value.drafts[key] ?: ComposerDraft()
                require(
                    latest.attachments.filter { it.type == "image" }.sumOf { it.sizeBytes } +
                        (if (attachment.type == "image") attachment.sizeBytes else 0) <=
                        80L * 1024 * 1024
                ) {
                    "Images in one message must total 80 MiB or less."
                }
                saveDraft(key, latest.copy(attachments = latest.attachments + attachment))
            } finally {
                stagedFile?.delete()
            }
        }

    fun removeAttachment(threadId: String?, attachmentId: String) {
        val key = draftKey(threadId)
        val draft = state.value.drafts[key] ?: return
        saveDraft(
            key,
            draft.copy(attachments = draft.attachments.filterNot { it.id == attachmentId }),
        )
    }

    fun openAttachment(attachment: ChatAttachment) =
        action(label = "Opening attachment…") {
            val application = getApplication<Application>()
            val file = transfers.download(attachment, File(application.cacheDir, "attachments"))
            val uri =
                FileProvider.getUriForFile(application, "${BuildConfig.APPLICATION_ID}.files", file)
            application.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, attachment.mimeType)
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    )
            )
        }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    fun cancelTransfer() {
        exclusiveJob?.cancel()
    }

    private fun action(
        exclusive: Boolean = true,
        label: String? = null,
        block: suspend () -> Unit,
    ) {
        if (exclusive && state.value.busy) return
        mutableState.update {
            it.copy(
                busy = it.busy || exclusive,
                busyLabel = if (exclusive) label else it.busyLabel,
                error = null,
            )
        }
        val job = viewModelScope.launch {
            try {
                block()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(error = error.message ?: "The request failed. Please try again.")
                }
            } finally {
                if (exclusive) {
                    mutableState.update { it.copy(busy = false, busyLabel = null) }
                    exclusiveJob = null
                }
            }
        }
        if (exclusive) exclusiveJob = job
    }

    fun pair(url: String, code: String) = action {
        val connection = repository.pair(url, code)
        updateConnections { saved ->
            saved.filterNot { it.id == connection.id } + connection
        }
        store.activeConnectionId = connection.id
        draftWrites.trySend(state.value.drafts)
        connectRepository(connection)
        completeAction(AppAction.Pair)
    }

    fun connect(connection: SavedConnection) = action {
        store.activeConnectionId = connection.id
        connectRepository(connection)
    }

    private fun workspaceConnection(connectionId: String, environmentId: String?): SavedConnection {
        val connection =
            state.value.connections.firstOrNull { it.id == connectionId }
                ?: error("This environment was removed. Choose another connection.")
        check(
            connection.cloudAccountId == null ||
                connection.cloudAccountId == cloud.account.state.value.identity?.accountId
        ) {
            "Sign in to the account that owns this environment."
        }
        check(
            environmentId == null ||
                connection.environmentId == environmentId ||
                (connection.environmentId == null &&
                    workspaces.environments.value.any {
                        it.connectionId == connectionId && it.environmentId == environmentId
                    })
        ) {
            "The environment identity changed. Choose its connection again."
        }
        return if (connection.environmentId == null && environmentId != null)
            connection.copy(environmentId = environmentId)
        else connection
    }

    private suspend fun awaitWorkspace(connectionId: String, generation: Long): RepositoryState =
        repository.state.first {
            if (
                repository.connectionId != connectionId ||
                    repository.connectionGeneration != generation ||
                    state.value.activeConnectionId != connectionId
            )
                throw CancellationException("The destination changed.")
            if (it.connectionStatus == ConnectionStatus.Disconnected && it.error != null)
                error(it.error)
            it.shellReady && it.connectionStatus == ConnectionStatus.Connected
        }

    /**
     * Opens a chat, switching environments first when it lives elsewhere. The saved copy shows at
     * once and connecting carries on behind it, like on launch: nothing waits for the sync and the
     * composer and voice stay usable (an exclusive "busy" action would lock them).
     */
    fun openWorkspaceThread(ref: WorkspaceThreadRef) =
        action(exclusive = false) {
            val connection = workspaceConnection(ref.connectionId, ref.environmentId)
            if (
                repository.connectionId != connection.id ||
                    state.value.activeConnectionId != connection.id
            ) {
                savedState["selected-thread:${connection.id}"] = ref.threadId
                connectRepository(connection)
            }
            selectThread(ref.threadId)
        }

    fun prepareWorkspaceTask(ref: WorkspaceProjectRef) =
        action(label = "Opening environment…") {
            val connection = workspaceConnection(ref.connectionId, ref.environmentId)
            if (
                repository.connectionId != connection.id ||
                    state.value.activeConnectionId != connection.id
            )
                connectRepository(connection)
            val ready = awaitWorkspace(connection.id, repository.connectionGeneration)
            check("orchestration:operate" in ready.scopes) {
                "This connection does not allow starting agents."
            }
            check(ready.projects.any { it.id == ref.projectId && !it.archived }) {
                "This project is no longer available."
            }
            completeAction(AppAction.OpenWorkspaceTask, ref.projectId)
        }

    fun updateWorkspaceDraft(ref: WorkspaceProjectRef, text: String) {
        try {
            workspaceConnection(ref.connectionId, ref.environmentId)
            val key = "${ref.connectionId}:new"
            saveDraft(key, (state.value.drafts[key] ?: ComposerDraft()).copy(text = text))
        } catch (failure: IllegalStateException) {
            mutableState.update { it.copy(error = failure.message) }
        }
    }

    fun saveCloudConnection(connection: SavedConnection) = action {
        val owner = cloud.account.state.value.identity
        check(connection.cloudAccountId != null && owner?.accountId == connection.cloudAccountId) {
            "The T3 Connect account changed. Sign in again."
        }
        val connections = updateConnections { saved ->
            if (cloud.account.state.value.identity != owner)
                throw CancellationException("The T3 Connect account changed.")
            val previous = saved.firstOrNull {
                it.id == connection.id &&
                    it.environmentId == connection.environmentId &&
                    it.cloudAccountId == connection.cloudAccountId
            }
            saved.filterNot { it.id == connection.id } +
                connection.copy(learnedRoutes = previous?.learnedRoutes.orEmpty())
        }
        if (cloud.account.state.value.identity != owner)
            throw CancellationException("The T3 Connect account changed.")
        val retained = connections.first { it.id == connection.id }
        store.activeConnectionId = connection.id
        connectRepository(retained)
        completeAction(AppAction.Pair)
    }

    suspend fun handleSignInLink(link: String): Boolean = cloud.account.handle(Uri.parse(link))

    fun openNotification(route: AwarenessRoute.Thread) = action {
        val connection =
            state.value.connections.find {
                it.environmentId == route.environmentId &&
                    (it.cloudAccountId == null ||
                        it.cloudAccountId == cloud.account.state.value.identity?.accountId)
            } ?: error("Add this environment in Connections to open its notification.")
        val key = "selected-thread:${connection.id}"
        savedState[key] = route.threadId
        store.activeConnectionId = connection.id
        connectRepository(connection)
    }

    fun refreshUsage() =
        action(exclusive = false) {
            EnvironmentTools(repository).providers(fresh = true)
        }

    private suspend fun publishUsage() {
        val configs = usageConfigs.toMap()
        val snapshot = withContext(Dispatchers.Default) { buildSubscriptionUsageSnapshot(configs) }
        mutableUsage.value = snapshot
        withContext(Dispatchers.IO) {
            SubscriptionWidgetPublisher.publish(getApplication(), snapshot)
        }
    }

    fun setForeground(value: Boolean) {
        // Coming back to the app: reconnect now instead of waiting out the backoff.
        if (value && !workspaceForeground.value) {
            if (repository.state.value.connectionStatus == ConnectionStatus.Reconnecting)
                repository.retryNow()
        }
        workspaceForeground.value = value
        AgentNotifications.setAppForeground(value)
        usageRefreshJob?.cancel()
        if (value)
            usageRefreshJob = viewModelScope.launch {
                while (isActive) {
                    if (
                        repository.state.value.connectionStatus == ConnectionStatus.Connected &&
                            "orchestration:read" in repository.state.value.scopes
                    )
                        refreshUsage()
                    delay(300_000)
                }
            }
    }

    fun setWorkspaceArchived(value: Boolean) {
        workspaceArchived.value = value
    }

    private suspend fun connectRepository(connection: SavedConnection) {
        cancelSelectionRestore()
        val version = selectionRestoreVersion
        var activated = false
        try {
            val owner =
                if (connection.cloudAccountId == null) null
                else cloud.account.state.first { it.initialized || it.error != null }.identity
            fun requireIntent() {
                if (version != selectionRestoreVersion)
                    throw CancellationException("Connection selection changed.")
                if (
                    connection.cloudAccountId != null &&
                        (owner == null || owner.accountId != connection.cloudAccountId)
                )
                    throw CloudHttpException(401)
                if (
                    connection.cloudAccountId != null && cloud.account.state.value.identity != owner
                )
                    throw CancellationException("The T3 Connect account changed.")
            }
            requireIntent()
            val key = "selected-thread:${connection.id}"
            val cached =
                offlineCopies[connection.id]
                    ?: withContext(Dispatchers.IO) {
                        try {
                            store.offlineCache(connection.id)
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            store.removeOfflineCache(connection.id)
                            null
                        }
                    }
            requireIntent()
            if (cached != null) offlineCopies[connection.id] = cached
            val requestedId = savedState.get<String>(key) ?: cached?.selectedThreadId
            if (requestedId != null) savedState[key] = requestedId
            mutableState.update {
                it.copy(
                    activeConnectionId = connection.id,
                    restoringSelection = requestedId != null,
                )
            }
            repository.connect(connection, cached)
            activated = true
            if (requestedId == null) return
            // Reopen the last chat from the saved copy right away; connecting happens behind it.
            if (cached?.threads?.any { it.id == requestedId && !it.archived } == true) {
                repository.selectThread(requestedId)
                mutableState.update { it.copy(restoringSelection = false) }
            }
            selectionRestoreJob = viewModelScope.launch {
                try {
                    val shell =
                        repository.state.first {
                            it.shellReady ||
                                (it.connectionStatus == ConnectionStatus.Disconnected &&
                                    it.error != null)
                        }
                    if (!shell.shellReady) return@launch
                    if (
                        version == selectionRestoreVersion &&
                            state.value.activeConnectionId == connection.id &&
                            repository.connectionId == connection.id &&
                            savedState.get<String>(key) == requestedId
                    )
                        selectThread(shell.restorableThreadId(requestedId))
                } finally {
                    if (version == selectionRestoreVersion) {
                        selectionRestoreJob = null
                        mutableState.update { it.copy(restoringSelection = false) }
                    }
                }
            }
        } finally {
            if (version == selectionRestoreVersion) {
                if (!activated) {
                    val previous =
                        state.value.connections.firstOrNull { it.id == repository.connectionId }
                    val active =
                        previous
                            ?.takeIf {
                                it.cloudAccountId == null ||
                                    it.cloudAccountId ==
                                        cloud.account.state.value.identity?.accountId
                            }
                            ?.id
                    if (repository.connectionId != null && active == null) repository.disconnect()
                    store.activeConnectionId = active
                    mutableState.update {
                        it.copy(activeConnectionId = active, restoringSelection = false)
                    }
                } else if (selectionRestoreJob == null)
                    mutableState.update { it.copy(restoringSelection = false) }
            }
        }
    }

    private fun cancelSelectionRestore() {
        selectionRestoreVersion += 1
        selectionRestoreJob?.cancel()
        selectionRestoreJob = null
    }

    /** Retry without tearing the screen down; a full reconnect only when nothing is retrying. */
    fun retryNow() {
        if (!repository.retryNow()) reconnect()
    }

    fun reconnect() {
        state.value.connections.find { it.id == state.value.activeConnectionId }?.let(::connect)
    }

    fun disconnect() {
        cancelSelectionRestore()
        repository.disconnect()
        store.activeConnectionId = null
        mutableState.update { it.copy(activeConnectionId = null, restoringSelection = false) }
    }

    fun removeConnection(connection: SavedConnection) = action {
        if (state.value.activeConnectionId == connection.id) disconnect()
        updateConnections { saved -> saved.filterNot { it.id == connection.id } }
        clearClipboardOwner(connection)
        offlineCopies.remove(connection.id)
        usageConfigs.remove(connection.id)
        publishUsage()
        withContext(Dispatchers.IO) {
            store.removeOfflineCache(connection.id)
            store.removePrefix("review-draft:${connection.id}:")
        }
        savedState.remove<String>("selected-thread:${connection.id}")
        mutableState.update { current ->
            current.copy(drafts = current.drafts.filterKeys { !it.startsWith("${connection.id}:") })
        }
        draftWrites.trySend(state.value.drafts)
    }

    fun setTheme(theme: AppTheme) {
        store.theme = theme
        mutableState.update { it.copy(theme = theme) }
    }

    fun setFollowUpBehavior(value: String) {
        require(value in setOf("queue", "steer"))
        mutableState.update { it.copy(preferences = it.preferences.copy(followUpBehavior = value)) }
        preferenceWrites.trySend(state.value.preferences)
    }

    fun setAppearance(value: ClientAppearancePreferences) {
        mutableState.update {
            it.copy(
                preferences = it.preferences.copy(appearance = normalizeClientAppearance(value))
            )
        }
        preferenceWrites.trySend(state.value.preferences)
    }

    fun setComposerEnterBehavior(value: String) {
        require(value in setOf("send", "newline"))
        mutableState.update {
            it.copy(preferences = it.preferences.copy(composerEnterBehavior = value))
        }
        preferenceWrites.trySend(state.value.preferences)
    }

    fun setComposerInput(value: String) {
        require(value in setOf("voice", "keyboard"))
        if (state.value.preferences.composerInput == value) return
        mutableState.update { it.copy(preferences = it.preferences.copy(composerInput = value)) }
        preferenceWrites.trySend(state.value.preferences)
    }

    fun setProjectGrouping(value: String) {
        require(value in setOf("repository", "repository_path", "separate"))
        mutableState.update { it.copy(preferences = it.preferences.copy(projectGrouping = value)) }
        preferenceWrites.trySend(state.value.preferences)
    }

    fun clearClientCache() = action {
        val ids = state.value.connections.map { it.id }
        offlineCopies.clear()
        withContext(Dispatchers.IO) {
            ids.forEach { store.removeOfflineCache(it) }
            File(getApplication<Application>().cacheDir, "attachments").deleteRecursively()
        }
        mutableState.update { it.copy(cacheBytes = 0L) }
    }

    fun forgetCloudEnvironment(environmentId: String) = action {
        val removed =
            state.value.connections.filter {
                it.environmentId == environmentId &&
                    it.cloudAccountId == cloud.account.state.value.identity?.accountId
            }
        if (removed.any { it.id == state.value.activeConnectionId }) disconnect()
        val removedIds = removed.map { it.id }.toSet()
        removed.forEach { clearClipboardOwner(it) }
        updateConnections { saved -> saved.filterNot { it.id in removedIds } }
        withContext(Dispatchers.IO) {
            removedIds.forEach {
                store.removeOfflineCache(it)
                store.removePrefix("review-draft:$it:")
            }
        }
        removedIds.forEach {
            offlineCopies.remove(it)
            usageConfigs.remove(it)
        }
        mutableState.update {
            it.copy(
                drafts =
                    it.drafts.filterKeys { key ->
                        removedIds.none { id -> key.startsWith("$id:") }
                    }
            )
        }
        draftWrites.trySend(state.value.drafts)
        publishUsage()
    }

    fun selectThread(threadId: String?) {
        cancelSelectionRestore()
        state.value.activeConnectionId?.let { connectionId ->
            val key = "selected-thread:$connectionId"
            if (threadId == null) savedState.remove<String>(key) else savedState[key] = threadId
        }
        repository.selectThread(threadId)
        mutableState.update { it.copy(restoringSelection = false) }
    }

    /**
     * Holds the thread's draft until the environment is reachable again. Queued messages go out in
     * order on reconnect, each with a saved dispatch id so a retry never sends it twice.
     */
    fun queueMessage(threadId: String) {
        val key = draftKey(threadId)
        val draft = state.value.drafts[key] ?: return
        if (draft.text.isBlank() && draft.attachments.isEmpty()) return
        mutableState.update {
            it.copy(
                drafts = it.drafts - key,
                outbox = it.outbox + (key to (it.outbox[key].orEmpty() + draft)),
                outboxSent = 0,
            )
        }
        draftWrites.trySend(state.value.drafts)
        outboxWrites.trySend(state.value.outbox)
    }

    /** Takes the newest queued message back into the composer so it can be edited or dropped. */
    fun unqueueMessage(threadId: String, index: Int) {
        val key = draftKey(threadId)
        val queue = state.value.outbox[key] ?: return
        val message = queue.getOrNull(index) ?: return
        if (message.pendingDispatch != null) return
        val current = state.value.drafts[key]
        mutableState.update {
            it.copy(
                outbox =
                    (queue.filterIndexed { i, _ -> i != index }).let { rest ->
                        if (rest.isEmpty()) it.outbox - key else it.outbox + (key to rest)
                    },
                drafts =
                    it.drafts +
                        (key to
                            if (current == null || current.text.isBlank()) message
                            else current.copy(text = message.text + "\n\n" + current.text)),
            )
        }
        draftWrites.trySend(state.value.drafts)
        outboxWrites.trySend(state.value.outbox)
    }

    private suspend fun flushOutbox() = outboxLock.withLock {
        val owner = repository.connectionId ?: return@withLock
        val generation = repository.connectionGeneration
        var sent = 0
        fun live() =
            repository.connectionId == owner &&
                repository.connectionGeneration == generation &&
                repository.state.value.connectionStatus == ConnectionStatus.Connected
        fun store(key: String, queue: List<ComposerDraft>) {
            mutableState.update {
                it.copy(outbox = if (queue.isEmpty()) it.outbox - key else it.outbox + (key to queue))
            }
            outboxWrites.trySend(state.value.outbox)
        }
        for (key in state.value.outbox.keys.filter { it.startsWith("$owner:") }) {
            val threadId = key.removePrefix("$owner:")
            while (live()) {
                val queue = state.value.outbox[key].orEmpty()
                val next = queue.firstOrNull() ?: break
                try {
                    val prepared =
                        prepareDraftDispatch(
                            next,
                            owner,
                            threadId,
                            repository.state.value.threads.find { it.id == threadId }?.activeRunId,
                            state.value.preferences.followUpBehavior,
                        )
                    val pending = checkNotNull(prepared.pendingDispatch)
                    store(key, listOf(prepared) + queue.drop(1))
                    repository.sendMessage(
                        threadId,
                        prepared.text,
                        prepared.attachments.map { it.wire() },
                        state.value.preferences.followUpBehavior,
                        context = pending.context,
                        pendingDispatch = pending,
                    )
                    store(key, state.value.outbox[key].orEmpty().drop(1))
                    sent += 1
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    // Left in place; the next reconnect tries again with the same dispatch id.
                    mutableState.update {
                        it.copy(error = error.message ?: "A queued message could not be sent.")
                    }
                    return@withLock
                }
            }
        }
        if (sent > 0) mutableState.update { it.copy(outboxSent = sent) }
    }

    fun sendMessage(threadId: String) = action {
        val key = draftKey(threadId)
        val draft = state.value.drafts[key] ?: return@action
        if (draft.text.isBlank() && draft.attachments.isEmpty()) return@action
        val owner = repository.connectionId ?: error("Connect to the environment before sending.")
        val generation = repository.connectionGeneration
        val prepared =
            prepareDraftDispatch(
                draft,
                owner,
                threadId,
                repository.state.value.threads.find { it.id == threadId }?.activeRunId,
                state.value.preferences.followUpBehavior,
            )
        val pending = checkNotNull(prepared.pendingDispatch)
        saveDraft(key, prepared)
        persistDrafts()
        if (
            repository.connectionId != owner ||
                repository.connectionGeneration != generation ||
                state.value.activeConnectionId != owner
        )
            throw CancellationException("The environment changed.")
        repository.sendMessage(
            threadId,
            prepared.text,
            prepared.attachments.map { it.wire() },
            state.value.preferences.followUpBehavior,
            context = pending.context,
            pendingDispatch = pending,
        )
        val remaining = state.value.drafts[key]
        if (remaining == prepared) {
            mutableState.update { it.copy(drafts = it.drafts - key) }
            draftWrites.trySend(state.value.drafts)
        } else if (remaining?.pendingDispatch == pending)
            saveDraft(key, remaining.copy(pendingDispatch = null))
    }

    fun launchThread(
        projectId: String,
        model: ModelSelection,
        runtimeMode: String,
        interactionMode: String,
        workspaceStrategy: JsonObject,
    ) = action {
        val key = draftKey(null)
        val connectionId = state.value.activeConnectionId
        val draft = state.value.drafts[key] ?: ComposerDraft()
        require(draft.text.isNotBlank() || draft.attachments.isNotEmpty()) {
            "Write a message or attach a file to start a conversation."
        }
        val title =
            draft.text.lineSequence().firstOrNull(String::isNotBlank)?.take(80)
                ?: draft.attachments.firstOrNull()?.name
                ?: "New conversation"
        val id =
            try {
                repository.launchThread(
                    projectId,
                    title,
                    model,
                    draft.text,
                    runtimeMode,
                    interactionMode,
                    workspaceStrategy,
                    draft.attachments.map { it.wire() },
                    context = referencedComposerContext(draft.text, draft.context),
                )
            } catch (failure: ThreadLaunchPartialFailure) {
                if (!failure.messageAccepted)
                    saveDraft("${connectionId.orEmpty()}:${failure.threadId}", draft)
                mutableState.update {
                    if (it.drafts[key] == draft) it.copy(drafts = it.drafts - key) else it
                }
                draftWrites.trySend(state.value.drafts)
                if (state.value.activeConnectionId == connectionId) {
                    selectThread(failure.threadId)
                    completeAction(AppAction.LaunchConversation)
                }
                throw failure
            }
        selectThread(id)
        mutableState.update {
            if (it.drafts[key] == draft) it.copy(drafts = it.drafts - key) else it
        }
        draftWrites.trySend(state.value.drafts)
        completeAction(AppAction.LaunchConversation)
    }

    fun stopThread(threadId: String) = action(exclusive = false) { repository.stopThread(threadId) }

    fun respond(threadId: String, requestId: String, decision: String) =
        reply(requestId, PendingReply(threadId, decision = decision))

    fun answer(threadId: String, requestId: String, answers: Map<String, List<String>>) =
        reply(requestId, PendingReply(threadId, answers = answers))

    private fun live() =
        repository.state.value.let {
            it.connectionStatus == ConnectionStatus.Connected && it.shellReady
        }

    /** Sends a reply now, or holds it until connected so nothing waits on the link. */
    private fun reply(requestId: String, reply: PendingReply) {
        if (!live()) {
            mutableState.update { it.copy(pendingReplies = it.pendingReplies + (requestId to reply)) }
            return
        }
        action { send(requestId, reply) }
    }

    private suspend fun send(requestId: String, reply: PendingReply) {
        if (reply.answers != null)
            repository.answerQuestions(reply.threadId, requestId, reply.answers)
        else repository.respondToRequest(reply.threadId, requestId, checkNotNull(reply.decision))
    }

    private suspend fun flushReplies() {
        if (state.value.pendingReplies.isEmpty()) return
        // Let the live thread replace the saved copy first, so a stale request is not answered.
        withTimeoutOrNull(10_000) { repository.state.first { !it.threadLoading } }
        for ((requestId, reply) in state.value.pendingReplies) {
            if (!live()) return
            mutableState.update { it.copy(pendingReplies = it.pendingReplies - requestId) }
            // The request may have been settled on the desktop meanwhile; that is not an error.
            if (repository.state.value.pendingRequests.none { it.id == requestId }) continue
            try {
                send(requestId, reply)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(error = error.message ?: "Your reply could not be sent.")
                }
            }
        }
    }

    fun archive(threadId: String, archived: Boolean) = action {
        repository.setArchived(threadId, archived)
        if (archived && repository.state.value.selectedThreadId == threadId) selectThread(null)
    }

    fun workLocally(threadId: String) = action {
        repository.workLocally(threadId)
    }

    fun loadOlder() = action(exclusive = false) { repository.loadOlder() }

    fun loadHistoryThrough(entryId: String) =
        action(exclusive = false) {
            repository.loadOlder(throughEntryId = entryId)
        }

    fun createProject(title: String, workspaceRoot: String) = action {
        repository.createProject(title, workspaceRoot)
        completeAction(AppAction.CreateProject)
    }

    private fun completeAction(action: AppAction, projectId: String? = null) {
        mutableState.update {
            it.copy(
                actionCompletion =
                    AppActionCompletion(
                        UUID.randomUUID().toString(),
                        action,
                        it.activeConnectionId,
                        projectId,
                    )
            )
        }
    }

    fun consumeActionCompletion(id: String) {
        mutableState.update {
            if (it.actionCompletion?.id == id) it.copy(actionCompletion = null) else it
        }
    }

    override fun onCleared() {
        workspaces.close()
        awareness.close()
        repository.disconnect()
        draftWrites.close()
        outboxWrites.close()
        preferenceWrites.close()
        shareDraftWrites.close()
    }
}

internal fun RepositoryState.restorableThreadId(id: String): String? = id.takeIf {
    shellReady && threads.any { thread -> thread.id == id && !thread.archived }
}
