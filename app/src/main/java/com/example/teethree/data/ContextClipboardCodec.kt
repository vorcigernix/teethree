package com.example.teethree.data

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

const val CONTEXT_CLIPBOARD_MIME = "web application/x-t3-context-fragment+json"
private const val MAX_FRAGMENT_CHARS = 16_000_000
private val contextLink =
    Regex(
        "!?\\[([^\\]\\n]{0,512})]\\(t3-context://v1/([a-z][a-z0-9-]{0,39})/([a-zA-Z0-9_-]{1,128})\\)"
    )
private val fragmentAttribute = Regex("data-t3-context-fragment=(?:\"([^\"]+)\"|'([^']+)')")
private val hosted = "https://app\\.t3\\.codes/threads/[A-Za-z0-9_%-]+/[A-Za-z0-9_%-]+"
private val threadLinks =
    Regex(
        """(?<fence>(`{3,}|~{3,})[\s\S]*?(?:\2|$))|(?<span>(`+)[^\n]*?\4)|\[(?<label>[^\]\n]*)\]\((?<href>t3-thread://v[12]/[^\s)]+|$hosted)\)|<(?<autolink>t3-thread://v[12]/[^\s<>]+|$hosted)>|(?<bare>$hosted)(?![\w%/?#-])"""
    )

data class ContextClipboardPayload(
    val text: String,
    val fragment: String? = null,
    val html: String? = null,
)

data class ClipboardSource(
    val environmentId: String,
    val threadId: String? = null,
    val messageId: String? = null,
)

data class DecodedContextClipboard(
    val source: ClipboardSource,
    val records: List<JsonObject>,
    val warnings: List<String> = emptyList(),
)

data class ReidentifiedContextClipboard(
    val text: String,
    val source: ClipboardSource,
    val context: JsonObject,
    val warnings: List<String>,
)

internal fun strictClipboardPercentDecode(encoded: String): String {
    val bytes = ByteArrayOutputStream()
    var cursor = 0
    while (cursor < encoded.length) {
        if (encoded[cursor] == '%') {
            require(cursor + 2 < encoded.length)
            val high =
                encoded[cursor + 1].digitToIntOrNull(16) ?: error("Invalid clipboard escape.")
            val low = encoded[cursor + 2].digitToIntOrNull(16) ?: error("Invalid clipboard escape.")
            bytes.write(high * 16 + low)
            cursor += 3
        } else {
            val point = encoded.codePointAt(cursor)
            require(point !in 0xD800..0xDFFF)
            val character = String(Character.toChars(point))
            bytes.write(character.toByteArray(Charsets.UTF_8))
            cursor += character.length
        }
    }
    return Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes.toByteArray()))
        .toString()
}

private fun decodeFragment(raw: String?): DecodedContextClipboard? = runCatching {
    if (raw.isNullOrEmpty() || raw.length > MAX_FRAGMENT_CHARS) return null
    val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
    if (root["version"] != JsonPrimitive(1)) return null
    val source = root["source"] as? JsonObject ?: return null
    fun identity(key: String, optional: Boolean = false): String? {
        if (optional && !source.containsKey(key)) return null
        val value = source[key] as? JsonPrimitive
        require(
            value?.isString == true &&
                value.content.isNotBlank() &&
                value.content == value.content.trim()
        )
        return value.content
    }
    val origin =
        ClipboardSource(
            requireNotNull(identity("environmentId")),
            identity("threadId", true),
            identity("messageId", true),
        )
    val rawRecords = root["records"] as? JsonArray ?: return null
    require(rawRecords.size <= 200)
    val warnings = mutableListOf<String>()
    val records = rawRecords.mapNotNull { item ->
        val record = item as? JsonObject
        if (
            record != null &&
                runCatching { validateComposerContext(contextOf(listOf(record))) }.isSuccess
        )
            record
        else {
            warnings.add("An unsupported or malformed context item was kept as literal text.")
            null
        }
    }
    require(records.map { it.string("contextId") }.toSet().size == records.size)
    DecodedContextClipboard(origin, records, warnings)
}
    .getOrNull()

fun decodeContextClipboard(payload: ContextClipboardPayload): DecodedContextClipboard? {
    decodeFragment(payload.fragment)?.let {
        return it
    }
    val html = payload.html ?: return null
    if (html.length > MAX_FRAGMENT_CHARS * 9 + 4096) return null
    val attribute = fragmentAttribute.find(html) ?: return null
    val encoded = attribute.groups[1]?.value ?: attribute.groups[2]?.value ?: return null
    return runCatching { strictClipboardPercentDecode(encoded) }.getOrNull()?.let(::decodeFragment)
}

internal fun contextOf(records: List<JsonObject>) = buildJsonObject {
    put("version", 1)
    put("records", JsonArray(records))
}

fun encodeContextClipboard(
    text: String,
    source: ClipboardSource,
    context: JsonObject?,
): ContextClipboardPayload {
    val records = referencedComposerContext(text, context)?.array("records").orEmpty()
    val raw = buildJsonObject {
        put("version", 1)
        put(
            "source",
            buildJsonObject {
                put("environmentId", source.environmentId)
                source.threadId?.let { put("threadId", it) }
                source.messageId?.let { put("messageId", it) }
            },
        )
        put("records", JsonArray(records))
    }
        .toString()
    require(raw.length <= MAX_FRAGMENT_CHARS) { "This context selection is too large to copy." }
    require(decodeFragment(raw) != null) {
        "This context selection has an invalid source identity."
    }
    val encoded = URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")
    val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return ContextClipboardPayload(
        text,
        raw,
        "<pre data-t3-context-fragment=\"$encoded\">$escaped</pre>",
    )
}

fun clipboardCopyOfDraft(draft: ComposerDraft, source: ClipboardSource): ContextClipboardPayload {
    val bound =
        draft.context
            ?.array("records")
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.nullableString("attachmentId") }
            .toSet()
    val records =
        draft.attachments
            .filter { it.id !in bound }
            .map { attachment ->
                buildJsonObject {
                    put("version", 1)
                    put("kind", if (attachment.type == "image") "image" else "file")
                    put("contextId", UUID.randomUUID().toString())
                    put("label", attachment.name.take(200))
                    put("attachmentId", attachment.id)
                    put("name", attachment.name)
                    put("mimeType", attachment.mimeType)
                    put("sizeBytes", attachment.sizeBytes)
                }
            }
    val complete = if (records.isEmpty()) draft else appendComposerContext(draft, records)
    return encodeContextClipboard(complete.text, source, complete.context)
}

private fun threadIdentity(href: String): Pair<String, String>? = runCatching {
    val pair =
        when {
            href.startsWith("t3-thread://v2/") ->
                href.removePrefix("t3-thread://v2/").let { path ->
                    path.substringBefore('/') to path.substringAfter('/', "")
                }
            href.startsWith("https://app.t3.codes/threads/") ->
                href
                    .removePrefix("https://app.t3.codes/threads/")
                    .split('/')
                    .takeIf { it.size == 2 }
                    ?.let {
                        strictClipboardPercentDecode(it[0]) to strictClipboardPercentDecode(it[1])
                    } ?: return null
            else -> return null
        }
    pair.takeIf {
        listOf(it.first, it.second).all { id ->
            id.isNotBlank() && id == id.trim() && id.none(Char::isISOControl)
        }
    }
}
    .getOrNull()

fun reidentifyContextClipboard(
    payload: ContextClipboardPayload,
    destinationEnvironmentId: String,
    linkedEnvironmentIds: Set<String> = emptySet(),
    createId: () -> String = { UUID.randomUUID().toString() },
): ReidentifiedContextClipboard? {
    require(payload.text.length <= MAX_FRAGMENT_CHARS)
    val copied = decodeContextClipboard(payload)
    val records = copied?.records.orEmpty().associateByTo(linkedMapOf()) { it.string("contextId") }
    var source = copied?.source
    val warnings = copied?.warnings.orEmpty().toMutableList()
    val text =
        threadLinks.replace(payload.text) { match ->
            val href =
                match.groups["href"]?.value
                    ?: match.groups["autolink"]?.value
                    ?: match.groups["bare"]?.value
                    ?: return@replace match.value
            val identity = threadIdentity(href) ?: return@replace match.value
            if (
                identity.first != destinationEnvironmentId &&
                    identity.first !in linkedEnvironmentIds
            )
                return@replace match.value
            val old =
                records.values.firstOrNull {
                    it.string("kind") == "thread" &&
                        it.string("environmentId") == identity.first &&
                        it.string("threadId") == identity.second
                }
            val record =
                old
                    ?: buildJsonObject {
                        val label =
                            match.groups["label"]?.value?.takeIf(String::isNotBlank)?.take(200)
                                ?: "Thread"
                        put("version", 1)
                        put("kind", "thread")
                        put("contextId", createId())
                        put("label", label)
                        put("title", label)
                        put("environmentId", identity.first)
                        put("threadId", identity.second)
                    }
            records[record.string("contextId")] = record
            if (source == null) source = ClipboardSource(identity.first)
            composerContextReference(record)
        }
    val origin = source ?: return null
    val selected =
        referencedComposerContext(text, contextOf(records.values.toList()))
            ?.array("records")
            .orEmpty()
            .map { it.jsonObject }
    val ids = selected.associate { it.string("contextId") to createId() }
    require(ids.values.toSet().size == ids.size)
    val rewritten =
        contextLink.replace(text) { match ->
            ids[match.groupValues[3]]?.let { fresh ->
                composerContextReference(
                    buildJsonObject {
                        put("kind", match.groupValues[2])
                        put("contextId", fresh)
                        put("label", match.groupValues[1])
                    }
                )
            } ?: match.value
        }
    val imported = selected.map { record ->
        JsonObject(
            record +
                ("contextId" to JsonPrimitive(ids.getValue(record.string("contextId")))) +
                record
                    .nullableString("screenshotContextId")
                    ?.let { screenshot ->
                        mapOf("screenshotContextId" to JsonPrimitive(ids[screenshot] ?: screenshot))
                    }
                    .orEmpty()
        )
    }
    val retained = imported.filter { record ->
        val foreign =
            record.string("kind") == "thread" &&
                record.string("environmentId") != destinationEnvironmentId &&
                record.string("environmentId") !in linkedEnvironmentIds
        if (foreign)
            warnings.add(
                "${record.string("label")} is on a machine this environment cannot read. Its reference remains literal text."
            )
        !foreign
    }
    return ReidentifiedContextClipboard(rewritten, origin, contextOf(retained), warnings.distinct())
}
