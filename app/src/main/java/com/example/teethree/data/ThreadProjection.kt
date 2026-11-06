package com.example.teethree.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal class ThreadProjection(
    snapshot: JsonObject,
    private var sequence: Long,
    private val latestLocalOrdinal: Long? = null,
    private val partial: Boolean = false,
) {
    private data class Row(
        val sourceThreadId: String,
        val sourceItemId: String,
        val inherited: Boolean,
        var raw: JsonObject,
    ) {
        var parsed: TimelineItem = parseTimelineItem(raw, "$sourceThreadId:$sourceItemId")
    }

    private val rows =
        snapshot
            .array("visibleTurnItems")
            .map { raw ->
                val row = raw.jsonObject
                Row(
                    row.string("sourceThreadId"),
                    row.string("sourceItemId"),
                    row.string("visibility") != "local",
                    row.obj("item"),
                )
            }
            .toMutableList()
    private val items =
        snapshot.array("turnItems").associateByTo(linkedMapOf()) { it.jsonObject.string("id") }
    private val runs =
        snapshot
            .array("runs")
            .associate { it.jsonObject.let { run -> run.string("id") to run.string("status") } }
            .toMutableMap()
    private val attempts =
        snapshot.array("attempts").associateByTo(linkedMapOf()) { it.jsonObject.string("id") }
    private val requests =
        snapshot.array("runtimeRequests").associateByTo(linkedMapOf()) {
            it.jsonObject.string("id")
        }

    private val subagentRecords =
        snapshot.array("subagents").filterIsInstance<JsonObject>().associateByTo(linkedMapOf()) {
            it.string("id")
        }
    private val runRecords =
        snapshot.array("runs").filterIsInstance<JsonObject>().associateByTo(linkedMapOf()) {
            it.string("id")
        }
    private var cachedTimeline: List<TimelineItem>? = null

    fun subagents(): JsonArray =
        currentTurnSubagents(
            JsonArray(runRecords.values.toList()),
            JsonArray(subagentRecords.values.toList()),
        )

    fun applyEvent(frame: JsonObject) {
        val nextSequence = frame.long("sequence")
        if (nextSequence <= sequence) return
        sequence = nextSequence
        val event = frame.obj("event")
        val payload = event.obj("payload")
        if (event.string("type") != "subagent.updated") cachedTimeline = null
        when (event.string("type")) {
            "run.created",
            "run.updated" -> {
                runs[payload.string("id")] = payload.string("status")
                runRecords[payload.string("id")] = payload
            }
            "subagent.updated" -> {
                val id = payload.string("id")
                if (id.isNotBlank())
                    subagentRecords[id] = restoreSubagentBodies(subagentRecords[id], payload)
            }
            "run-attempt.created",
            "run-attempt.updated" -> attempts[payload.string("id")] = payload
            "runtime-request.updated" -> requests[payload.string("id")] = payload
            "turn-item.updated" -> {
                val id = payload.string("id")
                if (id.isEmpty()) return
                val existing = rows.indexOfLast { it.sourceItemId == id && !it.inherited }
                if (existing < 0 && outsideWindow(payload)) return
                items[id] = payload
                if (existing >= 0) {
                    rows[existing].raw = payload
                    rows[existing].parsed = parseTimelineItem(payload, rows[existing].parsed.id)
                } else {
                    val row = Row(payload.string("threadId"), id, false, payload)
                    val insertion = rows.indexOfFirst {
                        !it.inherited && it.raw.long("ordinal") > payload.long("ordinal")
                    }
                    if (insertion < 0) rows.add(row) else rows.add(insertion, row)
                }
            }
        }
    }

    fun timeline(): List<TimelineItem> =
        cachedTimeline
            ?: rows
                .filter { it.inherited || visible(it.raw) }
                .map(Row::parsed)
                .also { cachedTimeline = it }

    fun mergeHistory(page: JsonObject) {
        cachedTimeline = null
        val existing = rows.associateBy { "${it.sourceThreadId}:${it.sourceItemId}" }
        val addedKeys = hashSetOf<String>()
        val older =
            page.array("items").mapNotNull { raw ->
                val value = raw.jsonObject
                val key = "${value.string("sourceThreadId")}:${value.string("sourceItemId")}"
                if (!addedKeys.add(key)) return@mapNotNull null
                existing[key]
                    ?: run {
                        val inherited = value.string("visibility") != "local"
                        val latest =
                            if (inherited) value.obj("item")
                            else
                                (items[value.string("sourceItemId")] as? JsonObject)
                                    ?: value.obj("item")
                        if (!inherited && !visible(latest)) null
                        else
                            Row(
                                    value.string("sourceThreadId"),
                                    value.string("sourceItemId"),
                                    inherited,
                                    latest,
                                )
                                .also {
                                    if (!inherited) items[it.sourceItemId] = latest
                                }
                    }
            }
        val retained = rows.filterNot { "${it.sourceThreadId}:${it.sourceItemId}" in addedKeys }
        rows.clear()
        rows.addAll(older)
        rows.addAll(retained)
    }

    fun pendingRequests(): List<RuntimeRequest> =
        requests.values.mapNotNull { raw ->
            val request = raw.jsonObject
            if (request.string("status") != "pending") return@mapNotNull null
            val item = rows.lastOrNull { it.raw.string("requestId") == request.string("id") }?.raw
            val type = item?.string("type")
            val capability = request.obj("responseCapability").string("type")
            val approval = type == "approval_request"
            val question = type == "user_input_request"
            RuntimeRequest(
                id = request.string("id"),
                kind = request.string("kind"),
                prompt =
                    item
                        ?.string("prompt")
                        ?.ifBlank { item.string("reason") }
                        ?.ifBlank { item.string("title") }
                        ?: "This request needs a compatible client.",
                canRespond =
                    (approval && capability == "live") ||
                        (question && capability in setOf("live", "message")),
                options = if (approval) approvalOptions(checkNotNull(item)) else emptyList(),
                questions =
                    item?.array("questions")?.map { value ->
                        val q = value.jsonObject
                        UserQuestion(
                            q.string("id"),
                            q.string("question"),
                            q.array("options").map { it.jsonObject.string("label") },
                            q.bool("multiSelect"),
                            q.bool("allowCustomAnswer", true),
                            q.nullableString("initialAnswer"),
                            q.bool("required", true),
                        )
                    } ?: emptyList(),
            )
        }

    fun answerValues(requestId: String, questionId: String, values: List<String>): List<String> {
        val item = rows.lastOrNull { it.raw.string("requestId") == requestId }?.raw
        val question =
            item
                ?.array("questions")
                ?.firstOrNull { it.jsonObject.string("id") == questionId }
                ?.jsonObject
        return values.map { value ->
            question
                ?.array("options")
                ?.firstOrNull { it.jsonObject.string("label") == value }
                ?.jsonObject
                ?.nullableString("value") ?: value
        }
    }

    private fun visible(item: JsonObject): Boolean {
        val runId = item.nullableString("runId")
        val status = runs[runId]
        if (status == "rolled_back") return false
        if (
            status == "cancelled" &&
                item.string("type") == "user_message" &&
                item.string("inputIntent") == "queued_turn"
        )
            return false
        if (
            item.string("type") == "run_interrupt_result" &&
                runId != null &&
                item.nullableString("nodeId") != null
        ) {
            val superseded =
                attempts.values.any { raw ->
                    val attempt = raw.jsonObject
                    attempt.string("runId") == runId &&
                        attempt.string("rootNodeId") == item.string("nodeId") &&
                        attempt.string("status") == "superseded"
                }
            if (
                superseded &&
                    items.values.none {
                        it.jsonObject.string("type") == "run_interrupt_request" &&
                            it.jsonObject.string("runId") == runId
                    }
            )
                return false
        }
        return true
    }

    private fun outsideWindow(item: JsonObject): Boolean {
        if (!partial) return false
        val runId = item.nullableString("runId")
        if (runs[runId] in setOf("queued", "preparing", "starting", "running", "waiting"))
            return false
        if (
            rows.any {
                !it.inherited &&
                    it.raw.nullableString("runId") == runId &&
                    it.raw.long("ordinal") <= item.long("ordinal")
            }
        )
            return false
        val watermark =
            latestLocalOrdinal
                ?: rows.filterNot(Row::inherited).minOfOrNull { it.raw.long("ordinal") }
                ?: return false
        return item.long("ordinal") <= watermark
    }
}

internal fun parseTimelineItem(item: JsonObject, stableId: String): TimelineItem {
    val type = item.string("type")
    val text =
        when (type) {
            "proposed_plan" -> item.string("markdown")
            "command_execution" ->
                listOf(item.string("input"), item.string("output"))
                    .filter(String::isNotBlank)
                    .joinToString("\n")
            "file_change" ->
                listOf(item.string("fileName"), item.string("diffStr"))
                    .filter(String::isNotBlank)
                    .joinToString("\n")
            "todo_list" ->
                item.array("steps").joinToString("\n") { raw ->
                    raw.jsonObject.let { "${it.string("status")}: ${it.string("text")}" }
                }
            "approval_request" -> item.string("prompt").ifBlank { item.string("reason") }
            "user_input_request" ->
                item.array("questions").joinToString("\n") { it.jsonObject.string("question") }
            "file_search" -> item.string("pattern")
            "web_search" -> item.array("patterns").joinToString(", ") { it.toString().trim('"') }
            else -> item.string("text").ifBlank { item.string("message") }
        }
    return TimelineItem(
        id = stableId,
        type =
            when (type) {
                "user_message" -> "user"
                "assistant_message" -> "assistant"
                "proposed_plan" -> "plan"
                else -> type
            },
        text = text,
        title =
            item.nullableString("title")
                ?: item.nullableString("fileName")
                ?: item.nullableString("input"),
        status = item.string("status"),
        ordinal = item.long("ordinal"),
        streaming = item.bool("streaming"),
        requestId = item.nullableString("requestId"),
        options = if (type == "approval_request") approvalOptions(item) else emptyList(),
        attachments =
            item.array("attachments").mapNotNull { raw ->
                val attachment = raw as? JsonObject ?: return@mapNotNull null
                val id = attachment.string("id")
                if (id.isBlank()) null
                else
                    ChatAttachment(
                        id,
                        attachment.string("type", "file"),
                        attachment.string("name", "Attachment"),
                        attachment.string("mimeType", "application/octet-stream"),
                        attachment.long("sizeBytes"),
                    )
            },
        mcpApp =
            parseNativeMcpApp(
                item.obj("output")["t3McpApp"],
                item.string("threadId"),
                item.string("id"),
            ),
        searchEntryId =
            if (type in setOf("user_message", "assistant_message")) item.nullableString("messageId")
            else item.nullableString("id"),
        context = item["context"] as? JsonObject,
    )
}

private fun approvalOptions(item: JsonObject): List<ApprovalOption> =
    if (item.containsKey("options")) {
        item.array("options").mapNotNull { value ->
            val option = value.jsonObject
            val decision = option.string("decision")
            if (
                decision !in
                    setOf("accept", "acceptForSession", "acceptAlways", "decline", "cancel")
            )
                null
            else ApprovalOption(decision, option.string("label"), option.nullableString("warning"))
        }
    } else listOf(ApprovalOption("accept", "Allow once"), ApprovalOption("decline", "Decline"))
