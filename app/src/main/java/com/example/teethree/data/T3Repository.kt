package com.example.teethree.data

import com.example.teethree.data.auth.CloudHttpException
import com.example.teethree.data.auth.DirectEnvironmentVerifier
import com.example.teethree.data.auth.EnvironmentAuthorization
import com.example.teethree.data.auth.endpointUrl
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class T3Repository(
    private val scope: CoroutineScope,
    client: OkHttpClient = OkHttpClient(),
    private val beforeHttpAccess: suspend (String) -> Unit = {},
    private val authorization: EnvironmentAuthorization? = null,
    private val includeArchived: Boolean = true,
) {
    private val http =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    private val directVerifier = DirectEnvironmentVerifier(http, beforeHttpAccess)
    private val mutableState = MutableStateFlow(RepositoryState())
    val state: StateFlow<RepositoryState> = mutableState.asStateFlow()
    private val mutableLearnedRoutes = MutableStateFlow<LearnedConnectionRoutes?>(null)
    val learnedRoutes: StateFlow<LearnedConnectionRoutes?> = mutableLearnedRoutes.asStateFlow()
    private val generation = AtomicLong()
    private val threadGeneration = AtomicLong()
    private val threadLock = Any()
    private val supplementalFamilyThreads = linkedMapOf<String, T3Thread>()
    private var authoritativeThreadIds: Set<String> = emptySet()
    private var connectionJob: Job? = null
    @Volatile private var rpc: EffectRpcClient? = null
    private var threadSubscription: String? = null
    private var projection: ThreadProjection? = null
    private var historyReducer: ConversationHistoryReducer? = null
    @Volatile private var currentConnection: SavedConnection? = null
    private var historyCursor: String? = null
    @Volatile private var offlineCache: CachedEnvironment? = null

    internal fun retainOfflineCache(value: CachedEnvironment) {
        offlineCache = value
    }

    internal val connectionId: String?
        get() = currentConnection?.id

    internal val connectionGeneration: Long
        get() = generation.get()

    internal val httpBaseUrl: String?
        get() = currentConnection?.effectiveHttpBaseUrl

    internal fun workspaceObservation(): WorkspaceRepositoryObservation =
        synchronized(threadLock) {
            WorkspaceRepositoryObservation(currentConnection, generation.get(), state.value)
        }

    suspend fun pair(baseUrl: String, pairingCode: String): SavedConnection = operation {
        val target = parsePairingTarget(baseUrl, pairingCode)
        beforeHttpAccess(target.first)
        val descriptor =
            try {
                executeJson(
                    Request.Builder()
                        .url("${target.first}/.well-known/t3/environment")
                        .header("Cache-Control", "no-cache, no-store")
                        .get()
                        .build()
                )
            } catch (error: EnvironmentHttpException) {
                if (error.status == 404) null else throw error
            }
        fun identityField(key: String): String? = descriptor?.let { value ->
            (value[key] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotBlank() && it == it.trim() && it.none(Char::isISOControl) }
                ?: throw IOException("The environment returned an invalid identity response.")
        }
        val environmentId = identityField("environmentId")
        val label = identityField("label")
        val body =
            FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                .add("subject_token", target.second)
                .add("subject_token_type", "urn:t3:params:oauth:token-type:environment-bootstrap")
                .add("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
                .add("client_label", "T3 Code Android")
                .add("client_device_type", "mobile")
                .add("client_os", "Android")
                .build()
        val token =
            executeJson(Request.Builder().url("${target.first}/oauth/token").post(body).build())
        require(token.string("token_type") == "Bearer") {
            "This environment requires an unsupported token type."
        }
        val accessToken = token.string("access_token")
        val lifetime = token.long("expires_in")
        require(accessToken.isNotBlank() && lifetime > 0) {
            "The environment returned an invalid access token."
        }
        SavedConnection(
            id =
                environmentId?.let {
                    UUID.nameUUIDFromBytes(
                            "bearer\u0000$it\u0000${target.first}".toByteArray(Charsets.UTF_8)
                        )
                        .toString()
                } ?: UUID.randomUUID().toString(),
            baseUrl = target.first,
            accessToken = accessToken,
            scopes = token.string("scope").split(Regex("\\s+")).filter(String::isNotEmpty).toSet(),
            expiresAt = System.currentTimeMillis() + lifetime.coerceAtMost(315_360_000L) * 1_000,
            environmentId = environmentId,
            label = label,
        )
    }

    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * Tries again now instead of waiting out the backoff, keeping the threads on screen. The
     * outage clock restarts, so waking the phone reads as a fresh "Connecting…" rather than
     * picking up an old outage at "Can't reach". False when nothing is retrying on its own.
     */
    fun retryNow(): Boolean {
        val state = mutableState.value
        if (state.connectionStatus != ConnectionStatus.Reconnecting) return false
        mutableState.update {
            it.copy(offlineSince = System.currentTimeMillis(), reconnectAttempts = 0, nextRetryAt = null)
        }
        retrySignal.trySend(Unit)
        return true
    }

    fun connect(connection: SavedConnection, cached: CachedEnvironment? = null) {
        val version =
            synchronized(threadLock) {
                val version = generation.incrementAndGet()
                connectionJob?.cancel()
                rpc?.close()
                rpc = null
                val sameEnvironment = currentConnection?.id == connection.id
                currentConnection = connection
                offlineCache = cached
                mutableLearnedRoutes.value = null
                clearSupplementalThreadFamilies()
                threadGeneration.incrementAndGet()
                threadSubscription = null
                projection = null
                historyReducer = null
                historyCursor = null
                val previous = mutableState.value
                mutableState.value =
                    RepositoryState(
                        connectionStatus = ConnectionStatus.Connecting,
                        scopes = connection.scopes,
                        projects = cached?.projects.orEmpty(),
                        threads = cached?.threads.orEmpty(),
                        providers = cached?.providers.orEmpty(),
                        // Retrying the same environment keeps the outage clock running.
                        offlineSince =
                            if (sameEnvironment && previous.offlineSince != null)
                                previous.offlineSince
                            else System.currentTimeMillis(),
                        reconnectAttempts = if (sameEnvironment) previous.reconnectAttempts else 0,
                        lastLiveAt = if (sameEnvironment) previous.lastLiveAt else cached?.savedAt,
                    )
                version
            }
        val nextJob =
            scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val canonical = AtomicReference(connection.copy(selectedTransport = null))
                val preferredRoute = AtomicReference<String?>(null)
                val failedUntil = ConcurrentHashMap<String, Long>()
                var attempts: ConnectionRouteAttempts? = null
                var transientInCycle = false
                var failures = 0
                while (isActive && generation.get() == version) {
                    if (attempts == null) {
                        transientInCycle = false
                        val saved = canonical.get()
                        val ordered = orderedConnectionRoutes(saved)
                        val preferred = preferredRoute.getAndSet(null)
                        val routes =
                            if (preferred == null) ordered
                            else
                                ordered.sortedBy {
                                    if (connectionRouteId(it) == preferred) 0 else 1
                                }
                        attempts =
                            ConnectionRouteAttempts(this, routes) { route ->
                                if (
                                    route == null &&
                                        (saved.cloudAccountId != null ||
                                            saved.environmentId == null)
                                )
                                    true
                                else directVerifier.probe(saved.copy(selectedTransport = route))
                            }
                    }
                    val attempt = checkNotNull(attempts).next() ?: break
                    var connection = canonical.get().copy(selectedTransport = attempt.transport)
                    val switching = AtomicBoolean(false)
                    var becameLive = false
                    var routeWorker: Job? = null
                    var routeChecks: Channel<Unit>? = null
                    var session: EffectRpcClient? = null
                    var archiveWorker: Job? = null
                    var authorizationWorker: Job? = null
                    var archiveRequests: Channel<Unit>? = null
                    try {
                        connection =
                            authorization?.prepare(connection, forceDirectVerification = true)
                                ?: connection.also { directVerifier.verify(it, force = true) }
                        if (generation.get() != version) break
                        canonical.set(
                            connection.copy(
                                selectedTransport = null,
                                connectionMethod =
                                    if (connection.cloudAccountId != null) "relay" else "direct",
                            )
                        )
                        val ownsConnection =
                            synchronized(threadLock) {
                                if (generation.get() != version) false
                                else {
                                    currentConnection = connection
                                    true
                                }
                            }
                        if (!ownsConnection) break
                        if (connection.expiresAt <= System.currentTimeMillis()) {
                            throw EnvironmentHttpException(
                                401,
                                "Connection expired. Pair with the environment again.",
                            )
                        }
                        beforeHttpAccess(connection.effectiveHttpBaseUrl)
                        val sessionState =
                            executeJson(
                                authenticated(
                                        EnvironmentRequestContext(connection, version),
                                        "/api/auth/session",
                                    )
                                    .get()
                                    .build()
                            )
                        if (!sessionState.bool("authenticated")) {
                            throw EnvironmentHttpException(
                                401,
                                "Connection expired. Pair with the environment again.",
                            )
                        }
                        val granted =
                            sessionState
                                .array(
                                    if (sessionState.containsKey("permissions")) "permissions"
                                    else "scopes"
                                )
                                .mapNotNull { (it as? JsonPrimitive)?.content }
                                .toSet()
                        val ticket =
                            try {
                                executeJson(
                                    authenticated(
                                            EnvironmentRequestContext(connection, version),
                                            "/api/auth/websocket-ticket/rpc",
                                        )
                                        .post(ByteArray(0).toRequestBody())
                                        .build()
                                )
                            } catch (error: EnvironmentHttpException) {
                                if (error.status != 404) throw error
                                executeJson(
                                    authenticated(
                                            EnvironmentRequestContext(connection, version),
                                            "/api/auth/websocket-ticket",
                                        )
                                        .post(ByteArray(0).toRequestBody())
                                        .build()
                                )
                            }
                        require(ticket.string("ticket").isNotBlank()) {
                            "The environment returned an invalid connection ticket."
                        }
                        val url =
                            authorization?.socketUrl(connection, ticket.string("ticket"))
                                ?: (connection.effectiveWsBaseUrl
                                        ?.replaceFirst("wss://", "https://")
                                        ?.replaceFirst("ws://", "http://")
                                        ?.toHttpUrl()
                                        ?: endpointUrl(connection.effectiveHttpBaseUrl, "/ws"))
                                    .newBuilder()
                                    .addQueryParameter("wsTicket", ticket.string("ticket"))
                                    .addQueryParameter("orchestrationProtocol", "2")
                                    .addQueryParameter("clientSurface", "mobile")
                                    .addQueryParameter("clientOs", "android")
                                    .addQueryParameter("clientDeviceType", "phone")
                                    .addQueryParameter("connectionMethod", "direct")
                                    .build()
                                    .toString()
                                    .replaceFirst("http", "ws")
                        session = EffectRpcClient(this, http)
                        session.connect(url)
                        val ownsSession =
                            synchronized(threadLock) {
                                if (generation.get() != version) false
                                else {
                                    rpc = session
                                    becameLive = true
                                    mutableState.update {
                                        it.copy(
                                            connectionStatus = ConnectionStatus.Connected,
                                            scopes = granted,
                                            error = null,
                                            shellReady = false,
                                            reconnectAttempts = 0,
                                            nextRetryAt = null,
                                            offlineSince = null,
                                            lastLiveAt = System.currentTimeMillis(),
                                        )
                                    }
                                    true
                                }
                            }
                        if (!ownsSession) break
                        attempts?.close()
                        attempts = null
                        val activeSession = session
                        val checks = Channel<Unit>(Channel.CONFLATED)
                        routeChecks = checks
                        val archiveRefresh = Channel<Unit>(Channel.CONFLATED)
                        archiveRequests = archiveRefresh
                        archiveWorker =
                            if (includeArchived)
                                launch {
                                    for (ignored in archiveRefresh) {
                                        try {
                                            val snapshot =
                                                activeSession
                                                    .call(
                                                        "orchestration.getArchivedShellSnapshot",
                                                        JsonObject(emptyMap()),
                                                    )
                                                    .jsonObject
                                            synchronized(threadLock) {
                                                if (
                                                    generation.get() == version &&
                                                        rpc === activeSession
                                                ) {
                                                    val archived =
                                                        snapshot.array("threads").map {
                                                            parseThread(it.jsonObject, true)
                                                        }
                                                    mutableState.update { current ->
                                                        val active =
                                                            current.threads.filterNot(
                                                                T3Thread::archived
                                                            )
                                                        val activeIds =
                                                            active.mapTo(hashSetOf(), T3Thread::id)
                                                        current.copy(
                                                            threads =
                                                                active +
                                                                    archived.filterNot {
                                                                        it.id in activeIds
                                                                    }
                                                        )
                                                    }
                                                }
                                            }
                                        } catch (error: Exception) {
                                            if (error is CancellationException) throw error
                                            synchronized(threadLock) {
                                                if (
                                                    generation.get() == version &&
                                                        rpc === activeSession
                                                )
                                                    mutableState.update {
                                                        it.copy(
                                                            error =
                                                                "Could not load archived threads."
                                                        )
                                                    }
                                            }
                                        }
                                    }
                                }
                            else null
                        session.subscribe(
                            "subscribeServerConfig",
                            buildJsonObject { put("usageLimitSources", true) },
                        ) { event ->
                            synchronized(threadLock) {
                                if (generation.get() == version && rpc === activeSession) {
                                    applyConfig(event)
                                    val config = state.value.serverConfig
                                    val environmentId =
                                        config?.obj("environment")?.nullableString("environmentId")
                                    val saved = canonical.get()
                                    if (
                                        config != null &&
                                            environmentId != null &&
                                            (saved.environmentId == null ||
                                                saved.environmentId == environmentId)
                                    ) {
                                        val known = saved.copy(environmentId = environmentId)
                                        val routes =
                                            if (config.containsKey("directEndpoints"))
                                                mergeLearnedDirectRoutes(
                                                    known,
                                                    config.array("directEndpoints"),
                                                )
                                            else known.learnedRoutes
                                        canonical.set(known.copy(learnedRoutes = routes))
                                        currentConnection =
                                            currentConnection?.copy(
                                                environmentId = environmentId,
                                                learnedRoutes = routes,
                                            )
                                        val learned =
                                            LearnedConnectionRoutes(
                                                known.id,
                                                version,
                                                environmentId,
                                                routes,
                                                known.cloudAccountId,
                                            )
                                        if (mutableLearnedRoutes.value != learned) {
                                            mutableLearnedRoutes.value = learned
                                            checks.trySend(Unit)
                                        }
                                    }
                                }
                            }
                        }
                        session.subscribe("orchestration.subscribeShell", JsonObject(emptyMap())) {
                            event ->
                            synchronized(threadLock) {
                                if (generation.get() == version && rpc === activeSession) {
                                    applyShell(event)
                                    if (event.string("kind") == "thread.removed")
                                        archiveRefresh.trySend(Unit)
                                }
                            }
                        }
                        if (includeArchived) archiveRefresh.trySend(Unit)
                        routeWorker = launch {
                            val ticker = launch {
                                while (isActive) {
                                    delay(60_000)
                                    checks.trySend(Unit)
                                }
                            }
                            try {
                                for (ignored in checks) {
                                    val saved = canonical.get()
                                    var routes = orderedConnectionRoutes(saved)
                                    if (
                                        connection.selectedTransport != null &&
                                            routes.none {
                                                connectionRouteId(it) ==
                                                    connectionRouteId(connection.selectedTransport)
                                            }
                                    ) {
                                        routes =
                                            orderedConnectionRoutes(
                                                saved.copy(
                                                    learnedRoutes =
                                                        saved.learnedRoutes +
                                                            connection.selectedTransport!!
                                                )
                                            )
                                    }
                                    val active = routes.indexOfFirst {
                                        connectionRouteId(it) ==
                                            connectionRouteId(connection.selectedTransport)
                                    }
                                    if (active <= 0 || saved.environmentId == null) continue
                                    val better =
                                        routes.take(active).filter {
                                            (failedUntil[connectionRouteId(it)] ?: 0) <=
                                                System.currentTimeMillis()
                                        }
                                    val ready = coroutineScope {
                                        val slots = Semaphore(4)
                                        better
                                            .map { route ->
                                                async {
                                                    slots.withPermit {
                                                        directVerifier.probe(
                                                            saved.copy(selectedTransport = route)
                                                        )
                                                    }
                                                }
                                            }
                                            .awaitAll()
                                    }
                                    val index = ready.indexOfFirst { it }
                                    if (
                                        index >= 0 &&
                                            generation.get() == version &&
                                            rpc === activeSession
                                    ) {
                                        preferredRoute.set(connectionRouteId(better[index]))
                                        switching.set(true)
                                        activeSession.close()
                                        break
                                    }
                                }
                            } finally {
                                ticker.cancel()
                            }
                        }
                        checks.trySend(Unit)
                        authorizationWorker = authorization?.let { authorizer ->
                            launch {
                                try {
                                    authorizer.renew(connection)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    activeSession.close()
                                }
                            }
                        }
                        synchronized(threadLock) {
                            if (generation.get() == version && rpc === activeSession)
                                subscribeSelectedThread(activeSession, version)
                        }
                        failures = 0
                        throw session.awaitClosed()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (generation.get() != version) break
                        if (!becameLive)
                            failedUntil[connectionRouteId(attempt.transport)] =
                                System.currentTimeMillis() + 300_000
                        val moreRoutes = attempts?.hasMore == true
                        val blocked =
                            error is ConnectionAccessDeniedException ||
                                (error is CloudHttpException &&
                                    error.status in setOf(401, 403, 404)) ||
                                (error is EnvironmentHttpException && error.status in 400..499)
                        if (!blocked) transientInCycle = true
                        val terminal =
                            !moreRoutes && !switching.get() && blocked && !transientInCycle
                        synchronized(threadLock) {
                            if (generation.get() == version)
                                mutableState.update {
                                    it.copy(
                                        offlineSince =
                                            it.offlineSince ?: System.currentTimeMillis(),
                                        lastLiveAt =
                                            if (becameLive) System.currentTimeMillis()
                                            else it.lastLiveAt,
                                        connectionStatus =
                                            if (terminal) ConnectionStatus.Disconnected
                                            else ConnectionStatus.Reconnecting,
                                        error =
                                            if (switching.get()) null
                                            else
                                                error.message
                                                    ?: "Could not connect to the environment.",
                                        shellReady = false,
                                        threadLoading = !terminal && it.selectedThreadId != null,
                                    )
                                }
                        }
                        if (terminal) {
                            attempts?.close()
                            break
                        }
                    } finally {
                        routeChecks?.close()
                        routeWorker?.cancel()
                        archiveRequests?.close()
                        archiveWorker?.cancel()
                        authorizationWorker?.cancel()
                        session?.close()
                        synchronized(threadLock) { if (rpc === session) rpc = null }
                    }
                    if (switching.get()) {
                        attempts?.close()
                        attempts = null
                        continue
                    }
                    if (attempts?.hasMore == true) continue
                    attempts?.close()
                    attempts = null
                    failures += 1
                    val backoff = (1_000L shl failures.coerceAtMost(5)).coerceAtMost(30_000)
                    synchronized(threadLock) {
                        if (generation.get() == version)
                            mutableState.update {
                                it.copy(
                                    reconnectAttempts = it.reconnectAttempts + 1,
                                    nextRetryAt = System.currentTimeMillis() + backoff,
                                )
                            }
                    }
                    // Waking the app cuts the wait short and starts the backoff over.
                    if (withTimeoutOrNull(backoff) { retrySignal.receive() } != null) failures = 0
                    synchronized(threadLock) {
                        if (generation.get() == version)
                            mutableState.update { it.copy(nextRetryAt = null) }
                    }
                }
            }
        synchronized(threadLock) {
            if (generation.get() == version) {
                connectionJob = nextJob
                nextJob.start()
            } else nextJob.cancel()
        }
    }

    fun disconnect() {
        synchronized(threadLock) {
            generation.incrementAndGet()
            connectionJob?.cancel()
            connectionJob = null
            rpc?.close()
            rpc = null
            currentConnection = null
            offlineCache = null
            mutableLearnedRoutes.value = null
            clearSupplementalThreadFamilies()
            threadGeneration.incrementAndGet()
            threadSubscription = null
            projection = null
            historyReducer = null
            historyCursor = null
            mutableState.value = RepositoryState()
        }
    }

    fun selectThread(threadId: String?) =
        synchronized(threadLock) {
            if (mutableState.value.selectedThreadId == threadId) return@synchronized
            threadGeneration.incrementAndGet()
            threadSubscription?.let { rpc?.unsubscribe(it) }
            threadSubscription = null
            projection = null
            historyReducer = null
            historyCursor = null
            mutableState.update {
                it.copy(
                    selectedThreadId = threadId,
                    timeline =
                        offlineCache
                            ?.conversations
                            ?.find { it.threadId == threadId }
                            ?.timeline
                            .orEmpty(),
                    pendingRequests =
                        offlineCache
                            ?.conversations
                            ?.find { it.threadId == threadId }
                            ?.pendingRequests
                            .orEmpty(),
                    subagents = JsonArray(emptyList()),
                    error = null,
                    threadLoading = threadId != null,
                    hasOlderMessages = false,
                    historyLoading = false,
                    history = null,
                )
            }
            rpc?.let { subscribeSelectedThread(it, generation.get()) }
        }

    suspend fun sendMessage(
        threadId: String,
        text: String,
        attachments: List<JsonObject> = emptyList(),
        followUpBehavior: String = "queue",
        context: JsonObject? = null,
        pendingDispatch: PendingMessageDispatch? = null,
    ) = operation {
        validateMessageInput(text, attachments)
        context?.let(::validateComposerContext)
        val thread = state.value.threads.firstOrNull { it.id == threadId }
        check(thread == null || !providerManagedChild(thread)) {
            "This agent is managed by its provider. Reply in its parent conversation."
        }
        if (pendingDispatch != null) {
            require(
                pendingDispatch.matches(
                    currentConnection?.id.orEmpty(),
                    threadId,
                    text,
                    attachments,
                    context,
                )
            ) {
                "This saved send attempt belongs to another draft or environment."
            }
            dispatch(pendingDispatch.wire())
            return@operation Unit
        }
        dispatch(
            buildJsonObject {
                put("type", "message.dispatch")
                put("commandId", newId())
                put("createdBy", "user")
                put("creationSource", "mobile")
                put("threadId", threadId)
                put("messageId", newId())
                put("text", text)
                context?.let { put("context", it) }
                put("attachments", JsonArray(attachments))
                put(
                    "dispatchMode",
                    messageDispatchMode(thread?.activeRunId, followUpBehavior),
                )
            }
        )
        Unit
    }

    suspend fun createProject(title: String, workspaceRoot: String): String = operation {
        require(title.isNotBlank()) { "Enter a project name." }
        require(workspaceRoot.isNotBlank()) { "Enter a workspace folder on the environment." }
        val id = newId()
        requireOperate()
            .call(
                "projects.mutate",
                buildJsonObject {
                    put("type", "project.create")
                    put("commandId", newId())
                    put("projectId", id)
                    put("title", title.trim())
                    put("workspaceRoot", workspaceRoot.trim())
                },
            )
        id
    }

    suspend fun launchThread(
        projectId: String,
        title: String,
        model: ModelSelection,
        text: String,
        runtimeMode: String = "approval-required",
        interactionMode: String = "default",
        workspaceStrategy: JsonObject = buildJsonObject { put("type", "root") },
        attachments: List<JsonObject> = emptyList(),
        context: JsonObject? = null,
        generateTitle: Boolean = true,
    ): String = operation {
        validateMessageInput(text, attachments)
        context?.let(::validateComposerContext)
        require(
            runtimeMode in setOf("approval-required", "auto-accept-edits", "auto", "full-access")
        ) {
            "Invalid permission mode."
        }
        require(interactionMode in setOf("default", "plan")) { "Invalid interaction mode." }
        val version = generation.get()
        val client = requireOperate()
        val id = newId()
        val initialMessageId = newId()
        try {
            val result =
                client
                    .call(
                        "orchestration.launchThread",
                        buildJsonObject {
                            put("commandId", newId())
                            put("threadId", id)
                            put("creationSource", "mobile")
                            put("projectId", projectId)
                            put("title", title.ifBlank { text.take(80).ifBlank { "New thread" } })
                            put("generateTitle", generateTitle)
                            put(
                                "modelSelection",
                                model.wire(),
                            )
                            put("runtimeMode", runtimeMode)
                            put("interactionMode", interactionMode)
                            put("workspaceStrategy", workspaceStrategy)
                            put(
                                "initialMessage",
                                buildJsonObject {
                                    put("messageId", initialMessageId)
                                    put("text", text)
                                    put("attachments", JsonArray(attachments))
                                    context?.let { put("context", it) }
                                },
                            )
                        },
                    )
                    .jsonObject
            if (generation.get() != version || rpc !== client)
                throw CancellationException("Environment changed")
            val threadId = result.string("threadId", id)
            selectThread(threadId)
            threadId
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (generation.get() != version || rpc !== client)
                throw CancellationException("Environment changed")
            // A workspace setup failure can leave the newly created thread available for retry.
            val created =
                try {
                    client
                        .call(
                            "orchestration.getThreadProjection",
                            buildJsonObject { put("threadId", id) },
                        )
                        .jsonObject
                } catch (recoveryError: Exception) {
                    if (recoveryError is CancellationException) throw recoveryError
                    null
                }
            if (created != null) {
                if (generation.get() != version || rpc !== client)
                    throw CancellationException("Environment changed")
                selectThread(id)
                throw ThreadLaunchPartialFailure(
                    id,
                    created.array("messages").any {
                        it.jsonObject.string("id") == initialMessageId
                    },
                    error,
                )
            }
            throw error
        }
    }

    suspend fun stopThread(threadId: String) = operation {
        dispatch(
            buildJsonObject {
                put("type", "thread.stop")
                put("commandId", newId())
                put("threadId", threadId)
            }
        )
        Unit
    }

    suspend fun workLocally(threadId: String): String = operation {
        val version = generation.get()
        val thread =
            state.value.threads.find { it.id == threadId }
                ?: error("This conversation is no longer available.")
        val projection =
            callTool(
                    "orchestration.getThreadProjection",
                    buildJsonObject { put("threadId", threadId) },
                )
                .jsonObject
        val prompt = setupRecoveryMessage(projection)
        if (generation.get() != version) throw CancellationException("Environment changed")
        val cancelled =
            callTool("worktreeSetup.cancel", buildJsonObject { put("threadId", threadId) })
                .jsonObject
                .bool("cancelled")
        check(cancelled) {
            "Worktree setup already finished or could not be cancelled. Open the conversation to check its status."
        }
        if (generation.get() != version) throw CancellationException("Environment changed")
        launchThread(
            thread.projectId,
            thread.title,
            thread.modelSelection,
            prompt.string("text"),
            thread.runtimeMode,
            thread.interactionMode,
            attachments = prompt.array("attachments").map { it.jsonObject },
            context = prompt["context"] as? JsonObject,
            generateTitle = false,
        )
    }

    suspend fun respondToRequest(threadId: String, requestId: String, decision: String) =
        operation {
            require(
                decision in setOf("accept", "acceptForSession", "acceptAlways", "decline", "cancel")
            ) {
                "Invalid approval decision."
            }
            val request = state.value.pendingRequests.firstOrNull { it.id == requestId }
            require(
                request != null && request.canRespond && state.value.selectedThreadId == threadId
            ) {
                "This request can no longer be answered."
            }
            require(request.options.any { it.decision == decision }) {
                "This decision is unavailable for this request."
            }
            dispatch(
                buildJsonObject {
                    put("type", "runtime-request.respond")
                    put("commandId", newId())
                    put("threadId", threadId)
                    put("requestId", requestId)
                    put("decision", decision)
                }
            )
            Unit
        }

    suspend fun answerQuestions(
        threadId: String,
        requestId: String,
        answers: Map<String, List<String>>,
    ) = operation {
        val request = state.value.pendingRequests.firstOrNull { it.id == requestId }
        require(request != null && request.canRespond && state.value.selectedThreadId == threadId) {
            "This question can no longer be answered."
        }
        val encodedAnswers =
            synchronized(threadLock) {
                answers.mapValues { (questionId, values) ->
                    projection?.answerValues(requestId, questionId, values) ?: values
                }
            }
        dispatch(
            buildJsonObject {
                put("type", "runtime-request.respond")
                put("commandId", newId())
                put("threadId", threadId)
                put("requestId", requestId)
                put(
                    "answers",
                    buildJsonObject {
                        for ((questionId, values) in encodedAnswers) put(
                            questionId,
                            JsonArray(values.map(::JsonPrimitive)),
                        )
                    },
                )
            }
        )
        Unit
    }

    suspend fun setArchived(threadId: String, archived: Boolean) = operation {
        dispatch(
            buildJsonObject {
                put("type", if (archived) "thread.archive" else "thread.unarchive")
                put("commandId", newId())
                put("threadId", threadId)
            }
        )
        Unit
    }

    suspend fun loadOlder(throughEntryId: String? = null) = operation {
        val context =
            synchronized(threadLock) {
                val connection = currentConnection
                val threadId = state.value.selectedThreadId
                val cursor = historyCursor
                if (
                    connection == null ||
                        threadId == null ||
                        cursor == null ||
                        state.value.historyLoading
                )
                    null
                else {
                    mutableState.update { it.copy(historyLoading = true) }
                    HistoryRequest(
                        connection,
                        threadId,
                        cursor,
                        generation.get(),
                        threadGeneration.get(),
                    )
                }
            } ?: return@operation
        try {
            val url =
                context.connection.effectiveHttpBaseUrl
                    .toHttpUrl()
                    .newBuilder()
                    .addPathSegments("api/orchestration/threads")
                    .addPathSegment(context.threadId)
                    .addPathSegment("history")
                    .addQueryParameter("cursor", context.cursor)
                    .apply { throughEntryId?.let { addQueryParameter("throughEntryId", it) } }
                    .build()
            val page =
                executeJson(
                    Request.Builder()
                        .url(url)
                        .tag(
                            EnvironmentRequestContext::class.java,
                            EnvironmentRequestContext(context.connection, context.version),
                        )
                        .header("Authorization", "Bearer ${context.connection.accessToken}")
                        .header("x-t3-orchestration-protocol", "2")
                        .get()
                        .build()
                )
            if (
                page.bool("hasMoreHistory") && page.nullableString("nextCursor") == context.cursor
            ) {
                throw IOException(
                    "The environment could not advance the conversation history. Try loading earlier messages again."
                )
            }
            synchronized(threadLock) {
                if (
                    generation.get() == context.version &&
                        threadGeneration.get() == context.threadVersion &&
                        historyCursor == context.cursor
                ) {
                    projection?.mergeHistory(page)
                    val metadataChanged = historyReducer?.mergeHistory(page) == true
                    historyCursor = page.nullableString("nextCursor")
                    mutableState.update {
                        it.copy(
                            timeline = projection?.timeline().orEmpty(),
                            hasOlderMessages = page.bool("hasMoreHistory") && historyCursor != null,
                            historyRevision = it.historyRevision + 1,
                            history = if (metadataChanged) historyReducer?.value() else it.history,
                        )
                    }
                }
            }
        } finally {
            synchronized(threadLock) {
                if (
                    generation.get() == context.version &&
                        threadGeneration.get() == context.threadVersion
                )
                    mutableState.update { it.copy(historyLoading = false) }
            }
        }
    }

    private suspend fun dispatch(command: JsonObject) =
        requireOperate().call("orchestration.dispatchCommand", command)

    internal suspend fun callTool(
        method: String,
        payload: JsonObject,
        requiredScope: String? = null,
        expectedConnectionId: String? = null,
    ): JsonElement = operation {
        val scopes = requiredToolScopes(method, payload)
        require(requiredScope == null || requiredScope in scopes) {
            "The requested permission does not match this operation."
        }
        val version = generation.get()
        val session = requireToolSession(scopes)
        if (expectedConnectionId != null && currentConnection?.id != expectedConnectionId)
            throw CancellationException("Environment changed")
        val result = session.call(method, payload)
        if (generation.get() != version || rpc !== session)
            throw CancellationException("Environment changed")
        result
    }

    internal fun streamTool(
        method: String,
        payload: JsonObject,
        onError: (Throwable) -> Unit = {},
        onValue: (JsonObject) -> Unit,
    ): AutoCloseable {
        val version = generation.get()
        val session = requireToolSession(requiredToolScopes(method, payload))
        val id =
            session.subscribe(
                method,
                payload,
                onError = { error ->
                    if (generation.get() == version && rpc === session) onError(error)
                },
            ) { value ->
                if (generation.get() == version && rpc === session) onValue(value)
            }
        return AutoCloseable { session.unsubscribe(id) }
    }

    internal fun streamValueTool(
        method: String,
        payload: JsonObject,
        onError: (Throwable) -> Unit = {},
        onValue: (JsonElement) -> Unit,
    ): AutoCloseable {
        val version = generation.get()
        val session = requireToolSession(requiredToolScopes(method, payload))
        val id =
            session.subscribeValue(
                method,
                payload,
                onError = { error ->
                    if (generation.get() == version && rpc === session) onError(error)
                },
            ) { value ->
                if (generation.get() == version && rpc === session) onValue(value)
            }
        return AutoCloseable { session.unsubscribe(id) }
    }

    internal suspend fun postTool(path: String, payload: JsonObject): JsonObject = operation {
        require(path == "/api/pull-requests/diff") { "This HTTP operation is unavailable." }
        val context = captureRequestContext(setOf("orchestration:read"))
        val version = context.generation
        val result =
            executeJson(
                authenticated(context, path)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            )
        if (generation.get() != version) throw CancellationException("Environment changed")
        result
    }

    internal suspend fun mediaAccess(basePath: String): JsonObject = operation {
        require(basePath in setOf("/api/preview-stream", "/api/device-hub"))
        val context = captureRequestContext(setOf("orchestration:read"))
        val version = context.generation
        val connection = context.connection
        val ticket =
            executeJson(
                    authenticated(context, "/api/auth/websocket-ticket/media")
                        .post(ByteArray(0).toRequestBody())
                        .build()
                )
                .string("ticket")
        require(ticket.isNotBlank()) { "The environment returned an invalid media ticket." }
        if (generation.get() != version) throw CancellationException("Environment changed")
        buildJsonObject {
            put("httpBase", connection.effectiveHttpBaseUrl.trimEnd('/') + basePath)
            put(
                "wsBase",
                connection.effectiveHttpBaseUrl.replaceFirst("http", "ws").trimEnd('/') + basePath,
            )
            put("query", buildJsonObject { put("wsTicket", ticket) })
            put("credentials", false)
        }
    }

    internal suspend fun assetHttp(relativeUrl: String): Response = operation {
        val context = captureRequestContext(setOf("orchestration:read"))
        val connection = context.connection
        val request =
            Request.Builder()
                .url(
                    resolveSignedAssetUrl(
                        connection.effectiveHttpBaseUrl,
                        relativeUrl,
                        "/api/assets/",
                    )
                )
                .tag(
                    EnvironmentRequestContext::class.java,
                    context,
                )
                .get()
                .build()
        val authorized = authorizeRequest(request)
        requireCurrentRequest(authorized, requestContext())
        val response = http.awaitResponse(authorized)
        try {
            requireCurrentRequest(authorized, requestContext())
        } catch (cancelled: CancellationException) {
            response.close()
            throw cancelled
        }
        response
    }

    internal suspend fun previewHttp(
        path: String,
        query: Map<String, String>,
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
    ): Response = operation {
        val scope =
            when {
                path == "/api/preview-stream/upload" && method == "POST" -> "preview:operate"
                path == "/api/preview-stream/download" && method == "GET" -> "orchestration:read"
                else -> throw IllegalArgumentException("This preview transfer is unavailable.")
            }
        val context = captureRequestContext(setOf(scope))
        val builder = authenticated(context, path)
        val url =
            builder
                .build()
                .url
                .newBuilder()
                .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
                .build()
        val authorized = authorizeRequest(builder.url(url).method(method, body).build())
        requireCurrentRequest(authorized, requestContext())
        val result = http.awaitResponse(authorized)
        try {
            requireCurrentRequest(authorized, requestContext())
        } catch (cancelled: CancellationException) {
            result.close()
            throw cancelled
        }
        result
    }

    fun terminalSession(
        threadId: String,
        terminalId: String = "term-1",
        cwd: String,
        worktreePath: String? = null,
    ): TerminalSession =
        TerminalSession(scope, state, { rpc }, threadId, terminalId, cwd, worktreePath)

    internal fun clearSupplementalThreadFamilies() =
        synchronized(threadLock) {
            val supplementalOnly = supplementalFamilyThreads.keys - authoritativeThreadIds
            supplementalFamilyThreads.clear()
            authoritativeThreadIds = emptySet()
            mutableState.update {
                it.copy(threads = it.threads.filterNot { thread -> thread.id in supplementalOnly })
            }
        }

    internal suspend fun fetchThreadFamily(threadId: String): List<T3Thread> {
        val version = generation.get()
        val session = requireToolSession(setOf("orchestration:read"))
        val response =
            session.call(
                "orchestration.getThreadFamily",
                buildJsonObject { put("threadId", threadId) },
            ) as? JsonObject ?: error("Invalid thread family response.")
        val family =
            response.array("threads").filterIsInstance<JsonObject>().map { parseThread(it, false) }
        synchronized(threadLock) {
            if (generation.get() != version || rpc !== session)
                throw CancellationException("Environment changed")
            val previouslySupplemental = supplementalFamilyThreads.keys.toSet()
            family.filterNot(::threadIsListed).forEach { child ->
                supplementalFamilyThreads[child.id] = child
            }
            val protected = setOfNotNull(threadId, state.value.selectedThreadId)
            val evictable = supplementalFamilyThreads.keys.filterNot { it in protected }
            evictable
                .take((supplementalFamilyThreads.size - 256).coerceAtLeast(0))
                .forEach(supplementalFamilyThreads::remove)
            mutableState.update { current ->
                current.copy(
                    threads =
                        current.threads.filterNot {
                            it.id in previouslySupplemental && it.id !in authoritativeThreadIds
                        } +
                            supplementalFamilyThreads.values.filterNot {
                                it.id in authoritativeThreadIds
                            }
                )
            }
        }
        return family
    }

    private fun requireToolSession(required: Set<String>): EffectRpcClient {
        check(state.value.scopes.containsAll(required)) {
            "This connection requires ${required.joinToString()} permission."
        }
        check(state.value.connectionStatus == ConnectionStatus.Connected) {
            "Wait for the environment to reconnect."
        }
        return checkNotNull(rpc) { "Environment is disconnected." }
    }

    private fun requireOperate(): EffectRpcClient {
        check("orchestration:operate" in state.value.scopes) {
            "This connection has read-only access."
        }
        check(state.value.connectionStatus == ConnectionStatus.Connected) {
            "Wait for the environment to reconnect."
        }
        return checkNotNull(rpc) { "Environment is disconnected." }
    }

    private fun subscribeSelectedThread(session: EffectRpcClient, version: Long) {
        val id = state.value.selectedThreadId ?: return
        val threadVersion = threadGeneration.incrementAndGet()
        projection = null
        historyReducer = null
        historyCursor = null
        mutableState.update {
            it.copy(
                threadLoading = true,
                subagents = JsonArray(emptyList()),
                hasOlderMessages = false,
                historyLoading = false,
                history = null,
            )
        }
        threadSubscription =
            session.subscribe(
                "orchestration.subscribeThread",
                buildJsonObject {
                    put("threadId", id)
                    put("acceptBoundedSnapshot", true)
                },
            ) { event ->
                synchronized(threadLock) {
                    if (
                        generation.get() != version ||
                            threadGeneration.get() != threadVersion ||
                            rpc !== session ||
                            state.value.selectedThreadId != id
                    )
                        return@synchronized
                    if (event.string("kind") == "snapshot") {
                        val history =
                            ConversationHistoryReducer(
                                id,
                                event.obj("projection"),
                                event.long("snapshotSequence"),
                            )
                        val detail =
                            ThreadProjection(
                                event.obj("projection"),
                                event.long("snapshotSequence"),
                                event.nullableString("latestLocalTurnOrdinal")?.toLongOrNull(),
                                event.containsKey("historyCursor"),
                            )
                        historyReducer = history
                        projection = detail
                        historyCursor = event.nullableString("historyCursor")
                        val timeline = detail.timeline()
                        val requests = detail.pendingRequests()
                        mutableState.update {
                            it.copy(
                                threadLoading = false,
                                timeline = timeline,
                                pendingRequests = requests,
                                subagents = detail.subagents(),
                                hasOlderMessages =
                                    event.bool("hasMoreHistory") && historyCursor != null,
                                historyRevision = it.historyRevision + 1,
                                history = history.value(),
                            )
                        }
                    } else if (event.string("kind") == "event") {
                        val detail = projection ?: return@synchronized
                        detail.applyEvent(event)
                        val updatedHistory =
                            if (historyReducer?.applyEvent(event) == true) historyReducer?.value()
                            else null
                        val timeline = detail.timeline()
                        val requests = detail.pendingRequests()
                        mutableState.update {
                            it.copy(
                                timeline = timeline,
                                pendingRequests = requests,
                                subagents = detail.subagents(),
                                history = updatedHistory ?: it.history,
                            )
                        }
                    }
                }
            }
    }

    private data class HistoryRequest(
        val connection: SavedConnection,
        val threadId: String,
        val cursor: String,
        val version: Long,
        val threadVersion: Long,
    )

    private fun applyConfig(event: JsonObject) {
        val payload = mergeServerConfig(state.value.serverConfig, event) ?: return
        val providers =
            payload.array("providers").mapNotNull { value ->
                val provider = value as? JsonObject ?: return@mapNotNull null
                val instanceId = provider.string("instanceId")
                if (instanceId.isEmpty()) return@mapNotNull null
                T3Provider(
                    instanceId,
                    provider.string("displayName", provider.string("driver", instanceId)),
                    provider.array("models").mapNotNull { raw ->
                        val model = raw as? JsonObject ?: return@mapNotNull null
                        val slug = model.string("slug")
                        if (slug.isEmpty()) null
                        else
                            T3Model(
                                slug,
                                model.string("name", slug),
                                model.bool("isDefault"),
                                model.obj("capabilities").array("optionDescriptors"),
                            )
                    },
                    provider.bool("enabled") &&
                        provider.bool("installed") &&
                        provider.string("availability") != "unavailable" &&
                        provider.string("status") != "error",
                    provider.bool("supportsConversationRollback", true),
                )
            }
        mutableState.update { it.copy(providers = providers, serverConfig = payload) }
    }

    private fun applyShell(event: JsonObject) {
        when (event.string("kind")) {
            "snapshot" -> {
                val snapshot = event.obj("snapshot")
                if (event.containsKey("resolvedRepositoryIdentityRoots")) {
                    val roots =
                        event
                            .array("resolvedRepositoryIdentityRoots")
                            .mapNotNull { (it as? JsonPrimitive)?.content }
                            .toSet()
                    val updates =
                        snapshot
                            .array("projects")
                            .map { parseProject(it.jsonObject) }
                            .filter { it.workspaceRoot in roots }
                            .associateBy { it.id }
                    mutableState.update { state ->
                        state.copy(
                            projects =
                                state.projects.map { project ->
                                    val metadata = updates[project.id]
                                    if (metadata == null) project
                                    else
                                        project.copy(
                                            repositoryKey = metadata.repositoryKey,
                                            repositoryRoot = metadata.repositoryRoot,
                                            repositoryLabel = metadata.repositoryLabel,
                                        )
                                }
                        )
                    }
                    return
                }
                val projects = snapshot.array("projects").map { parseProject(it.jsonObject) }
                val active = snapshot.array("threads").map { parseThread(it.jsonObject, false) }
                val archived =
                    snapshot.array("archivedThreads").map { parseThread(it.jsonObject, true) }
                authoritativeThreadIds = (active + archived).mapTo(hashSetOf(), T3Thread::id)
                (active + archived).forEach { thread ->
                    if (thread.id in supplementalFamilyThreads)
                        supplementalFamilyThreads[thread.id] = thread
                }
                mutableState.update {
                    it.copy(
                        projects = projects,
                        shellReady = true,
                        threads =
                            (active +
                                    archived +
                                    it.threads.filter(T3Thread::archived) +
                                    supplementalFamilyThreads.values)
                                .distinctBy(T3Thread::id),
                    )
                }
            }
            "project.updated" -> {
                val project = parseProject(event.obj("project"))
                mutableState.update {
                    it.copy(
                        projects = it.projects.filterNot { item -> item.id == project.id } + project
                    )
                }
            }
            "project.removed" ->
                mutableState.update {
                    it.copy(
                        projects =
                            it.projects.filterNot { item -> item.id == event.string("projectId") }
                    )
                }
            "thread.updated" -> {
                val thread = parseThread(event.obj("thread"), event.string("location") == "archive")
                authoritativeThreadIds = authoritativeThreadIds + thread.id
                if (thread.id in supplementalFamilyThreads)
                    supplementalFamilyThreads[thread.id] = thread
                mutableState.update {
                    it.copy(
                        threads = it.threads.filterNot { item -> item.id == thread.id } + thread
                    )
                }
            }
            "thread.removed" -> {
                val id = event.string("threadId")
                supplementalFamilyThreads.remove(id)
                authoritativeThreadIds = authoritativeThreadIds - id
                val archived = event.string("location") == "archive"
                mutableState.update {
                    it.copy(
                        threads =
                            it.threads.filterNot { item ->
                                item.id == id && item.archived == archived
                            }
                    )
                }
            }
        }
    }

    private suspend fun <T> operation(action: suspend () -> T): T {
        val version = generation.get()
        return try {
            action().also {
                mutableState.update { current ->
                    if (generation.get() == version) current.copy(error = null) else current
                }
            }
        } catch (error: Exception) {
            if (error !is CancellationException)
                mutableState.update {
                    if (generation.get() == version)
                        it.copy(error = error.message ?: "The request failed.")
                    else it
                }
            throw error
        }
    }

    private fun captureRequestContext(requiredScopes: Set<String>): EnvironmentRequestContext =
        synchronized(threadLock) {
            requireToolSession(requiredScopes)
            EnvironmentRequestContext(
                checkNotNull(currentConnection) { "Environment is disconnected." },
                generation.get(),
            )
        }

    private fun authenticated(context: EnvironmentRequestContext, path: String): Request.Builder =
        Request.Builder()
            .url(endpointUrl(context.connection.effectiveHttpBaseUrl, path))
            .tag(EnvironmentRequestContext::class.java, context)
            .header("Authorization", "Bearer ${context.connection.accessToken}")

    private fun requestContext() =
        synchronized(threadLock) {
            currentConnection?.let { EnvironmentRequestContext(it, generation.get()) }
        }

    private suspend fun authorizeRequest(request: Request): Request =
        authorizeBoundRequest(request, ::requestContext) { connection, captured ->
            authorization?.authorize(connection, captured)
                ?: captured.also { directVerifier.verify(connection) }
        }

    private suspend fun executeJson(request: Request): JsonObject {
        val authorized = authorizeRequest(request)
        return suspendCancellableCoroutine { continuation ->
            requireCurrentRequest(authorized, requestContext())
            val call = http.newCall(authorized)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive)
                            continuation.resumeWithException(
                                IOException("Could not reach the environment.", e)
                            )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                if (!it.isSuccessful) {
                                    val message =
                                        when (it.code) {
                                            401,
                                            403 ->
                                                "Pairing was rejected or this connection expired. Pair with the environment again."
                                            else -> "The environment returned HTTP ${it.code}."
                                        }
                                    throw EnvironmentHttpException(it.code, message)
                                }
                                try {
                                    Json.parseToJsonElement(it.body?.string() ?: "{}").jsonObject
                                } catch (error: Exception) {
                                    throw IOException(
                                        "The environment returned an invalid response.",
                                        error,
                                    )
                                }
                            }
                            requireCurrentRequest(authorized, requestContext())
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            )
        }
    }
}

internal class EnvironmentHttpException(val status: Int, message: String) : IOException(message)

class ConnectionAccessDeniedException(message: String) : IOException(message)

class ThreadLaunchPartialFailure(
    val threadId: String,
    val messageAccepted: Boolean,
    cause: Throwable,
) :
    IOException(
        if (messageAccepted)
            "The conversation and message were saved. Open the conversation to check the agent's status."
        else
            "The conversation was created, but the message could not be sent. Your draft is saved for retry.",
        cause,
    )

internal fun newId(): String = UUID.randomUUID().toString()

internal fun parseProject(value: JsonObject) =
    T3Project(
        value.string("id"),
        value.string("title"),
        value.string("workspaceRoot"),
        value.nullableString("pinnedAt") != null,
        value.nullableString("archivedAt") != null,
        value.obj("repositoryIdentity").obj("origin").nullableString("canonicalKey")
            ?: value.obj("repositoryIdentity").nullableString("canonicalKey"),
        value.obj("repositoryIdentity").nullableString("rootPath"),
        value.obj("repositoryIdentity").obj("origin").nullableString("displayName")
            ?: value.obj("repositoryIdentity").obj("origin").nullableString("canonicalKey")
            ?: value.obj("repositoryIdentity").nullableString("displayName"),
        value["defaultModelSelection"] as? JsonObject,
        value.array("scripts"),
        value.nullableString("defaultThreadEnvMode"),
        false,
        value.string("createdAt"),
        value.string("updatedAt"),
        value.obj("repositoryIdentity").nullableString("name"),
    )

internal fun parseThread(value: JsonObject, archived: Boolean): T3Thread {
    val model = value.obj("modelSelection")
    val pendingRequest = value["pendingRuntimeRequest"] as? JsonObject
    return T3Thread(
        value.string("id"),
        value.string("projectId"),
        value.string("title"),
        value.string("status", "idle"),
        parseModelSelection(model),
        value.nullableString("activeRunId"),
        archived,
        value.obj("latestVisibleMessage").string("text"),
        value.string("updatedAt"),
        value.string("runtimeMode", "approval-required"),
        value.string("interactionMode", "default"),
        value.nullableString("worktreePath"),
        value.nullableString("pinnedAt") != null,
        value.nullableString("snoozedUntil"),
        value.string("settledOverride") == "settled",
        (pendingRequest != null && pendingRequest.string("kind") != "auth_refresh") ||
            (value.string("interactionMode") == "plan" && value.bool("hasActionableProposedPlan")),
        value.obj("lineage"),
        value.nullableString("creationSource"),
        value.array("tags").mapNotNull {
            (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
        },
        value["delegatedFrom"] as? JsonObject,
        value.array("pendingBackgroundTasks"),
        value["goal"] as? JsonObject,
        value.nullableString("lastVisitedAt"),
        value.nullableString("snoozedAt"),
        value.nullableString("latestRunId"),
        value.nullableString("latestRunCompletedAt"),
        value.bool("latestRunStopRequested"),
        value["limitRecovery"] as? JsonObject,
        pendingRequest != null,
        value.long("pendingGuestMessageCount").toInt(),
    )
}

internal fun parsePairingTarget(rawBaseUrl: String, rawCode: String): Pair<String, String> {
    var input = rawBaseUrl.trim()
    require(input.isNotBlank()) { "Enter an environment URL." }
    if (!input.contains("://")) input = "https://$input"
    var uri = parsePairingUri(input)
    val wrapper = readParameters(uri.rawQuery)["pairingUrl"]
    if ((uri.scheme.startsWith("t3code") || uri.scheme == "teethree") && wrapper != null)
        uri = parsePairingUri(wrapper)
    val query = readParameters(uri.rawQuery)
    val code =
        rawCode.trim().ifEmpty {
            readParameters(uri.rawFragment)["token"] ?: query["token"].orEmpty()
        }
    query["host"]?.takeIf(String::isNotBlank)?.let { host ->
        uri = parsePairingUri(if (host.contains("://")) host else "https://$host")
    }
    val scheme =
        when (uri.scheme?.lowercase()) {
            "http",
            "ws" -> "http"
            "https",
            "wss" -> "https"
            else -> throw IllegalArgumentException("Use an HTTP or HTTPS environment URL.")
        }
    require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
        "Enter a valid environment URL without a username or password."
    }
    require(code.isNotBlank()) { "Enter a pairing code or paste a pairing link." }
    val origin = URI(scheme, null, uri.host, uri.port, null, null, null).toASCIIString()
    return origin to code
}

private fun readParameters(raw: String?): Map<String, String> =
    raw.orEmpty()
        .split('&')
        .mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size != 2) null
            else
                URLDecoder.decode(pieces[0], Charsets.UTF_8.name()) to
                    URLDecoder.decode(pieces[1], Charsets.UTF_8.name())
        }
        .toMap()

private fun parsePairingUri(value: String): URI =
    try {
        URI(value)
    } catch (_: java.net.URISyntaxException) {
        throw IllegalArgumentException("Enter a valid environment URL.")
    }

internal fun requiredToolScopes(method: String, payload: JsonObject): Set<String> {
    if (
        method == "device.list" &&
            (payload.containsKey("retryHostId") || payload.containsKey("updateTool"))
    )
        return setOf("orchestration:read", "orchestration:operate")
    if (method == "server.updateSettings") {
        val patch = payload.obj("patch")
        val providerKeys =
            setOf("providers", "providerInstances", "usageLimitSources", "secretProviders")
        val hasMutation = payload["providerInstanceMutation"] is JsonObject
        return buildSet {
            if (hasMutation || patch.keys.any { it in providerKeys }) add("providers:manage")
            if ((!hasMutation && patch.isEmpty()) || patch.keys.any { it !in providerKeys })
                add("settings:write")
        }
    }
    if (method == "projects.mutate")
        return buildSet {
            add("orchestration:operate")
            if (payload.string("type") != "project.delete" && payload.containsKey("scripts"))
                add("settings:write")
        }
    if (
        method == "assets.createUrl" &&
            payload.obj("resource").string("_tag") in
                setOf("workspace-file", "media-file", "draft-workspace-file")
    )
        return setOf("filesystem:read")
    val primary =
        when (method) {
            "orchestration.getTurnDiff",
            "orchestration.getThreadProjection",
            "orchestration.getTurnItem",
            "orchestration.searchThreadStream",
            "orchestration.getThreadFamily",
            "orchestration.getWorkflowScript",
            "orchestration.getNativeChildWork",
            "orchestration.getFullThreadDiff" -> "orchestration:read"
            "projects.listEntries",
            "projects.searchEntries",
            "projects.readFile",
            "review.getDiffPreview" -> "filesystem:read"
            "vcs.refreshStatus",
            "vcs.listRefs",
            "pullRequests.list",
            "pullRequests.detail",
            "pullRequests.preview",
            "pullRequests.activity",
            "pullRequests.checks",
            "pullRequests.stack",
            "pullRequests.filesViewed",
            "pullRequests.threadComments",
            "pullRequests.reviewerCandidates",
            "pullRequests.labelCandidates",
            "pullRequests.reviewGuide",
            "preview.list",
            "subscribePreviewEvents",
            "subscribeDiscoveredLocalServers",
            "device.list",
            "device.detail",
            "subscribeDeviceState",
            "mcpApps.toolInfo",
            "mcpApps.readResource",
            "server.discoverSourceControl",
            "sourceControl.lookupRepository",
            "sourceControl.appAccess",
            "subscribeProjectClones",
            "server.getDatabaseStorage",
            "server.getStorageCleanupReport",
            "subscribeWorktreeSetup",
            "assets.createUrl",
            "scheduledTasks.subscribe",
            "server.refreshProviders" -> "orchestration:read"
            "server.getUsageSummary",
            "server.refreshUsageRates" -> "diagnostics:read"
            "provider.consumeResetCredit" -> "providers:manage"
            "peerLinks.list" -> "access:read"
            "vcs.switchRef",
            "vcs.createRef",
            "vcs.pull",
            "git.runStackedAction",
            "pullRequests.comment",
            "pullRequests.submitReview",
            "pullRequests.runAction",
            "pullRequests.update",
            "pullRequests.updateComment",
            "pullRequests.replyToThread",
            "pullRequests.setThreadResolution",
            "pullRequests.setReaction",
            "pullRequests.requestReviewers",
            "pullRequests.setLabels",
            "pullRequests.setFilesViewed" -> "source-control:write"
            "projectClone.start",
            "projectClone.cancel",
            "projectClone.retry",
            "sourceControl.publishRepository" -> "source-control:write"
            "attachments.createUploadUrl",
            "attachments.delete",
            "assets.persistChatAttachments",
            "scheduledTasks.upsert",
            "scheduledTasks.setEnabled",
            "scheduledTasks.delete",
            "scheduledTasks.runNow",
            "device.open",
            "device.close",
            "device.shutdown",
            "device.action",
            "mcpApps.callTool",
            "mcpApps.updateModelContext",
            "pullRequests.generateReviewGuide" -> "orchestration:operate"
            "worktreeSetup.cancel",
            "orchestration.stopWorkflow",
            "orchestration.forceStopThread" -> "orchestration:operate"
            "server.runStorageCleanup" -> "settings:write"
            "sourceControl.signInStart",
            "sourceControl.signInWait",
            "sourceControl.signInCancel" -> "settings:write"
            "projects.createNew" -> "orchestration:operate"
            "filesystem.browse" -> "filesystem:read"
            "preview.open",
            "preview.resize",
            "preview.adjust",
            "preview.close",
            "preview.clearProfile" -> "preview:operate"
            "terminal.open",
            "terminal.attach",
            "terminal.write",
            "terminal.resize",
            "terminal.clear",
            "terminal.close" -> "terminal:operate"
            "terminal.observe",
            "subscribeTerminalMetadata" -> "terminal:read"
            "orchestration.dispatchCommand" -> "orchestration:operate"
            "provider.install.subscribe" -> "orchestration:read"
            "provider.install.start",
            "provider.install.cancel",
            "provider.install.remove",
            "server.updateProvider" -> "providers:manage"
            "provider.auth.subscribe",
            "provider.auth.start",
            "provider.auth.respond",
            "provider.auth.complete",
            "provider.auth.cancel",
            "provider.auth.logout" -> "providers:manage"
            else ->
                throw IllegalArgumentException(
                    "This operation is unavailable in the Android client."
                )
        }
    val extra =
        if (
            method == "git.runStackedAction" &&
                payload.containsKey("threadId") &&
                payload.string("action") in setOf("create_pr", "commit_push_pr")
        )
            setOf("orchestration:read")
        else emptySet()
    return setOf(primary) + extra
}

internal fun validateMessageInput(text: String, attachments: List<JsonObject>) {
    require(text.isNotBlank() || attachments.isNotEmpty()) { "Enter a message." }
    require(text.length <= 120_000) { "Messages can contain up to 120,000 characters." }
    require(attachments.size <= 100) { "You can attach up to 100 files per message." }
    val imageTypes = setOf("image/gif", "image/jpeg", "image/png", "image/webp")
    val imageBytes = attachments.sumOf { attachment ->
        val size = attachment.long("sizeBytes")
        val image =
            attachment.string("type") == "image" ||
                attachment.string("mimeType").lowercase() in imageTypes
        require(size >= 0 && size <= if (image) 10 * 1024 * 1024L else 50 * 1024 * 1024L) {
            "This attachment exceeds the supported file size."
        }
        if (image) size else 0L
    }
    require(imageBytes <= 80 * 1024 * 1024L) { "Images can total up to 80 MiB per message." }
}
