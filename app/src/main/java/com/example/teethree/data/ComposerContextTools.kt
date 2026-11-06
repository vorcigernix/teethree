package com.example.teethree.data

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ComposerContextSelection(
    val connectionId: String,
    val connectionGeneration: Long,
    val ownerThreadId: String?,
    val records: List<JsonObject>,
)

data class ComposerContextItem(val contextId: String, val kind: String, val label: String)

data class ComposerSkill(val name: String, val label: String, val description: String)

data class ComposerFileMatches(val paths: List<String>, val truncated: Boolean)

private val contextIdPattern = Regex("[a-zA-Z0-9_-]{1,128}")
private val contextKindPattern = Regex("[a-z][a-z0-9-]{0,39}")
private val contextReferencePattern =
    Regex(
        "!?\\[([^\\]\\n]{0,512})]\\(t3-context://v1/([a-z][a-z0-9-]{0,39})/([a-zA-Z0-9_-]{1,128})\\)"
    )
private val knownContextKinds =
    setOf(
        "image",
        "file",
        "terminal",
        "element",
        "preview-annotation",
        "review-comment",
        "mention",
        "skill",
        "thread",
        "environment",
    )

fun validateComposerContext(context: JsonObject?) {
    if (context == null) return
    fun obj(value: JsonElement?): JsonObject =
        value as? JsonObject ?: throw IllegalArgumentException("A context item is malformed.")
    fun text(row: JsonObject, key: String, max: Int, nonempty: Boolean = false): String {
        val value = requireNotNull(row[key] as? JsonPrimitive)
        require(value.isString) { "Invalid context field: $key." }
        return value.content.also {
            require(it.length <= max && (!nonempty || it.isNotBlank() && it == it.trim())) {
                "Context field exceeds its limit: $key."
            }
        }
    }
    fun number(row: JsonObject, key: String, positive: Boolean = false): Long {
        val value = requireNotNull(row[key] as? JsonPrimitive)
        require(!value.isString)
        return requireNotNull(value.longOrNull).also { require(it >= if (positive) 1 else 0) }
    }
    fun nullableText(row: JsonObject, key: String, max: Int = 2048) {
        require(row.containsKey(key))
        if (row[key] != JsonNull) text(row, key, max)
    }
    fun array(row: JsonObject, key: String, max: Int): JsonArray =
        (row[key] as? JsonArray)?.also { require(it.size <= max) }
            ?: throw IllegalArgumentException("Invalid context list: $key.")
    fun strings(row: JsonObject, key: String, max: Int, count: Int) {
        array(row, key, count).forEach { text(buildJsonObject { put("value", it) }, "value", max) }
    }
    fun element(row: JsonObject) {
        text(row, "pageUrl", 2048)
        nullableText(row, "pageTitle")
        text(row, "tagName", 255, true)
        nullableText(row, "selector")
        text(row, "htmlPreview", 8000)
        nullableText(row, "componentName")
        text(row, "styles", 8000)
        require(row.containsKey("source"))
        if (row["source"] != JsonNull)
            obj(row["source"]).let { source ->
                nullableText(source, "functionName")
                nullableText(source, "fileName")
                for (key in listOf("lineNumber", "columnNumber")) {
                    require(source.containsKey(key))
                    if (source[key] != JsonNull) number(source, key)
                }
            }
    }
    require(number(context, "version") == 1L) { "Unsupported message context version." }
    val records = array(context, "records", 200)
    require(records.toString().length <= 16_000_000) { "This draft has too much context to send." }
    val ids = mutableSetOf<String>()
    records.forEach { raw ->
        val record = obj(raw)
        require(number(record, "version") == 1L)
        val id = text(record, "contextId", 128, true)
        require(contextIdPattern.matches(id) && ids.add(id)) {
            "Context item identities must be unique."
        }
        val kind = text(record, "kind", 40, true)
        require(contextKindPattern.matches(kind))
        text(record, "label", 200)
        when (kind) {
            "image",
            "file" -> {
                require(contextIdPattern.matches(text(record, "attachmentId", 128, true)))
                text(record, "name", 255, true)
                text(record, "mimeType", 100, true)
                number(record, "sizeBytes")
            }
            "mention" -> text(record, "path", 2048, true)
            "skill" -> text(record, "name", 255, true)
            "environment" -> text(record, "environmentId", 16_000_000, true)
            "thread" -> {
                text(record, "environmentId", 16_000_000, true)
                text(record, "threadId", 16_000_000, true)
                text(record, "title", 200)
            }
            "terminal" -> {
                text(record, "terminalId", 255, true)
                text(record, "terminalLabel", 255, true)
                require(number(record, "lineEnd") >= number(record, "lineStart"))
                text(record, "text", 64_000)
            }
            "element" -> element(record)
            "preview-annotation" -> {
                text(record, "annotationId", 2048)
                text(record, "pageUrl", 2048)
                nullableText(record, "pageTitle")
                text(record, "comment", 8000)
                text(record, "targetSummary", 2048)
                strings(record, "styleChanges", 2048, 200)
                if (record.containsKey("elements"))
                    array(record, "elements", 50).forEach { element(obj(it)) }
                if (record.containsKey("elementIds")) strings(record, "elementIds", 2048, 50)
                for (key in listOf("regionCount", "strokeCount")) if (record.containsKey(key))
                    number(record, key)
                if (record.containsKey("screenshotContextId"))
                    require(
                        contextIdPattern.matches(text(record, "screenshotContextId", 128, true))
                    )
                if (record.containsKey("styleChangeDetails"))
                    array(record, "styleChangeDetails", 200).forEach { rawChange ->
                        obj(rawChange).let { change ->
                            text(change, "targetId", 2048)
                            nullableText(change, "selector")
                            text(change, "property", 2048)
                            text(change, "previousValue", 8000)
                            text(change, "value", 8000)
                        }
                    }
            }
            "review-comment" -> {
                text(record, "sectionId", 255, true)
                text(record, "sectionTitle", 2048)
                text(record, "filePath", 2048, true)
                require(number(record, "endIndex") >= number(record, "startIndex"))
                text(record, "rangeLabel", 2048)
                text(record, "text", 16_000)
                text(record, "diff", 32_000)
                if (record.containsKey("fenceLanguage")) text(record, "fenceLanguage", 64)
                if (record.containsKey("pullRequest"))
                    obj(record["pullRequest"]).let { pr ->
                        number(pr, "number", true)
                        for (key in listOf("title", "url", "headBranch", "baseBranch")) text(
                            pr,
                            key,
                            2048,
                        )
                        require(text(pr, "state", 6) in setOf("open", "closed", "merged"))
                        require((pr["isDraft"] as? JsonPrimitive)?.booleanOrNull != null)
                    }
            }
            else -> {
                require(kind !in knownContextKinds && record.containsKey("payload"))
                require(record["payload"].toString().length <= 64_000)
            }
        }
    }
}

fun composerContextItems(context: JsonObject?): List<ComposerContextItem> =
    context
        ?.array("records")
        .orEmpty()
        .mapNotNull { raw ->
            (raw as? JsonObject)?.let { row ->
                row.string("contextId").takeIf(String::isNotBlank)?.let {
                    ComposerContextItem(it, row.string("kind"), row.string("label").take(200))
                }
            }
        }
        .distinctBy { it.contextId }

fun referencedComposerContext(text: String, context: JsonObject?): JsonObject? {
    validateComposerContext(context)
    if (context == null) return null
    val ids = contextReferencePattern.findAll(text).map { it.groupValues[3] }.toMutableSet()
    val records = context.array("records").map { it.jsonObject }
    records
        .filter { it.string("contextId") in ids && it.string("kind") == "preview-annotation" }
        .forEach { it.nullableString("screenshotContextId")?.let(ids::add) }
    val kept = records.filter { it.string("contextId") in ids }
    return if (kept.isEmpty()) null
    else
        buildJsonObject {
            put("version", 1)
            put("records", JsonArray(kept))
        }
}

private fun contextLabel(label: String, fallback: String): String {
    val clean = label.replace(Regex("[\\[\\]\\\\\\r\\n]"), " ").replace(Regex("\\s+"), " ").trim()
    val end =
        if (clean.length > 200 && clean[199].isHighSurrogate() && clean[200].isLowSurrogate()) 199
        else minOf(clean.length, 200)
    return clean.take(end).ifEmpty { fallback }
}

fun composerContextReference(record: JsonObject): String {
    val kind = record.string("kind")
    val id = record.string("contextId")
    require(contextKindPattern.matches(kind) && contextIdPattern.matches(id))
    return "${if (kind == "image") "!" else ""}[${contextLabel(record.string("label"), kind)}](t3-context://v1/$kind/$id)"
}

private fun boundContextReferences(text: String, context: JsonObject?): List<MatchResult> {
    val keys = composerContextItems(context).mapTo(mutableSetOf()) { it.kind to it.contextId }
    return contextReferencePattern
        .findAll(text)
        .filter { (it.groupValues[2] to it.groupValues[3]) in keys }
        .toList()
}

fun composerEditableText(text: String, context: JsonObject? = null): String {
    val references = boundContextReferences(text, context)
    if (references.isEmpty()) return text
    val ids = references.mapTo(mutableSetOf()) { it.groupValues[2] to it.groupValues[3] }
    fun remove(source: String) =
        contextReferencePattern.replace(source) {
            if ((it.groupValues[2] to it.groupValues[3]) in ids) "" else it.value
        }
    val line = text.substringAfterLast('\n')
    val body =
        if ('\n' in text && contextReferencePattern.containsMatchIn(line) && remove(line).isBlank())
            text.substringBeforeLast('\n')
        else text
    return remove(body)
}

private fun contextText(prose: String, references: List<String>): String =
    if (references.isEmpty()) prose else "$prose\n${references.joinToString(" ")}"

fun replaceComposerDraftText(draft: ComposerDraft, edited: String): String {
    val references = boundContextReferences(draft.text, draft.context).map { it.value }.distinct()
    return contextText(edited, references)
}

fun appendComposerContext(draft: ComposerDraft, records: List<JsonObject>): ComposerDraft {
    val existing = draft.context?.array("records").orEmpty().map { it.jsonObject }
    val byId = (existing + records).associateBy { it.string("contextId") }
    val context = buildJsonObject {
        put("version", 1)
        put("records", JsonArray(byId.values.toList()))
    }
    validateComposerContext(context)
    val oldIds = contextReferencePattern.findAll(draft.text).map { it.groupValues[3] }.toSet()
    val added =
        records
            .distinctBy { it.string("contextId") }
            .filter { it.string("contextId") !in oldIds }
            .map(::composerContextReference)
    val text =
        contextText(
            composerEditableText(draft.text, draft.context),
            boundContextReferences(draft.text, draft.context).map { it.value }.distinct() + added,
        )
    validateMessageInput(text, draft.attachments.map(ChatAttachment::wire))
    return draft.copy(text = text, context = context)
}

fun removeComposerContext(draft: ComposerDraft, contextId: String): ComposerDraft {
    val text =
        contextText(
            composerEditableText(draft.text, draft.context),
            boundContextReferences(draft.text, draft.context)
                .filter { it.groupValues[3] != contextId }
                .map { it.value },
        )
    val kept =
        draft.context?.array("records").orEmpty().filter {
            (it as? JsonObject)?.string("contextId") != contextId
        }
    val context =
        if (kept.isEmpty()) null
        else
            buildJsonObject {
                put("version", 1)
                put("records", JsonArray(kept))
            }
    return draft.copy(text = text, context = referencedComposerContext(text, context))
}

private fun contextRecord(kind: String, label: String, fields: JsonObject): JsonObject =
    buildJsonObject {
        put("version", 1)
        put("kind", kind)
        put("contextId", UUID.randomUUID().toString())
        put("label", contextLabel(label, kind))
        fields.forEach { (key, value) -> put(key, value) }
    }

fun mentionComposerContext(path: String): JsonObject =
    contextRecord("mention", path.substringAfterLast('/'), buildJsonObject { put("path", path) })

fun skillComposerContext(name: String, label: String): JsonObject =
    contextRecord("skill", label, buildJsonObject { put("name", name) })

fun threadComposerContext(environmentId: String, thread: T3Thread): JsonObject =
    contextRecord(
        "thread",
        thread.title,
        buildJsonObject {
            put("environmentId", environmentId)
            put("threadId", thread.id)
            put("title", contextLabel(thread.title, "thread"))
        },
    )

fun terminalComposerContext(
    terminalId: String,
    label: String,
    lineStart: Long,
    lineEnd: Long,
    text: String,
): JsonObject =
    contextRecord(
        "terminal",
        label,
        buildJsonObject {
            put("terminalId", terminalId)
            put("terminalLabel", label)
            put("lineStart", lineStart)
            put("lineEnd", lineEnd)
            put("text", text)
        },
    )

fun previewAnnotationComposerContext(
    pageUrl: String,
    pageTitle: String?,
    comment: String,
): JsonObject =
    contextRecord(
        "preview-annotation",
        pageTitle ?: "Page note",
        buildJsonObject {
            put("annotationId", UUID.randomUUID().toString())
            put("pageUrl", pageUrl)
            put("pageTitle", pageTitle?.let(::JsonPrimitive) ?: JsonNull)
            put("comment", comment)
            put("targetSummary", "Page note")
            put("styleChanges", JsonArray(emptyList()))
        },
    )

internal fun parseComposerSkills(provider: JsonObject, cwd: String): List<ComposerSkill> {
    val snapshot =
        provider
            .array("workspaceSnapshots")
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("cwd") == cwd }
    return (snapshot ?: provider)
        .array("skills")
        .mapNotNull { raw ->
            (raw as? JsonObject)
                ?.takeIf { it.bool("enabled") && it["userInvocable"] != JsonPrimitive(false) }
                ?.let { row ->
                    row.string("name")
                        .takeIf { it.isNotBlank() && it.length <= 255 }
                        ?.let {
                            ComposerSkill(
                                it,
                                row.string("displayName").ifBlank { it },
                                row.string("shortDescription").ifBlank { row.string("description") },
                            )
                        }
                }
        }
        .distinctBy { it.name }
}

class ComposerContextTools(private val repository: T3Repository) {
    suspend fun files(cwd: String, query: String): ComposerFileMatches {
        require(query.length <= 256)
        val value =
            repository
                .callTool(
                    "projects.searchEntries",
                    buildJsonObject {
                        put("cwd", cwd)
                        put("query", query)
                        put("limit", 50)
                    },
                )
                .jsonObject
        return ComposerFileMatches(
            value
                .array("entries")
                .map { it.jsonObject.string("path") }
                .filter { it.isNotBlank() && it.length <= 2048 },
            value.bool("truncated"),
        )
    }

    suspend fun skills(instanceId: String, cwd: String): List<ComposerSkill> {
        val value =
            repository
                .callTool(
                    "server.refreshProviders",
                    buildJsonObject {
                        put("cwd", cwd)
                        put("instanceId", instanceId)
                        put("workspaceSnapshotsOnDemand", true)
                    },
                )
                .jsonObject
        return value
            .array("providers")
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("instanceId") == instanceId }
            ?.let { parseComposerSkills(it, cwd) }
            .orEmpty()
    }

    fun selection(
        connectionId: String,
        version: Long,
        ownerThreadId: String?,
        records: List<JsonObject>,
    ): ComposerContextSelection {
        if (repository.connectionId != connectionId || repository.connectionGeneration != version)
            throw CancellationException("The environment changed. Choose context again.")
        validateComposerContext(
            buildJsonObject {
                put("version", 1)
                put("records", JsonArray(records))
            }
        )
        return ComposerContextSelection(connectionId, version, ownerThreadId, records)
    }
}
