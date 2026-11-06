package com.example.teethree.data

import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Serializable
data class NativeMcpApp(
    val attachmentId: String,
    val server: String,
    val tool: String,
    val resourceUri: String,
    val sourceThreadId: String,
    val itemId: String,
    val csp: Map<String, List<String>> = emptyMap(),
    val permissions: Set<String> = emptySet(),
)

internal fun parseNativeMcpApp(
    value: JsonElement?,
    sourceThreadId: String,
    itemId: String,
): NativeMcpApp? {
    val raw = value as? JsonObject ?: return null
    fun text(key: String) = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun name(key: String) = text(key)?.takeIf { it.isNotBlank() && it.length <= 256 }
    val attachment = name("attachmentId") ?: return null
    val server = name("server") ?: return null
    val tool = name("tool") ?: return null
    val resource =
        text("resourceUri")?.takeIf { it.startsWith("ui://") && it.length <= 4096 } ?: return null
    val csp =
        listOf("connectDomains", "resourceDomains", "frameDomains", "baseUriDomains")
            .mapNotNull { key ->
                val domains =
                    raw.obj("csp")
                        .array(key)
                        .mapNotNull {
                            (it as? JsonPrimitive)
                                ?.takeIf { primitive -> primitive.isString }
                                ?.content
                        }
                        .filter(::validMcpDomain)
                        .take(32)
                if (domains.isEmpty()) null else key to domains
            }
            .toMap()
    val permissions =
        raw.obj("permissions")
            .filterValues { it is JsonObject }
            .keys
            .intersect(setOf("camera", "microphone", "geolocation", "clipboardWrite"))
    return NativeMcpApp(
        attachment,
        server,
        tool,
        resource,
        sourceThreadId,
        itemId,
        csp,
        permissions,
    )
}

internal fun validMcpDomain(value: String): Boolean =
    value.length <= 256 &&
        Regex("^(?:https?|wss?)://(?:\\*\\.)?[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*(?::[0-9]{1,5})?$")
            .matches(value)

internal fun mcpDomainAllows(domain: String, url: String): Boolean = runCatching {
    if (!validMcpDomain(domain)) return@runCatching false
    val wildcard = domain.contains("://*.")
    val declared = URI(domain.replace("://*.", "://"))
    val target = URI(url)
    fun port(uri: URI) =
        if (uri.port >= 0) uri.port else if (uri.scheme in setOf("https", "wss")) 443 else 80
    declared.scheme.equals(target.scheme, true) &&
        port(declared) == port(target) &&
        target.userInfo == null &&
        (if (wildcard) target.host?.endsWith(".${declared.host}", true) == true
        else target.host.equals(declared.host, true))
}
    .getOrDefault(false)

internal fun mcpContentPolicy(app: NativeMcpApp): String {
    val resources = app.csp["resourceDomains"].orEmpty().joinToString(" ")
    fun domains(key: String) = app.csp[key]?.joinToString(" ") ?: "'none'"
    return listOf(
            "default-src 'none'",
            "script-src 'unsafe-inline' $resources",
            "style-src 'unsafe-inline' $resources",
            "img-src data: blob: $resources",
            "font-src data: $resources",
            "media-src data: blob: $resources",
            "connect-src ${domains("connectDomains")}",
            "frame-src ${domains("frameDomains")}",
            "object-src 'none'",
            "base-uri ${domains("baseUriDomains")}",
            "form-action 'none'",
        )
        .joinToString("; ")
}

class NativeMcpAppTools(
    private val repository: T3Repository,
    private val app: NativeMcpApp,
    private val conversationThreadId: String,
) {
    private fun target(values: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("threadId", app.sourceThreadId)
        put("itemId", app.itemId)
        values.forEach { (key, value) ->
            require(key !in setOf("threadId", "itemId"))
            put(key, value)
        }
    }

    suspend fun toolInfo(name: String) =
        repository
            .callTool("mcpApps.toolInfo", target(buildJsonObject { put("name", name) }))
            .jsonObject

    suspend fun callTool(name: String, arguments: JsonObject) =
        repository
            .callTool(
                "mcpApps.callTool",
                target(
                    buildJsonObject {
                        put("name", name)
                        put("arguments", arguments)
                    }
                ),
            )
            .jsonObject

    suspend fun readResource(uri: String) =
        repository
            .callTool("mcpApps.readResource", target(buildJsonObject { put("uri", uri) }))
            .jsonObject

    suspend fun updateContext(params: JsonObject) =
        repository.callTool(
            "mcpApps.updateModelContext",
            target(
                buildJsonObject {
                    put("conversationThreadId", conversationThreadId)
                    params["content"]?.let {
                        require(it is JsonArray)
                        put("content", it)
                    }
                    params["structuredContent"]?.let {
                        require(it is JsonObject)
                        put("structuredContent", it)
                    }
                }
            ),
        )

    suspend fun sendMessage(text: String) = repository.sendMessage(conversationThreadId, text)

    suspend fun storedCall() =
        repository.callTool("orchestration.getTurnItem", target()).jsonObject.obj("item")

    suspend fun html(cache: File): String =
        withContext(Dispatchers.IO) {
            val file =
                AttachmentTransfers(repository)
                    .download(
                        ChatAttachment(
                            app.attachmentId,
                            "file",
                            app.tool.replace(Regex("[^a-zA-Z0-9_.-]"), "-").take(80) + ".html",
                            "text/html",
                            0,
                        ),
                        cache,
                    )
            try {
                require(file.length() <= 5 * 1024 * 1024) {
                    "This MCP app exceeds the 5 MiB HTML limit."
                }
                file.readText()
            } finally {
                file.delete()
            }
        }
}

internal fun mcpUserMessage(params: JsonObject): String {
    require(params.string("role") == "user") { "Only user text messages are supported." }
    val blocks = params["content"].let { if (it is JsonArray) it else listOf(it) }
    require(blocks.isNotEmpty()) { "Enter a message." }
    val text =
        blocks
            .map { block ->
                val content = block as? JsonObject
                require(content?.string("type") == "text" && content["text"] is JsonPrimitive) {
                    "Only text messages are supported."
                }
                content.string("text")
            }
            .joinToString("\n")
            .trim()
    require(text.isNotEmpty() && text.length <= 32_000) {
        "The message must contain between 1 and 32000 characters."
    }
    return text
}

internal fun normalizeMcpToolResult(result: JsonObject) = buildJsonObject {
    put("content", result["content"] as? JsonArray ?: JsonArray(emptyList()))
    (result["structuredContent"] as? JsonObject)?.let { put("structuredContent", it) }
    (result["_meta"] as? JsonObject)?.let { put("_meta", it) }
    if (result.bool("isError")) put("isError", true)
}

internal fun mcpRequestId(value: JsonElement?): JsonPrimitive? =
    (value as? JsonPrimitive)?.takeIf {
        it.isString || it.doubleOrNull?.isFinite() == true
    }
