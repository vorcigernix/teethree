package com.example.teethree.data

import com.example.teethree.ConnectionStore
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal class PullRequestDraftPersistence(
    private val key: String,
    private val read: () -> String?,
    private val write: (String, () -> Boolean) -> Boolean,
    private val isCurrent: () -> Boolean,
    onError: (String) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        store: ConnectionStore,
        connectionId: String,
        request: WorkspacePullRequest,
        isCurrent: () -> Boolean,
        onError: (String) -> Unit,
    ) : this(
        reviewDraftStorageKey(connectionId, request.ref().toString()),
        { store.read(reviewDraftStorageKey(connectionId, request.ref().toString())) },
        { value, current ->
            store.writeIf(
                reviewDraftStorageKey(connectionId, request.ref().toString()),
                value,
                current,
            )
        },
        isCurrent,
        onError,
    )

    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val worker = scope.launch {
        try {
            for (ignored in updates) {
                lock.withLock {
                    val value = pending[key] ?: return@withLock
                    if (value.owner !== this@PullRequestDraftPersistence) return@withLock
                    if (!isCurrent()) {
                        pending.remove(key, value)
                        return@withLock
                    }
                    try {
                        write(value.value.toString(), isCurrent)
                        pending.remove(key, value)
                    } catch (failure: Exception) {
                        onError("Review drafts could not be saved on this device.")
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }

    suspend fun load(): JsonObject =
        withContext(dispatcher) {
            lock.withLock {
                val queued = pending[key]
                if (queued != null && queued.isCurrent()) return@withLock queued.value
                if (queued != null) pending.remove(key, queued)
                try {
                    read()?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
                } catch (failure: Exception) {
                    throw IOException("Saved review drafts could not be read.")
                }
            }
        }

    fun save(value: JsonObject) {
        if (!isCurrent()) return
        pending[key] = PendingDraft(this, value, isCurrent)
        updates.trySend(Unit)
    }

    fun close() {
        if (!isCurrent())
            pending[key]?.takeIf { it.owner === this }?.let { pending.remove(key, it) }
        updates.close()
    }

    suspend fun awaitClosed() = worker.join()

    private companion object {
        data class PendingDraft(val owner: Any, val value: JsonObject, val isCurrent: () -> Boolean)

        val pending = ConcurrentHashMap<String, PendingDraft>()
        val lock = Mutex()
    }
}

internal fun reviewDraftStorageKey(connectionId: String, reference: String): String =
    "review-draft:$connectionId:" +
        MessageDigest.getInstance("SHA-256").digest(reference.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
