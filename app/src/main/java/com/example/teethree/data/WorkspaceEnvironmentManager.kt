package com.example.teethree.data

import com.example.teethree.data.auth.CloudIdentity
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class WorkspaceRepositoryObservation(
    val connection: SavedConnection?,
    val generation: Long,
    val state: RepositoryState,
)

@OptIn(FlowPreview::class)
class WorkspaceEnvironmentManager(
    scope: CoroutineScope,
    private val foregroundRepository: T3Repository,
    private val createBackgroundRepository: (CoroutineScope, Boolean) -> T3Repository,
    private val loadCache: suspend (SavedConnection) -> CachedEnvironment? = { null },
    private val onShell:
        suspend (SavedConnection, RepositoryState, Boolean, () -> Boolean) -> Unit =
        { _, _, _, _ ->
        },
    private val onRoutes:
        suspend (SavedConnection, List<DirectConnectionRoute>, () -> Boolean) -> Unit =
        { _, _, _ ->
        },
) : AutoCloseable {
    private data class Inputs(
        val connections: List<SavedConnection> = emptyList(),
        val selected: String? = null,
        val account: CloudIdentity? = null,
        val foreground: Boolean = false,
        val archived: Boolean = false,
    )

    private class Lease(
        val connection: SavedConnection,
        val account: CloudIdentity?,
        val archived: Boolean,
        val serial: Long,
    ) {
        var job: Job? = null
        val ready = CompletableDeferred<T3Repository>()
        @Volatile var expired = false
    }

    private val lifetime =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val inputs = MutableStateFlow(Inputs())
    private val mutableEnvironments = MutableStateFlow<List<WorkspaceEnvironmentShell>>(emptyList())
    val environments: StateFlow<List<WorkspaceEnvironmentShell>> = mutableEnvironments.asStateFlow()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val leaseRevision = MutableStateFlow(0L)
    private val mutableReconciliations = MutableStateFlow(0L)
    internal val reconciliations: StateFlow<Long> = mutableReconciliations.asStateFlow()
    private val lock = Any()
    private val records = linkedMapOf<String, WorkspaceEnvironmentShell>()
    private val leases = ConcurrentHashMap<String, Lease>()
    private val failedLeases = ConcurrentHashMap<String, Lease>()
    private val cacheReads = ConcurrentHashMap<String, Deferred<CachedEnvironment?>>()
    private val cacheSlots = Semaphore(2)
    private val pins = mutableMapOf<String, Int>()
    private val serial = AtomicLong()
    private val served = mutableMapOf<String, Long>()
    private val cooldown = ConcurrentHashMap<String, Long>()
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean(false)
    private val observedForegroundId = AtomicReference<String?>(null)

    private data class ShellSignal(
        val id: String?,
        val generation: Long,
        val projects: List<T3Project>,
        val threads: List<T3Thread>,
        val config: kotlinx.serialization.json.JsonObject?,
        val ready: Boolean,
        val status: ConnectionStatus,
        val error: String?,
        val scopes: Set<String>,
    )

    private fun shellSignal(repository: T3Repository): ShellSignal {
        val owned = repository.workspaceObservation()
        val state = owned.state
        return ShellSignal(
            owned.connection?.id,
            owned.generation,
            state.projects,
            state.threads,
            state.serverConfig,
            state.shellReady,
            state.connectionStatus,
            state.error,
            state.scopes,
        )
    }

    init {
        lifetime.launch {
            for (ignored in signal) {
                reconcile()
                mutableReconciliations.update { it + 1 }
            }
        }
        lifetime.launch {
            foregroundRepository.state
                .map { shellSignal(foregroundRepository) }
                .distinctUntilChanged()
                .collect { observeForeground() }
        }
    }

    fun update(
        connections: List<SavedConnection>,
        selectedConnectionId: String?,
        account: CloudIdentity?,
        foreground: Boolean,
        includeArchived: Boolean = false,
    ) {
        if (closed.get()) return
        val next =
            Inputs(connections.toList(), selectedConnectionId, account, foreground, includeArchived)
        val previous = inputs.value
        inputs.value = next
        previous.connections.forEach { old ->
            val replacement = connections.firstOrNull { it.id == old.id }
            if (replacement == null || !sameSession(old, replacement)) {
                cacheReads.remove(old.id)?.cancel()
                synchronized(lock) { records.remove(old.id) }
                blocked.remove(old.id)
                cooldown.remove(old.id)
            }
        }
        if (previous.account != account) {
            blocked.clear()
            cooldown.clear()
            previous.connections
                .filter { it.cloudAccountId != null }
                .forEach { old ->
                    cacheReads.remove(old.id)?.cancel()
                    synchronized(lock) { records.remove(old.id) }
                }
        }
        synchronized(lock) {
            val allowed = allowed(next).mapTo(hashSetOf()) { it.id }
            records.keys.retainAll(allowed)
            cacheReads.keys.filterNot { it in allowed }.forEach { cacheReads.remove(it)?.cancel() }
            publishLocked()
        }
        observeForeground()
        signal.trySend(Unit)
    }

    fun retry(connectionId: String) {
        blocked.remove(connectionId)
        cooldown.remove(connectionId)
        signal.trySend(Unit)
    }

    private fun allowed(value: Inputs = inputs.value) =
        value.connections.filter {
            it.cloudAccountId == null || it.cloudAccountId == value.account?.accountId
        }

    private fun sameSession(a: SavedConnection, b: SavedConnection) =
        a.id == b.id &&
            a.baseUrl == b.baseUrl &&
            a.wsBaseUrl == b.wsBaseUrl &&
            a.accessToken == b.accessToken &&
            a.tokenType == b.tokenType &&
            a.expiresAt == b.expiresAt &&
            a.cloudAccountId == b.cloudAccountId &&
            (a.environmentId == null ||
                b.environmentId == null ||
                a.environmentId == b.environmentId)

    private fun owns(connection: SavedConnection, account: CloudIdentity?): Boolean =
        !closed.get() &&
            allowed().any { sameSession(it, connection) } &&
            (connection.cloudAccountId == null || inputs.value.account == account)

    private fun owns(lease: Lease): Boolean =
        leases[lease.connection.id] === lease &&
            owns(lease.connection, lease.account) &&
            inputs.value.foreground &&
            lease.connection.id != inputs.value.selected &&
            lease.connection.id != foregroundRepository.workspaceObservation().connection?.id

    private fun shell(
        connection: SavedConnection,
        observed: RepositoryState,
        phase: WorkspaceEnvironmentPhase,
        config: kotlinx.serialization.json.JsonObject? = observed.serverConfig,
    ): WorkspaceEnvironmentShell {
        val scratch = config?.nullableString("scratchWorkspaceRoot")?.let(::normalizeWorkspacePath)
        val projects =
            observed.projects.map { project ->
                val isScratch =
                    if (config == null) project.isScratch
                    else scratch != null && normalizeWorkspacePath(project.workspaceRoot) == scratch
                if (project.isScratch == isScratch) project else project.copy(isScratch = isScratch)
            }
        return WorkspaceEnvironmentShell(
            connection.id,
            connection.environmentId ?: config?.obj("environment")?.nullableString("environmentId"),
            connection.label ?: connection.baseUrl,
            phase,
            projects,
            if (inputs.value.archived) observed.threads
            else observed.threads.filterNot { it.archived },
            if (phase == WorkspaceEnvironmentPhase.Live) observed.scopes else emptySet(),
            observed.error,
            config,
            observed.shellReady,
        )
    }

    private fun observeForeground() {
        val observation = foregroundRepository.workspaceObservation()
        if (
            observedForegroundId.getAndSet(observation.connection?.id) != observation.connection?.id
        )
            signal.trySend(Unit)
        val connection = observation.connection ?: return
        val registration =
            allowed().firstOrNull {
                it.id == connection.id && it.cloudAccountId == connection.cloudAccountId
            } ?: return
        if (
            registration.environmentId != null &&
                connection.environmentId != null &&
                registration.environmentId != connection.environmentId
        )
            return
        val state = observation.state
        val phase =
            when {
                state.connectionStatus == ConnectionStatus.Connected && state.shellReady ->
                    WorkspaceEnvironmentPhase.Live
                state.connectionStatus in
                    setOf(ConnectionStatus.Connecting, ConnectionStatus.Reconnecting) ->
                    WorkspaceEnvironmentPhase.Connecting
                state.projects.isNotEmpty() || state.threads.isNotEmpty() ->
                    WorkspaceEnvironmentPhase.Cached
                else -> WorkspaceEnvironmentPhase.Disconnected
            }
        synchronized(lock) {
            records[connection.id] = shell(connection, state, phase)
            publishLocked()
        }
    }

    private fun publishLocked() {
        val value = inputs.value
        val sources =
            allowed(value).map { connection ->
                val existing = records[connection.id]
                (existing
                        ?: WorkspaceEnvironmentShell(
                            connection.id,
                            connection.environmentId,
                            connection.label ?: connection.baseUrl,
                            if (value.foreground) WorkspaceEnvironmentPhase.Queued
                            else WorkspaceEnvironmentPhase.Disconnected,
                        ))
                    .copy(label = connection.label ?: existing?.label ?: connection.baseUrl)
            }
        val groups = sources.groupBy {
            it.environmentId?.let { id -> "environment:$id" } ?: "connection:${it.connectionId}"
        }
        mutableEnvironments.value =
            groups.values.map { aliases ->
                val selected =
                    aliases.firstOrNull { it.connectionId == value.selected }
                        ?: aliases.firstOrNull { it.phase == WorkspaceEnvironmentPhase.Live }
                        ?: aliases.first()
                selected.copy(aliasConnectionIds = aliases.mapTo(linkedSetOf()) { it.connectionId })
            }
    }

    private fun cached(
        connection: SavedConnection,
        account: CloudIdentity?,
    ): Deferred<CachedEnvironment?> =
        cacheReads.computeIfAbsent(connection.id) {
            lifetime.async {
                val loaded =
                    try {
                            cacheSlots.withPermit { loadCache(connection) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            null
                        }
                        ?.copy(selectedThreadId = null, conversations = emptyList())
                if (!owns(connection, account))
                    throw CancellationException("Environment ownership changed")
                if (loaded != null)
                    synchronized(lock) {
                        val previous = records[connection.id]
                        if (previous?.shellReady != true) {
                            val state =
                                RepositoryState(
                                    projects = loaded.projects,
                                    threads = loaded.threads,
                                    providers = loaded.providers,
                                )
                            records[connection.id] =
                                shell(
                                    connection,
                                    state,
                                    if (leases.containsKey(connection.id))
                                        WorkspaceEnvironmentPhase.Connecting
                                    else WorkspaceEnvironmentPhase.Cached,
                                )
                            publishLocked()
                        }
                    }
                loaded
            }
        }

    private suspend fun reconcile() {
        val value = inputs.value
        val foregroundId = foregroundRepository.workspaceObservation().connection?.id
        val all = allowed(value)
        val chosen =
            all.groupBy { it.environmentId ?: "connection:${it.id}" }
                .values
                .map { group ->
                    group.firstOrNull { it.id == value.selected || it.id == foregroundId }
                        ?: group.first()
                }
        val pinned = synchronized(lock) { pins.filterValues { it > 0 }.keys.toSet() }
        val candidates =
            (all.filter { it.id in pinned } + chosen)
                .distinctBy { it.id }
                .filter { it.id != value.selected && it.id != foregroundId }
        val validIds = candidates.mapTo(hashSetOf()) { it.id }
        val waitingPins = candidates.count { it.id in pinned && !leases.containsKey(it.id) }
        val preempt =
            leases.values
                .filter { it.connection.id !in pinned }
                .sortedBy { it.serial }
                .take(maxOf(0, leases.size + waitingPins - 2))
                .mapTo(hashSetOf()) { it.connection.id }
        val remove =
            leases.values.filter { lease ->
                !value.foreground ||
                    lease.connection.id in preempt ||
                    lease.connection.id !in validIds ||
                    !owns(lease.connection, lease.account) ||
                    lease.archived != value.archived ||
                    (lease.expired && lease.connection.id !in pinned)
            }
        for (lease in remove) {
            if (leases.remove(lease.connection.id, lease)) {
                leaseRevision.update { it + 1 }
                lease.job?.cancelAndJoin()
                synchronized(lock) {
                    records[lease.connection.id]?.let { prior ->
                        records[lease.connection.id] =
                            prior.copy(
                                phase =
                                    if (value.foreground) WorkspaceEnvironmentPhase.Queued
                                    else if (
                                        prior.projects.isNotEmpty() || prior.threads.isNotEmpty()
                                    )
                                        WorkspaceEnvironmentPhase.Cached
                                    else WorkspaceEnvironmentPhase.Disconnected,
                                scopes = emptySet(),
                                shellReady = false,
                            )
                    }
                }
            }
        }
        if (inputs.value != value) {
            signal.trySend(Unit)
            return
        }
        if (value.foreground) {
            all.forEach { connection -> cached(connection, value.account) }
            val ordered =
                candidates.sortedWith(
                    compareBy<SavedConnection> { if (it.id in pinned) 0 else 1 }
                        .thenBy { synchronized(lock) { served[it.id] ?: 0L } }
                )
            for (connection in ordered) {
                if (leases.size >= 2) break
                if (
                    leases.containsKey(connection.id) ||
                        connection.id in blocked ||
                        (cooldown[connection.id] ?: 0) > System.currentTimeMillis()
                )
                    continue
                val lease =
                    Lease(connection, value.account, value.archived, serial.incrementAndGet())
                leases[connection.id] = lease
                leaseRevision.update { it + 1 }
                synchronized(lock) { served[connection.id] = lease.serial }
                lease.job = lifetime.launch { runLease(lease) }
            }
        }
        synchronized(lock) { publishLocked() }
    }

    private suspend fun runLease(lease: Lease) = coroutineScope {
        val workers = mutableListOf<Job>()
        var opened: T3Repository? = null
        try {
            val repository = createBackgroundRepository(this, lease.archived)
            opened = repository
            val cache = cached(lease.connection, lease.account).await()
            if (!owns(lease)) return@coroutineScope
            repository.connect(lease.connection, cache)
            val observer = launch {
                repository.state.collect {
                    val observation = repository.workspaceObservation()
                    val connection = observation.connection ?: return@collect
                    if (!owns(lease) || connection.id != lease.connection.id) return@collect
                    val state = observation.state
                    val phase =
                        if (
                            state.connectionStatus == ConnectionStatus.Connected && state.shellReady
                        )
                            WorkspaceEnvironmentPhase.Live
                        else WorkspaceEnvironmentPhase.Connecting
                    synchronized(lock) {
                        records[connection.id] = shell(connection, state, phase)
                        publishLocked()
                    }
                }
            }
            workers += observer
            val persistence = launch {
                repository.state
                    .filter { it.shellReady && it.connectionStatus == ConnectionStatus.Connected }
                    .sample(750)
                    .collect {
                        val observation = repository.workspaceObservation()
                        if (owns(lease) && observation.state.shellReady)
                            onShell(
                                observation.connection ?: return@collect,
                                observation.state,
                                lease.archived,
                            ) {
                                owns(lease) &&
                                    repository.workspaceObservation().generation ==
                                        observation.generation
                            }
                    }
            }
            workers += persistence
            val routing = launch {
                repository.learnedRoutes.filterNotNull().collect { learned ->
                    if (owns(lease))
                        onRoutes(
                            lease.connection.copy(environmentId = learned.environmentId),
                            learned.routes,
                        ) {
                            owns(lease) &&
                                repository.workspaceObservation().generation == learned.generation
                        }
                }
            }
            workers += routing
            val ready =
                withTimeout(75_000) {
                    repository.state.first {
                        it.shellReady ||
                            (it.connectionStatus == ConnectionStatus.Disconnected &&
                                it.error != null)
                    }
                }
            if (!ready.shellReady) {
                blocked += lease.connection.id
                throw IOException(ready.error ?: "This environment could not connect.")
            }
            lease.ready.complete(repository)
            while (isActive) {
                delay(30_000)
                val foregroundId = foregroundRepository.workspaceObservation().connection?.id
                val queued =
                    environments.value.any {
                        it.connectionId != inputs.value.selected &&
                            it.connectionId != foregroundId &&
                            !leases.containsKey(it.connectionId) &&
                            it.connectionId !in blocked
                    }
                if (queued && synchronized(lock) { (pins[lease.connection.id] ?: 0) == 0 }) {
                    lease.expired = true
                    signal.trySend(Unit)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failLease(lease, IOException("This environment did not finish synchronizing."))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failLease(lease, failure)
        } finally {
            workers.forEach { it.cancel() }
            lease.ready.completeExceptionally(
                CancellationException("The environment lease closed.")
            )
            opened?.disconnect()
            if (leases.remove(lease.connection.id, lease)) {
                leaseRevision.update { it + 1 }
                signal.trySend(Unit)
            }
        }
    }

    private fun failLease(lease: Lease, failure: Exception) {
        synchronized(lock) {
            if (!owns(lease)) {
                lease.ready.completeExceptionally(CancellationException("Environment changed"))
                return
            }
            if ((pins[lease.connection.id] ?: 0) > 0) failedLeases[lease.connection.id] = lease
            lease.ready.completeExceptionally(failure)
            cooldown[lease.connection.id] = System.currentTimeMillis() + 30_000
            val old = records[lease.connection.id]
            records[lease.connection.id] =
                (old
                        ?: WorkspaceEnvironmentShell(
                            lease.connection.id,
                            lease.connection.environmentId,
                            lease.connection.label ?: lease.connection.baseUrl,
                            WorkspaceEnvironmentPhase.Disconnected,
                        ))
                    .copy(
                        phase =
                            if (
                                old?.projects?.isNotEmpty() == true ||
                                    old?.threads?.isNotEmpty() == true
                            )
                                WorkspaceEnvironmentPhase.Cached
                            else WorkspaceEnvironmentPhase.Disconnected,
                        scopes = emptySet(),
                        shellReady = false,
                        error = failure.message ?: "This environment could not synchronize.",
                    )
            publishLocked()
        }
        lifetime.launch {
            delay(30_000)
            signal.trySend(Unit)
        }
    }

    // Consume remote response bodies inside the callback, before its lease is released.
    suspend fun <T> withRepository(connectionId: String, block: suspend (T3Repository) -> T): T {
        val start = inputs.value
        val connection =
            allowed(start).firstOrNull { it.id == connectionId }
                ?: throw CancellationException(
                    "This environment is unavailable for the current account."
                )
        val foreground = foregroundRepository.workspaceObservation()
        if (foreground.connection?.id == connectionId) {
            val ready =
                combine(foregroundRepository.state, inputs, leaseRevision) { _, _, _ ->
                        foregroundRepository.workspaceObservation()
                    }
                    .first { latest ->
                        if (
                            latest.connection?.id != connectionId ||
                                latest.generation != foreground.generation ||
                                !owns(connection, start.account)
                        )
                            throw CancellationException("Environment changed")
                        latest.state.shellReady ||
                            (latest.state.connectionStatus == ConnectionStatus.Disconnected &&
                                latest.state.error != null)
                    }
            if (!ready.state.shellReady)
                throw IOException(ready.state.error ?: "This environment could not connect.")
            val result = block(foregroundRepository)
            if (
                foregroundRepository.workspaceObservation().generation != foreground.generation ||
                    !owns(connection, start.account)
            ) {
                (result as? AutoCloseable)?.close()
                throw CancellationException("Environment changed")
            }
            return result
        }
        synchronized(lock) {
            val count = pins[connectionId] ?: 0
            if (count == 0) failedLeases.remove(connectionId)
            pins[connectionId] = count + 1
        }
        retry(connectionId)
        try {
            val lease =
                combine(inputs, leaseRevision) { _, _ ->
                        if (!owns(connection, start.account) || !inputs.value.foreground)
                            throw CancellationException("Environment changed")
                        leases[connectionId] ?: failedLeases[connectionId]
                    }
                    .filterNotNull()
                    .first()
            val repository = lease.ready.await()
            val generation = repository.workspaceObservation().generation
            if (!owns(lease)) throw CancellationException("Environment changed")
            val result = block(repository)
            if (!owns(lease) || repository.workspaceObservation().generation != generation) {
                (result as? AutoCloseable)?.close()
                throw CancellationException("Environment changed")
            }
            return result
        } finally {
            synchronized(lock) {
                val count = (pins[connectionId] ?: 1) - 1
                if (count <= 0) {
                    pins.remove(connectionId)
                    failedLeases.remove(connectionId)
                } else pins[connectionId] = count
            }
            signal.trySend(Unit)
        }
    }

    override fun close() {
        closed.set(true)
        leaseRevision.update { it + 1 }
        lifetime.cancel()
        signal.close()
    }
}
