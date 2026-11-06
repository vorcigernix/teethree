package com.example.teethree.data

import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ThreadFindMatch(val entryId: String, val runId: String?, val occurrence: Int)

data class ThreadFindGroup(
    val entryId: String,
    val runId: String?,
    val startIndex: Int,
    val count: Int,
)

data class ThreadFindResult(
    val complete: Boolean,
    val snapshotSequence: Long,
    val totalMatches: Int,
    val activeIndex: Int,
    val match: ThreadFindMatch?,
    val navigation: List<ThreadFindGroup>,
)

@Serializable
data class ThreadSearchJump(
    val connectionId: String,
    val threadId: String,
    val entryId: String,
    val occurrence: Int,
    val query: String,
    val id: String = UUID.randomUUID().toString(),
)

internal fun threadFindPayload(
    threadId: String,
    query: String,
    index: Int? = null,
    start: ThreadFindMatch? = null,
    offset: Int = 0,
): JsonObject {
    val trimmed = query.trim()
    require(threadId.isNotBlank() && trimmed.isNotEmpty() && trimmed.length <= 200) {
        "Enter between 1 and 200 characters to search."
    }
    require(index == null || index >= 0)
    require(start == null || (start.entryId.isNotBlank() && start.occurrence >= 0))
    return buildJsonObject {
        put("threadId", threadId)
        put("query", trimmed)
        index?.let { put("index", it) }
        start?.let { match ->
            put(
                "start",
                buildJsonObject {
                    put("entryId", match.entryId)
                    put("occurrence", match.occurrence)
                },
            )
        }
        if (offset != 0) put("offset", offset)
    }
}

internal fun parseThreadFindResult(value: JsonObject): ThreadFindResult {
    fun nonnegative(value: JsonObject, key: String): Int =
        value
            .long(key, -1)
            .also { require(it in 0..Int.MAX_VALUE.toLong()) { "Invalid search response: $key." } }
            .toInt()
    val count = nonnegative(value, "totalMatches")
    val active = nonnegative(value, "activeIndex")
    val sequence = value.long("snapshotSequence", -1).also { require(it >= 0) }
    val match =
        (value["match"] as? JsonObject)?.let { raw ->
            ThreadFindMatch(
                raw.string("entryId").also { require(it.isNotBlank()) },
                raw.nullableString("runId"),
                nonnegative(raw, "occurrence"),
            )
        }
    require(
        (count == 0 && active == 0 && match == null) ||
            (count > 0 && active < count && match != null)
    ) {
        "The search selection is inconsistent."
    }
    val groups =
        value.array("navigation").map { raw ->
            raw.jsonObject.let { row ->
                ThreadFindGroup(
                    row.string("entryId").also { require(it.isNotBlank()) },
                    row.nullableString("runId"),
                    nonnegative(row, "startIndex"),
                    nonnegative(row, "count").also { require(it > 0) },
                )
            }
        }
    require(groups.size <= 17)
    var end = 0L
    groups.forEach { group ->
        require(
            group.startIndex.toLong() >= end && group.startIndex.toLong() + group.count <= count
        )
        end = group.startIndex.toLong() + group.count
    }
    return ThreadFindResult(value.bool("complete", true), sequence, count, active, match, groups)
}

internal fun threadSearchOffsets(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    fun fold(source: String): String {
        val output = StringBuilder()
        var cursor = 0
        while (cursor < source.length) {
            val code = source.codePointAt(cursor)
            val character = String(Character.toChars(code))
            val lowered =
                character
                    .lowercase(Locale.ROOT)
                    .replace('ς', 'σ')
                    .replace('‘', '\'')
                    .replace('’', '\'')
            output.append(if (lowered.length == character.length) lowered else character)
            cursor += character.length
        }
        return output.toString()
    }
    val source = fold(text)
    val needle = fold(query)
    val offsets = mutableListOf<Int>()
    var cursor = 0
    while (cursor <= source.length - needle.length) {
        val found = source.indexOf(needle, cursor)
        if (found < 0) break
        offsets.add(found)
        cursor = found + needle.length
    }
    return offsets
}

class ThreadSearchTools(private val repository: T3Repository) {
    fun find(
        threadId: String,
        query: String,
        index: Int? = null,
        start: ThreadFindMatch? = null,
        offset: Int = 0,
    ): Flow<ThreadFindResult> = callbackFlow {
        val environment =
            repository.connectionId
                ?: throw IllegalStateException("Connect to an environment to search its history.")
        val payload = threadFindPayload(threadId, query, index, start, offset)
        val stream =
            repository.streamTool(
                "orchestration.searchThreadStream",
                payload,
                onError = { close(it) },
            ) { value ->
                if (
                    repository.connectionId != environment ||
                        repository.state.value.selectedThreadId != threadId
                )
                    close(CancellationException("The conversation changed."))
                else
                    try {
                        val result = parseThreadFindResult(value)
                        trySend(result)
                        if (result.complete) close()
                    } catch (failure: Exception) {
                        close(failure)
                    }
            }
        if (repository.connectionId != environment) {
            stream.close()
            throw CancellationException("The environment changed.")
        }
        awaitClose { stream.close() }
    }
        .distinctUntilChanged()
}
