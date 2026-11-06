package com.example.teethree.data

import expo.modules.t3terminal.TerminalOutputBuffer
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TerminalSummary(val id: String, val label: String, val status: String)

data class TerminalState(
    val terminalId: String,
    val status: String = "connecting",
    val terminals: List<TerminalSummary> = emptyList(),
    val canOperate: Boolean = false,
    val cols: Int = 80,
    val rows: Int = 24,
    val outputVersion: Long = 0,
    val error: String? = null,
)

data class TerminalOutputUpdate(val version: Long, val reset: Boolean, val bytes: ByteArray)

internal class TerminalOutputJournal {
    private val retained = TerminalOutputBuffer()
    private val pending = ArrayDeque<Pair<Long, ByteArray>>()
    private var pendingBytes = 0
    private var resetVersion = 0L
    var version = 0L
        private set

    fun append(text: String, reset: Boolean = false) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        version += 1
        retained.write(bytes, reset)
        if (reset) {
            resetVersion = version
            pending.clear()
            pendingBytes = 0
        }
        pending.addLast(version to bytes)
        pendingBytes += bytes.size
        while (pending.size > 256 || pendingBytes > 512 * 1024) {
            pendingBytes -= pending.removeFirst().second.size
        }
    }

    fun since(afterVersion: Long): TerminalOutputUpdate? {
        if (afterVersion >= version) return null
        if (
            afterVersion < resetVersion ||
                afterVersion < (pending.firstOrNull()?.first ?: version) - 1
        ) {
            return TerminalOutputUpdate(version, true, retained.data)
        }
        val chunks = pending.filter { it.first > afterVersion }.map { it.second }
        val result = ByteArray(chunks.sumOf(ByteArray::size))
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return TerminalOutputUpdate(version, false, result)
    }
}

class TerminalSession
internal constructor(
    parentScope: CoroutineScope,
    private val environmentState: StateFlow<RepositoryState>,
    private val client: () -> EffectRpcClient?,
    val threadId: String,
    terminalId: String,
    private val cwd: String,
    private val worktreePath: String?,
) : AutoCloseable {
    private data class PendingWrite(
        val connection: EffectRpcClient,
        val terminalId: String,
        val text: String,
    )

    private data class PendingResize(
        val connection: EffectRpcClient,
        val terminalId: String,
        val cols: Int,
        val rows: Int,
    )

    private val scope =
        CoroutineScope(
            parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])
        )
    private val lock = Any()
    private val mutableState = MutableStateFlow(TerminalState(terminalId))
    val state: StateFlow<TerminalState> = mutableState.asStateFlow()
    private val output = TerminalOutputJournal()
    private val input = Channel<PendingWrite>(64)
    private val gridUpdates = Channel<PendingResize>(Channel.CONFLATED)
    private var attachmentGeneration = 0L
    private var activeClient: EffectRpcClient? = null
    private var subscription: String? = null
    private var metadataSubscription: String? = null
    private var started = false
    private var attachedOnce = false
    private var lastSequence = -1L
    private var measuredCols = 80
    private var measuredRows = 24

    fun start() =
        synchronized(lock) {
            if (started) return@synchronized
            started = true
            scope.launch {
                for (pending in input) operation {
                    check(operatingClient() === pending.connection) {
                        "Terminal disconnected before input could be sent."
                    }
                    for (chunk in terminalWriteChunks(pending.text)) {
                        pending.connection.call(
                            "terminal.write",
                            target(pending.terminalId) { put("data", chunk) },
                        )
                    }
                }
            }
            scope.launch {
                for (pending in gridUpdates) operation {
                    if (operatingClient() === pending.connection)
                        pending.connection.call(
                            "terminal.resize",
                            target(pending.terminalId) {
                                put("cols", pending.cols)
                                put("rows", pending.rows)
                            },
                        )
                }
            }
            scope.launch {
                environmentState
                    .map { Triple(it.connectionStatus, client(), it.scopes) }
                    .distinctUntilChanged()
                    .collect { (status, connection, scopes) ->
                        try {
                            synchronized(lock) {
                                detach()
                                val canOperate = "terminal:operate" in scopes
                                mutableState.update { it.copy(canOperate = canOperate) }
                                if (status != ConnectionStatus.Connected || connection == null) {
                                    mutableState.update { it.copy(status = "connecting") }
                                } else if (!canOperate && "terminal:read" !in scopes) {
                                    mutableState.update {
                                        it.copy(
                                            status = "error",
                                            error =
                                                "This connection does not have terminal access.",
                                        )
                                    }
                                } else {
                                    activeClient = connection
                                    metadataSubscription =
                                        connection.subscribe(
                                            "subscribeTerminalMetadata",
                                            JsonObject(emptyMap()),
                                            onError = ::subscriptionError,
                                        ) { event ->
                                            synchronized(lock) {
                                                if (activeClient === connection)
                                                    applyMetadata(event)
                                            }
                                        }
                                    if (canOperate) attach(resume = attachedOnce)
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            subscriptionError(error)
                        }
                    }
            }
        }

    suspend fun launchProjectAction(launch: ProjectActionLaunch): String {
        require(launch.threadId == threadId) { "The terminal belongs to another conversation." }
        val connection = client() ?: error("Environment is disconnected.")
        fun checkCurrent() {
            if (
                !scope.isActive ||
                    client() !== connection ||
                    environmentState.value.connectionStatus != ConnectionStatus.Connected
            )
                throw CancellationException("Environment changed")
            check("terminal:operate" in environmentState.value.scopes) {
                "This connection has read-only terminal access."
            }
        }
        checkCurrent()
        try {
            val snapshot =
                connection.call(
                    "terminal.open",
                    target(launch.terminalId) {
                        put("cwd", launch.cwd)
                        launch.worktreePath?.let { put("worktreePath", it) }
                        put("env", launch.env)
                        put("cols", measuredCols)
                        put("rows", measuredRows)
                    },
                ) as? JsonObject ?: error("Invalid terminal response.")
            checkCurrent()
            check(snapshot.string("status") == "running") { "The terminal did not start." }
            // Commands are sent only here; subsequent terminal attachments never replay them.
            for (chunk in terminalWriteChunks(launch.initialInput)) {
                checkCurrent()
                connection.call("terminal.write", target(launch.terminalId) { put("data", chunk) })
                checkCurrent()
            }
            return launch.terminalId
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw ProjectActionLaunchFailure(launch.terminalId, failure)
        }
    }

    fun outputSince(version: Long): TerminalOutputUpdate? =
        synchronized(lock) { output.since(version) }

    fun write(text: String) =
        synchronized(lock) {
            if (text.isEmpty() || !state.value.canOperate) return@synchronized
            val connection = activeClient ?: return@synchronized
            if (!input.trySend(PendingWrite(connection, state.value.terminalId, text)).isSuccess)
                subscriptionError(
                    IllegalStateException("Terminal input is busy. Wait before sending more text.")
                )
        }

    fun paste(text: String) = write(encodeTerminalPaste(text))

    fun resize(cols: Int, rows: Int) =
        synchronized(lock) {
            val nextCols = cols.coerceIn(2, 400)
            val nextRows = rows.coerceIn(2, 200)
            if (measuredCols == nextCols && measuredRows == nextRows) return@synchronized
            measuredCols = nextCols
            measuredRows = nextRows
            if (state.value.status == "running" && state.value.canOperate) sendResize()
        }

    fun selectTerminal(id: String) =
        synchronized(lock) {
            if (id == state.value.terminalId) return@synchronized
            subscription?.let { activeClient?.unsubscribe(it) }
            subscription = null
            attachedOnce = false
            output.append("", reset = true)
            mutableState.update {
                it.copy(
                    terminalId = id,
                    status = "connecting",
                    outputVersion = output.version,
                    error = null,
                )
            }
            attach(resume = false)
        }

    fun newTerminal() {
        if (state.value.canOperate) selectTerminal("term-${UUID.randomUUID().toString().take(8)}")
    }

    fun restart(): Job {
        val id = state.value.terminalId
        return scope.launch {
            operation {
                val connection = operatingClient()
                connection.call(
                    "terminal.open",
                    target(id) {
                        put("cwd", worktreePath ?: cwd)
                        worktreePath?.let { put("worktreePath", it) }
                        put("env", projectTerminalEnv(cwd, worktreePath))
                        put("cols", measuredCols)
                        put("rows", measuredRows)
                    },
                )
                synchronized(lock) {
                    if (state.value.terminalId == id && activeClient === connection) {
                        subscription?.let { connection.unsubscribe(it) }
                        subscription = null
                        attach(resume = true)
                    }
                }
            }
        }
    }

    fun clear() = command("terminal.clear") {}

    fun closeTerminal(deleteHistory: Boolean = false) =
        command("terminal.close") { put("deleteHistory", deleteHistory) }

    override fun close() =
        synchronized(lock) {
            detach()
            scope.cancel()
            input.close()
            gridUpdates.close()
            Unit
        }

    private fun attach(resume: Boolean) {
        val connection = activeClient ?: return
        val id = state.value.terminalId
        val generation = ++attachmentGeneration
        lastSequence = -1
        val operating = state.value.canOperate
        val payload = target {
            if (operating) {
                put("cwd", worktreePath ?: cwd)
                worktreePath?.let { put("worktreePath", it) }
                put("env", projectTerminalEnv(cwd, worktreePath))
                put("cols", measuredCols)
                put("rows", measuredRows)
                put("restartIfNotRunning", !resume)
                put("resume", resume)
            }
        }
        subscription =
            connection.subscribe(
                if (operating) "terminal.attach" else "terminal.observe",
                payload,
                onError = { error ->
                    synchronized(lock) {
                        if (
                            generation == attachmentGeneration &&
                                id == state.value.terminalId &&
                                state.value.status != "closed"
                        )
                            subscriptionError(error)
                    }
                },
            ) { event ->
                synchronized(lock) {
                    if (generation == attachmentGeneration && id == state.value.terminalId)
                        applyEvent(event)
                }
            }
    }

    private fun applyMetadata(event: JsonObject) {
        val current = state.value.terminals
        val terminals =
            when (event.string("type")) {
                "snapshot" ->
                    event.array("terminals").mapNotNull { raw ->
                        (raw as? JsonObject)
                            ?.takeIf { it.string("threadId") == threadId }
                            ?.let(::summary)
                    }
                "upsert" -> {
                    val terminal = event.obj("terminal")
                    if (terminal.string("threadId") != threadId) return
                    current.filterNot { it.id == terminal.string("terminalId") } + summary(terminal)
                }
                "remove" ->
                    if (event.string("threadId") == threadId)
                        current.filterNot { it.id == event.string("terminalId") }
                    else current
                else -> current
            }
        mutableState.update { it.copy(terminals = terminals) }
        if (!state.value.canOperate && subscription == null && terminals.isNotEmpty()) {
            if (terminals.none { it.id == state.value.terminalId })
                mutableState.update { it.copy(terminalId = terminals.first().id) }
            attach(resume = true)
        }
        if (!state.value.canOperate && terminals.isEmpty())
            mutableState.update {
                it.copy(status = "closed", error = "No terminals are available to view.")
            }
    }

    private fun applyEvent(event: JsonObject) {
        val type = event.string("type")
        if (type == "snapshot" || type == "restarted") {
            val snapshot = event.obj("snapshot")
            attachedOnce = true
            lastSequence = snapshot.long("sequence", -1)
            output.append(snapshot.string("history"), reset = true)
            mutableState.update {
                it.copy(
                    status = snapshot.string("status"),
                    cols = snapshot.long("cols", measuredCols.toLong()).toInt(),
                    rows = snapshot.long("rows", measuredRows.toLong()).toInt(),
                    outputVersion = output.version,
                    error = null,
                )
            }
            if (state.value.status == "running" && state.value.canOperate) sendResize()
            return
        }
        val sequence = event.long("sequence", -1)
        if (sequence >= 0 && lastSequence >= 0 && sequence <= lastSequence) return
        if (sequence >= 0) lastSequence = sequence
        when (type) {
            "output" -> {
                output.append(event.string("data"))
                mutableState.update { it.copy(outputVersion = output.version, error = null) }
            }
            "cleared" -> {
                output.append("", reset = true)
                mutableState.update { it.copy(outputVersion = output.version, error = null) }
            }
            "exited",
            "closed" -> mutableState.update { it.copy(status = type) }
            "error" ->
                mutableState.update { it.copy(status = "error", error = event.string("message")) }
            "activity" ->
                mutableState.update {
                    it.copy(
                        cols = event.long("cols", it.cols.toLong()).toInt(),
                        rows = event.long("rows", it.rows.toLong()).toInt(),
                    )
                }
        }
    }

    private fun sendResize() {
        val connection = activeClient ?: return
        gridUpdates.trySend(
            PendingResize(connection, state.value.terminalId, measuredCols, measuredRows)
        )
    }

    private fun detach() {
        attachmentGeneration += 1
        subscription?.let { activeClient?.unsubscribe(it) }
        metadataSubscription?.let { activeClient?.unsubscribe(it) }
        subscription = null
        metadataSubscription = null
        activeClient = null
    }

    private fun operatingClient(): EffectRpcClient {
        check("terminal:operate" in environmentState.value.scopes) {
            "This connection has read-only terminal access."
        }
        return checkNotNull(activeClient) { "Environment is disconnected." }
    }

    private fun target(
        terminalId: String = state.value.terminalId,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("threadId", threadId)
        put("terminalId", terminalId)
        extra()
    }

    private fun summary(value: JsonObject) =
        TerminalSummary(value.string("terminalId"), value.string("label"), value.string("status"))

    private fun command(
        method: String,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): Job {
        val payload = target(extra = extra)
        return scope.launch { operation { operatingClient().call(method, payload) } }
    }

    private fun subscriptionError(error: Throwable) {
        mutableState.update {
            it.copy(error = error.message ?: "The terminal could not be reached.")
        }
    }

    private suspend fun operation(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            subscriptionError(error)
        }
    }
}

internal fun terminalWriteChunks(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 65_536, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate()) end -= 1
        add(text.substring(start, end))
        start = end
    }
}

internal fun encodeTerminalPaste(text: String): String =
    text
        .replace(Regex("[\\u0000-\\u0008\\u000b\\u000c\\u000e-\\u001f\\u007f]"), " ")
        .replace(Regex("\\r\\n|\\n"), "\r")
