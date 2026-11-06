package com.example.teethree.data

import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class BrowserPreviewTab(
    val id: String,
    val threadId: String,
    val title: String,
    val url: String,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val runtime: String,
    val failure: String?,
    val zoomFactor: Double = 1.0,
)

data class BrowserPreviewState(
    val epoch: String = "",
    val revision: Long = -1,
    val tabs: List<BrowserPreviewTab> = emptyList(),
)

data class PreviewDevice(
    val hostId: String,
    val id: String,
    val platform: String,
    val name: String,
    val version: String,
    val booted: Boolean,
    val physical: Boolean,
) {
    val key
        get() = "$hostId\u0000$id"
}

data class PreviewDeviceSession(
    val threadId: String,
    val hostId: String,
    val deviceId: String,
    val platform: String,
)

data class PreviewDeviceHost(
    val id: String,
    val label: String,
    val kind: String,
    val status: String,
    val detail: String?,
    val tools: JsonObject? = null,
    val toolInspectionError: String? = null,
)

data class PreviewDeviceState(
    val devices: List<PreviewDevice> = emptyList(),
    val sessions: List<PreviewDeviceSession> = emptyList(),
    val hosts: List<PreviewDeviceHost> = emptyList(),
    val detail: String? = null,
    val revision: Long = -1,
    val supportsHostRetry: Boolean = false,
    val supportsToolInspection: Boolean = false,
)

class PreviewTools(private val repository: T3Repository) {
    suspend fun browsers(threadId: String) =
        parseBrowserPreviewState(
            repository
                .callTool("preview.list", buildJsonObject { put("threadId", threadId) })
                .jsonObject
        )

    fun watchBrowsers(onError: (Throwable) -> Unit, onEvent: (JsonObject) -> Unit) =
        repository.streamTool("subscribePreviewEvents", JsonObject(emptyMap()), onError, onEvent)

    suspend fun openBrowser(threadId: String, url: String) =
        repository.callTool(
            "preview.open",
            buildJsonObject {
                put("threadId", threadId)
                put("url", normalizeBrowserPreviewUrl(url))
                put("runtime", "server")
            },
        )

    suspend fun closeBrowser(tab: BrowserPreviewTab) =
        repository.callTool(
            "preview.close",
            buildJsonObject {
                put("threadId", tab.threadId)
                put("tabId", tab.id)
            },
        )

    suspend fun adjustBrowser(tab: BrowserPreviewTab, appearance: String) =
        repository.callTool(
            "preview.adjust",
            buildJsonObject {
                put("threadId", tab.threadId)
                put("tabId", tab.id)
                put("colorScheme", appearance)
            },
        )

    suspend fun adjustBrowser(tab: BrowserPreviewTab, values: JsonObject) =
        repository.callTool(
            "preview.adjust",
            buildJsonObject {
                put("threadId", tab.threadId)
                put("tabId", tab.id)
                values.forEach { (key, value) ->
                    require(key in setOf("colorScheme", "zoomFactor", "hardReload", "clear"))
                    put(key, value)
                }
            },
        )

    suspend fun resizeBrowser(tab: BrowserPreviewTab, viewport: JsonObject) =
        repository.callTool(
            "preview.resize",
            buildJsonObject {
                put("threadId", tab.threadId)
                put("tabId", tab.id)
                put("viewport", viewport)
            },
        )

    suspend fun devices(inspectOnly: Boolean = false) =
        parsePreviewDeviceState(
            repository
                .callTool(
                    "device.list",
                    buildJsonObject {
                        if (inspectOnly) put("inspectOnly", true)
                    },
                )
                .jsonObject
        )

    suspend fun retryDeviceHost(hostId: String): PreviewDeviceState =
        parsePreviewDeviceState(
            repository.callTool("device.list", deviceHostRetryInput(hostId)).jsonObject
        )

    fun watchDevices(onError: (Throwable) -> Unit, onValue: (PreviewDeviceState) -> Unit) =
        repository.streamTool("subscribeDeviceState", JsonObject(emptyMap()), onError) {
            onValue(parsePreviewDeviceState(it))
        }

    suspend fun openDevice(threadId: String, device: PreviewDevice) =
        repository.callTool(
            "device.open",
            buildJsonObject {
                put("threadId", threadId)
                put("hostId", device.hostId)
                put("deviceId", device.id)
                put("platform", device.platform)
            },
        )

    suspend fun closeDevice(threadId: String, device: PreviewDevice) =
        repository.callTool(
            "device.close",
            buildJsonObject {
                put("threadId", threadId)
                put("hostId", device.hostId)
                put("deviceId", device.id)
                put("shutdown", false)
            },
        )

    suspend fun shutdownDevice(device: PreviewDevice) =
        repository.callTool(
            "device.shutdown",
            buildJsonObject {
                put("hostId", device.hostId)
                put("deviceId", device.id)
                put("platform", device.platform)
            },
        )

    suspend fun deviceDetail(device: PreviewDevice) =
        repository
            .callTool(
                "device.detail",
                buildJsonObject {
                    put("hostId", device.hostId)
                    put("deviceId", device.id)
                },
            )
            .jsonObject

    suspend fun deviceAction(
        device: PreviewDevice,
        type: String,
        values: JsonObject = JsonObject(emptyMap()),
    ) =
        repository
            .callTool(
                "device.action",
                buildJsonObject {
                    put("hostId", device.hostId)
                    put("deviceId", device.id)
                    put("type", type)
                    values.forEach { (key, value) ->
                        require(key !in setOf("hostId", "deviceId", "type"))
                        put(key, value)
                    }
                },
            )
            .jsonObject

    suspend fun mediaAccess(device: PreviewDevice? = null): JsonObject {
        val access =
            repository.mediaAccess(if (device == null) "/api/preview-stream" else "/api/device-hub")
        if (device == null) return access
        return buildJsonObject {
            access.forEach { (key, value) -> put(key, value) }
            put(
                "query",
                buildJsonObject {
                    access.obj("query").forEach { (key, value) -> put(key, value) }
                    put("hostId", device.hostId)
                },
            )
        }
    }
}

internal fun normalizeBrowserPreviewUrl(raw: String): String {
    val text = raw.trim()
    require(text.isNotBlank() && text.length <= 2048) { "Enter a URL of at most 2048 characters." }
    val qualified =
        if (text.contains("://")) text
        else if (text.startsWith("localhost") || text.startsWith("127.0.0.1")) "http://$text"
        else "https://$text"
    val uri = runCatching { URI(qualified) }.getOrNull()
    require(
        uri != null &&
            uri.scheme.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    ) {
        "Enter an HTTP or HTTPS URL without embedded credentials."
    }
    return qualified
}

internal fun parseBrowserPreviewTab(value: JsonObject) =
    BrowserPreviewTab(
        value.string("tabId"),
        value.string("threadId"),
        value.obj("navStatus").string("title", "Browser"),
        value.obj("navStatus").string("url"),
        value.bool("canGoBack"),
        value.bool("canGoForward"),
        value.string("runtime", "desktop"),
        value.obj("navStatus").takeIf { it.string("_tag") == "LoadFailed" }?.string("description"),
        (value["zoomFactor"] as? JsonPrimitive)?.doubleOrNull ?: 1.0,
    )

internal fun parseBrowserPreviewState(value: JsonObject) =
    BrowserPreviewState(
        value.string("serverEpoch"),
        value.long("revision", -1),
        value.array("sessions").map { parseBrowserPreviewTab(it.jsonObject) },
    )

internal fun reduceBrowserPreviewEvent(
    state: BrowserPreviewState,
    event: JsonObject,
    threadId: String,
): BrowserPreviewState {
    if (event.string("threadId") != threadId) return state
    val epoch = event.string("serverEpoch")
    val revision = event.long("revision", -1)
    if (epoch == state.epoch && revision <= state.revision) return state
    val tabs = if (epoch == state.epoch) state.tabs else emptyList()
    val id = event.string("tabId")
    val next =
        when (event.string("type")) {
            "closed" -> tabs.filterNot { it.id == id }
            "opened",
            "navigated",
            "resized" ->
                tabs.filterNot { it.id == id } + parseBrowserPreviewTab(event.obj("snapshot"))
            "failed" ->
                tabs.map { if (it.id == id) it.copy(failure = event.string("description")) else it }
            else -> return state
        }
    return BrowserPreviewState(epoch, revision, next)
}

internal fun parsePreviewDeviceState(value: JsonObject): PreviewDeviceState {
    val hosts = value.array("hosts") + value.array("easHosts") + value.array("simlockHosts")
    return PreviewDeviceState(
        value.array("devices").map { entry ->
            val device = entry.jsonObject
            PreviewDevice(
                device.string("hostId", "local"),
                device.string("id"),
                device.string("platform"),
                device.string("name"),
                device.string("version"),
                device.bool("booted"),
                device.bool("physical"),
            )
        },
        value.array("sessions").map { entry ->
            val session = entry.jsonObject
            PreviewDeviceSession(
                session.string("threadId"),
                session.string("hostId", "local"),
                session.string("deviceId"),
                session.string("platform"),
            )
        },
        hosts
            .map { entry ->
                val host = entry.jsonObject
                val status = value.obj("hostStatuses").obj(host.string("id"))
                PreviewDeviceHost(
                    host.string("id"),
                    host.string("label"),
                    host.string("kind", "local"),
                    status.string("status"),
                    status.nullableString("detail"),
                    host["tools"] as? JsonObject,
                    host.nullableString("toolInspectionError"),
                )
            }
            .distinctBy { it.id },
        value.nullableString("hostStatusDetail"),
        value.long("revision", -1),
        value.bool("supportsHostRetry"),
        value.bool("supportsToolInspection"),
    )
}

internal fun reduceDeviceSnapshot(
    current: PreviewDeviceState,
    next: PreviewDeviceState,
    firstInSubscription: Boolean,
): PreviewDeviceState =
    if (firstInSubscription || next.revision >= current.revision) next else current

internal fun deviceHostRetryInput(hostId: String): JsonObject {
    val id = hostId.trim()
    require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl)) {
        "Choose a valid device host."
    }
    return buildJsonObject { put("retryHostId", id) }
}

internal fun deviceToolsNeedUpdate(tools: JsonObject?): Boolean =
    tools != null &&
        listOf("hub", "agent").any { key ->
            val version = tools.obj(key)
            val installed =
                version.array("installedVersions").mapNotNull {
                    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                }
            installed.isNotEmpty() && version.string("requiredVersion") !in installed
        }
