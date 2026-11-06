package com.example.teethree.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ConversationRun(
    val id: String,
    val ordinal: Long,
    val status: String,
    val prompt: String,
    val requestedAt: String,
)

data class QueuedMessage(
    val runId: String,
    val text: String,
    val attachmentCount: Int,
    val held: Boolean,
    val editable: Boolean,
)

data class CheckpointFile(val path: String, val additions: Long, val deletions: Long)

data class ConversationCheckpoint(
    val id: String,
    val scopeId: String,
    val runId: String?,
    val turnCount: Long?,
    val status: String?,
    val capturedAt: String,
    val files: List<CheckpointFile>,
    val fileCount: Long,
) {
    val canRequestRestore: Boolean
        get() = status == null || status == "ready"
}

data class ConversationHistory(
    val threadId: String,
    val runs: List<ConversationRun> = emptyList(),
    val checkpoints: List<ConversationCheckpoint> = emptyList(),
    val queue: List<QueuedMessage> = emptyList(),
    val activeRunId: String? = null,
    val restoreFailure: String? = null,
)

internal class ConversationHistoryReducer(
    private val threadId: String,
    snapshot: JsonObject,
    private var sequence: Long,
) {
    private val runs = records(snapshot, "runs")
    private val messages =
        records(snapshot, "messages").filterValues { it.string("role") == "user" }.toMutableMap()
    private val checkpoints = records(snapshot, "checkpoints")
    private var restoreFailure =
        snapshot.obj("thread").obj("rollbackFailure").nullableString("message")

    init {
        mergeRows(snapshot.array("visibleTurnItems"))
    }

    fun applyEvent(frame: JsonObject): Boolean {
        val nextSequence = frame.long("sequence")
        if (nextSequence <= sequence) return false
        sequence = nextSequence
        val event = frame.obj("event")
        val value = event.obj("payload")
        return when (event.string("type")) {
            "run.created",
            "run.updated" -> put(runs, value)
            "message.updated" -> if (value.string("role") == "user") put(messages, value) else false
            "checkpoint.captured" -> put(checkpoints, value)
            "turn-item.updated" -> mergeItem(value)
            "thread.metadata-updated" -> {
                val next = value.obj("rollbackFailure").nullableString("message")
                (next != restoreFailure).also { restoreFailure = next }
            }
            else -> false
        }
    }

    fun mergeHistory(page: JsonObject): Boolean = mergeRows(page.array("items"))

    fun value(): ConversationHistory {
        val orderedRuns = runs.values.sortedBy { it.long("ordinal") }
        val queue =
            orderedRuns
                .filter { it.string("status") == "queued" }
                .sortedWith(
                    compareByDescending<JsonObject> {
                            messages[it.string("userMessageId")]?.present("delegatedCompletion") ==
                                true
                        }
                        .thenBy {
                            it.nullableString("queuePosition")?.toLongOrNull() ?: it.long("ordinal")
                        }
                        .thenBy { it.long("ordinal") }
                )
                .map { run ->
                    val message = messages[run.string("userMessageId")]
                    QueuedMessage(
                        run.string("id"),
                        message?.string("text").orEmpty(),
                        message?.array("attachments")?.size ?: 0,
                        run.bool("queueHeld"),
                        message != null &&
                            !message.present("delegatedCompletion") &&
                            !message.present("notification"),
                    )
                }
        return ConversationHistory(
            threadId,
            orderedRuns.map { run ->
                ConversationRun(
                    run.string("id"),
                    run.long("ordinal"),
                    run.string("status"),
                    messages[run.string("userMessageId")]?.string("text").orEmpty(),
                    run.string("requestedAt"),
                )
            },
            checkpoints.values
                .map { value ->
                    val files =
                        value.array("files").map { raw ->
                            raw.jsonObject.let {
                                CheckpointFile(
                                    it.string("path"),
                                    it.long("additions"),
                                    it.long("deletions"),
                                )
                            }
                        }
                    ConversationCheckpoint(
                        value.string("id"),
                        value.string("scopeId"),
                        value.nullableString("runId"),
                        if (value.containsKey("appRunOrdinal"))
                            value.nullableString("appRunOrdinal")?.toLongOrNull()
                        else runs[value.string("runId")]?.nullableString("ordinal")?.toLongOrNull(),
                        value.nullableString("status"),
                        value.string("capturedAt"),
                        files,
                        value.obj("filesTotal").long("count", files.size.toLong()),
                    )
                }
                .sortedWith(
                    compareByDescending<ConversationCheckpoint> { it.turnCount ?: -1L }
                        .thenByDescending { it.capturedAt }
                ),
            queue,
            orderedRuns
                .lastOrNull {
                    it.string("status") in setOf("preparing", "starting", "running", "waiting")
                }
                ?.string("id"),
            restoreFailure,
        )
    }

    private fun mergeRows(rows: JsonArray): Boolean {
        var changed = false
        for (raw in rows) {
            val row = raw as? JsonObject ?: continue
            if (row.string("sourceThreadId") != threadId) continue
            changed = mergeItem(row.obj("item")) || changed
        }
        return changed
    }

    private fun mergeItem(item: JsonObject): Boolean =
        when (item.string("type")) {
            "user_message" -> {
                val id = item.string("messageId")
                if (id.isEmpty() || messages.containsKey(id)) false
                else
                    put(
                        messages,
                        JsonObject(
                            item + ("id" to JsonPrimitive(id)) + ("role" to JsonPrimitive("user"))
                        ),
                    )
            }
            "checkpoint" -> {
                val id = item.string("checkpointId")
                if (id.isEmpty() || checkpoints.containsKey(id)) false
                else {
                    // History rows omit checkpoint availability; the server checks it before a
                    // restore.
                    val checkpoint = buildJsonObject {
                        put("id", id)
                        put("scopeId", item.string("scopeId"))
                        item["runId"]?.let { put("runId", it) }
                        put("files", item.array("files"))
                        item["filesTotal"]?.let { put("filesTotal", it) }
                        put("capturedAt", item.string("updatedAt"))
                    }
                    put(checkpoints, checkpoint)
                }
            }
            else -> false
        }

    private fun put(destination: MutableMap<String, JsonObject>, value: JsonObject): Boolean {
        val id = value.string("id")
        if (id.isEmpty() || destination[id] == value) return false
        destination[id] = value
        return true
    }

    private fun records(snapshot: JsonObject, key: String): MutableMap<String, JsonObject> =
        snapshot
            .array(key)
            .mapNotNull { raw -> (raw as? JsonObject)?.takeIf { it.string("id").isNotEmpty() } }
            .associateByTo(linkedMapOf()) { it.string("id") }

    private fun JsonObject.present(key: String): Boolean =
        this[key] != null && this[key] != JsonNull
}

class ConversationTools(private val repository: T3Repository) {
    suspend fun turnDiff(threadId: String, turnCount: Long): String {
        require(turnCount > 0) { "Choose a completed turn." }
        selected(threadId)
        return repository
            .callTool(
                "orchestration.getTurnDiff",
                buildJsonObject {
                    put("threadId", threadId)
                    put("fromTurnCount", turnCount - 1)
                    put("toTurnCount", turnCount)
                },
            )
            .jsonObject
            .string("diff")
    }

    suspend fun fullDiff(threadId: String): String {
        val history = selected(threadId)
        val last =
            history.checkpoints.mapNotNull(ConversationCheckpoint::turnCount).maxOrNull() ?: 0L
        return repository
            .callTool(
                "orchestration.getFullThreadDiff",
                buildJsonObject {
                    put("threadId", threadId)
                    put("toTurnCount", last)
                },
            )
            .jsonObject
            .string("diff")
    }

    suspend fun restore(threadId: String, checkpointId: String, restoreFiles: Boolean) {
        val checkpoint = selected(threadId).checkpoints.firstOrNull { it.id == checkpointId }
        require(checkpoint != null && checkpoint.canRequestRestore) {
            "This checkpoint is unavailable. Refresh the conversation history."
        }
        dispatch(
            buildJsonObject {
                put("type", "checkpoint.rollback")
                put("threadId", threadId)
                put("scopeId", checkpoint.scopeId)
                put("checkpointId", checkpoint.id)
                put("restoreFiles", restoreFiles)
            }
        )
    }

    suspend fun fork(threadId: String, checkpointId: String? = null): String {
        val history = selected(threadId)
        if (checkpointId != null)
            require(history.checkpoints.any { it.id == checkpointId }) {
                "This checkpoint is no longer loaded."
            }
        val id = newId()
        dispatch(
            buildJsonObject {
                put("type", "thread.fork")
                put("sourceThreadId", threadId)
                put("targetThreadId", id)
                put("createdBy", "user")
                put("creationSource", "mobile")
                put(
                    "sourcePoint",
                    buildJsonObject {
                        put("type", if (checkpointId == null) "latest_stable" else "checkpoint")
                        if (checkpointId != null) put("checkpointId", checkpointId)
                    },
                )
            }
        )
        return id
    }

    suspend fun editQueued(threadId: String, runId: String, text: String) {
        val queued = queued(threadId, runId)
        require(text.isNotBlank() || queued.attachmentCount > 0) {
            "Enter a message or keep an attachment."
        }
        require(text.length <= 120_000) { "Messages can contain up to 120,000 characters." }
        queueCommand(threadId, runId, "queued-run.edit") { put("text", text) }
    }

    suspend fun cancelQueued(threadId: String, runId: String) =
        queueCommand(threadId, runId, "queued-run.cancel")

    suspend fun startQueued(threadId: String, runId: String) {
        require(selected(threadId).activeRunId == null) {
            "Stop the running turn before sending a queued message now."
        }
        queueCommand(threadId, runId, "queued-run.start")
    }

    suspend fun moveQueued(threadId: String, runId: String, beforeRunId: String?) {
        if (beforeRunId != null) queued(threadId, beforeRunId)
        require(beforeRunId != runId) { "Choose a different queue position." }
        queueCommand(threadId, runId, "queued-run.reorder") {
            put("beforeRunId", beforeRunId?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    suspend fun steerQueued(threadId: String, runId: String) {
        queued(threadId, runId)
        val active =
            checkNotNull(selected(threadId).activeRunId) { "There is no running turn to steer." }
        dispatch(
            buildJsonObject {
                put("type", "queued-message.promote-to-steer")
                put("threadId", threadId)
                put("queuedRunId", runId)
                put("targetRunId", active)
            }
        )
    }

    suspend fun resumeQueue(threadId: String) {
        require(selected(threadId).queue.isNotEmpty()) { "The queue is empty." }
        dispatch(
            buildJsonObject {
                put("type", "queue.resume")
                put("threadId", threadId)
            }
        )
    }

    private suspend fun queueCommand(
        threadId: String,
        runId: String,
        type: String,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) {
        queued(threadId, runId)
        dispatch(
            buildJsonObject {
                put("type", type)
                put("threadId", threadId)
                put("runId", runId)
                fields()
            }
        )
    }

    private suspend fun dispatch(command: JsonObject) {
        repository.callTool(
            "orchestration.dispatchCommand",
            JsonObject(command + ("commandId" to JsonPrimitive(newId()))),
        )
    }

    private fun queued(threadId: String, runId: String): QueuedMessage {
        val queued = selected(threadId).queue.firstOrNull { it.runId == runId }
        require(queued != null && queued.editable) {
            "This queued message can no longer be changed."
        }
        return queued
    }

    private fun selected(threadId: String): ConversationHistory {
        val state = repository.state.value
        require(state.selectedThreadId == threadId && state.history?.threadId == threadId) {
            "Open this conversation again before continuing."
        }
        return checkNotNull(state.history)
    }
}
