package com.example.teethree.ui

import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.NativeMcpApp
import com.example.teethree.data.NativeMcpAppTools
import com.example.teethree.data.T3Repository
import com.example.teethree.data.array
import com.example.teethree.data.bool
import com.example.teethree.data.long
import com.example.teethree.data.mcpContentPolicy
import com.example.teethree.data.mcpDomainAllows
import com.example.teethree.data.mcpRequestId
import com.example.teethree.data.mcpUserMessage
import com.example.teethree.data.normalizeMcpToolResult
import com.example.teethree.data.obj
import com.example.teethree.data.string
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private data class AppConfirmation(
    val title: String,
    val body: String,
    val answer: CompletableDeferred<Boolean>,
)

private data class AppDownload(val bytes: ByteArray, val answer: CompletableDeferred<Boolean>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpAppScreen(
    repository: T3Repository,
    conversationThreadId: String,
    app: NativeMcpApp,
    onBack: () -> Unit,
) {
    McpAppSurface(repository, conversationThreadId, app, "fullscreen", onBack = onBack)
}

@Composable
fun InlineMcpApp(
    repository: T3Repository,
    conversationThreadId: String,
    app: NativeMcpApp,
    onFullscreen: (() -> Unit)? = null,
) {
    McpAppSurface(repository, conversationThreadId, app, "inline", onFullscreen = onFullscreen)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpAppSurface(
    repository: T3Repository,
    conversationThreadId: String,
    app: NativeMcpApp,
    displayMode: String,
    onBack: () -> Unit = {},
    onFullscreen: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by repository.state.collectAsStateWithLifecycle()
    val tools =
        remember(repository, app, conversationThreadId) {
            NativeMcpAppTools(repository, app, conversationThreadId)
        }
    val colors = MaterialTheme.colorScheme
    val theme = if (colors.surface.luminance() < .5f) "dark" else "light"
    var generation by remember(app) { mutableStateOf(0) }
    var rendererRestarts by remember(app) { mutableStateOf(0) }
    var closed by remember(app) { mutableStateOf(false) }
    var inlineHeight by remember(app) { mutableStateOf(320) }
    val name = remember(app, generation) { UUID.randomUUID().toString() }
    val requests =
        remember(name) {
            CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        }
    var document by remember(app) { mutableStateOf<String?>(null) }
    var error by remember(app) { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<PreviewContentView?>(null) }
    var confirmation by remember { mutableStateOf<AppConfirmation?>(null) }
    var download by remember { mutableStateOf<AppDownload?>(null) }
    var initialized by remember(name) { mutableStateOf(false) }
    var call by remember(app) { mutableStateOf<JsonObject?>(null) }
    var toolDefinition by remember(app) { mutableStateOf<JsonElement?>(null) }
    val pending = remember(name) { mutableSetOf<JsonElement>() }
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val current = download
            download = null
            if (current != null)
                scope.launch {
                    try {
                        if (uri == null) current.answer.complete(false)
                        else {
                            withContext(Dispatchers.IO) {
                                checkNotNull(context.contentResolver.openOutputStream(uri)).use {
                                    it.write(current.bytes)
                                }
                            }
                            current.answer.complete(true)
                        }
                    } catch (failure: Exception) {
                        current.answer.completeExceptionally(failure)
                    }
                }
        }
    suspend fun ask(title: String, body: String): Boolean {
        check(confirmation == null) { "Another request is waiting for approval." }
        val answer = CompletableDeferred<Boolean>()
        confirmation = AppConfirmation(title, body, answer)
        return try {
            answer.await()
        } finally {
            if (confirmation?.answer === answer) confirmation = null
        }
    }
    fun post(value: JsonObject) {
        view?.postMessage(value)
    }
    fun notify(method: String, params: JsonObject) =
        post(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }
        )
    fun hostContext() = buildJsonObject {
        put("theme", theme)
        put("displayMode", displayMode)
        put(
            "availableDisplayModes",
            buildJsonArray {
                add(JsonPrimitive("inline"))
                add(JsonPrimitive("fullscreen"))
            },
        )
        put("platform", "mobile")
        put("locale", Locale.getDefault().toLanguageTag())
        put("timeZone", TimeZone.getDefault().id)
        put(
            "deviceCapabilities",
            buildJsonObject {
                put("touch", true)
                put("hover", false)
            },
        )
        put(
            "containerDimensions",
            buildJsonObject {
                val density = context.resources.displayMetrics.density
                put("width", (view?.width ?: 0) / density)
                put("height", (view?.height ?: 0) / density)
            },
        )
        put(
            "styles",
            buildJsonObject {
                put(
                    "variables",
                    buildJsonObject {
                        put(
                            "--color-background-primary",
                            "#%06x".format(colors.surface.toArgb() and 0xffffff),
                        )
                        put(
                            "--color-text-primary",
                            "#%06x".format(colors.onSurface.toArgb() and 0xffffff),
                        )
                        put(
                            "--color-text-secondary",
                            "#%06x".format(colors.onSurfaceVariant.toArgb() and 0xffffff),
                        )
                    },
                )
            },
        )
        toolDefinition?.let { put("toolInfo", buildJsonObject { put("tool", it) }) }
    }
    LaunchedEffect(app, tools, name, closed) {
        if (closed) return@LaunchedEffect
        error = null
        try {
            val html = tools.html(File(context.cacheDir, "attachments"))
            document = mcpOuterDocument(html, app, name)
            call = tools.storedCall()
            toolDefinition = tools.toolInfo(app.tool)["tool"]
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "Could not load the MCP app."
        }
    }
    val itemStatus =
        state.timeline
            .find {
                it.mcpApp?.itemId == app.itemId && it.mcpApp.sourceThreadId == app.sourceThreadId
            }
            ?.status
    LaunchedEffect(itemStatus) {
        if (itemStatus != null)
            try {
                call = tools.storedCall()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message
            }
    }
    LaunchedEffect(initialized, call) {
        if (initialized && call != null) {
            notify(
                "ui/notifications/tool-input",
                buildJsonObject {
                    put("arguments", call?.get("input") as? JsonObject ?: JsonObject(emptyMap()))
                },
            )
            (call?.obj("output")?.get("result") as? JsonObject)?.let {
                notify("ui/notifications/tool-result", normalizeMcpToolResult(it))
            }
        }
    }
    LaunchedEffect(initialized, theme, toolDefinition) {
        if (initialized) notify("ui/notifications/host-context-changed", hostContext())
    }
    DisposableEffect(app, name) {
        onDispose {
            requests.cancel()
            confirmation?.answer?.cancel()
            download?.answer?.cancel()
        }
    }
    fun closeApp() {
        if (displayMode == "fullscreen") onBack()
        else {
            closed = true
            document = null
            requests.cancel()
        }
    }
    suspend fun handle(method: String, params: JsonObject, userActive: Boolean): JsonElement =
        when (method) {
            "ui/initialize" ->
                buildJsonObject {
                    put("protocolVersion", "2026-01-26")
                    put(
                        "hostInfo",
                        buildJsonObject {
                            put("name", "t3-code-android")
                            put("version", "1")
                        },
                    )
                    put(
                        "hostCapabilities",
                        buildJsonObject {
                            listOf(
                                    "openLinks",
                                    "serverTools",
                                    "serverResources",
                                    "logging",
                                    "downloadFile",
                                )
                                .forEach { put(it, JsonObject(emptyMap())) }
                            put("message", buildJsonObject { put("text", JsonObject(emptyMap())) })
                            put(
                                "updateModelContext",
                                buildJsonObject {
                                    put("text", JsonObject(emptyMap()))
                                    put("structuredContent", JsonObject(emptyMap()))
                                },
                            )
                            put(
                                "sandbox",
                                buildJsonObject {
                                    put(
                                        "csp",
                                        buildJsonObject {
                                            app.csp.forEach { (key, values) ->
                                                put(key, JsonArray(values.map(::JsonPrimitive)))
                                            }
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put("hostContext", hostContext())
                }
            "ping" -> JsonObject(emptyMap())
            "tools/call" -> {
                val tool =
                    params.string("name").also { require(it.isNotBlank() && it.length <= 256) }
                require(params["arguments"] == null || params["arguments"] is JsonObject) {
                    "Tool arguments must be an object."
                }
                val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                require(arguments.toString().length <= 32_000) {
                    "Tool arguments exceed the supported 32000-character limit."
                }
                val info = tools.toolInfo(tool)
                check(info.bool("callable")) { "This app cannot call that tool." }
                check(
                    info.bool("readOnly") ||
                        ask(
                            "Allow ${app.server} to run ${info.string("title", tool)}?",
                            arguments.toString(),
                        )
                ) {
                    "Declined by the user."
                }
                normalizeMcpToolResult(tools.callTool(tool, arguments))
            }
            "resources/read" ->
                tools.readResource(
                    params.string("uri").also { require(it.isNotBlank() && it.length <= 4096) }
                )
            "ui/open-link" -> {
                val url = params.string("url")
                val uri = Uri.parse(url)
                require(
                    uri.scheme in setOf("http", "https") &&
                        !uri.host.isNullOrBlank() &&
                        uri.userInfo == null
                ) {
                    "Only HTTP or HTTPS links are supported."
                }
                check(ask("Open a link from ${app.server}?", url)) { "Declined by the user." }
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                JsonObject(emptyMap())
            }
            "ui/message" -> {
                val text = mcpUserMessage(params)
                check(ask("Send this message from ${app.server}?", text)) {
                    "Declined by the user."
                }
                tools.sendMessage(text)
                JsonObject(emptyMap())
            }
            "ui/update-model-context" -> {
                tools.updateContext(params)
                JsonObject(emptyMap())
            }
            "ui/request-display-mode" -> {
                val requested = params.string("mode")
                val next =
                    when {
                        requested == "fullscreen" &&
                            displayMode == "inline" &&
                            userActive &&
                            onFullscreen != null -> {
                            onFullscreen()
                            "fullscreen"
                        }
                        requested == "inline" && displayMode == "fullscreen" -> {
                            onBack()
                            "inline"
                        }
                        else -> displayMode
                    }
                buildJsonObject { put("mode", next) }
            }
            "ui/download-file" -> {
                val files = params.array("contents")
                require(files.isNotEmpty() && files.size <= 8) {
                    "Choose between one and eight files."
                }
                check(
                    ask(
                        "Save files from ${app.server}?",
                        files.joinToString("\n") {
                            it.jsonObject.string(
                                "name",
                                it.jsonObject.obj("resource").string("uri", "File"),
                            )
                        },
                    )
                ) {
                    "Declined by the user."
                }
                files.forEach { value ->
                    val entry = value.jsonObject
                    val resource =
                        when (entry.string("type")) {
                            "resource" -> entry.obj("resource")
                            "resource_link" ->
                                tools
                                    .readResource(entry.string("uri"))
                                    .array("contents")
                                    .firstOrNull()
                                    ?.jsonObject ?: kotlin.error("The resource has no contents.")
                            else ->
                                kotlin.error(
                                    "Only embedded resources and resource links can be saved."
                                )
                        }
                    val bytes =
                        when {
                            "text" in resource -> resource.string("text").toByteArray()
                            "blob" in resource ->
                                Base64.decode(resource.string("blob"), Base64.DEFAULT)
                            else -> kotlin.error("The resource has no contents.")
                        }
                    require(bytes.size <= 25 * 1024 * 1024) { "The file exceeds the 25 MiB limit." }
                    check(download == null) { "Another download is in progress." }
                    val answer = CompletableDeferred<Boolean>()
                    download = AppDownload(bytes, answer)
                    save.launch(
                        entry
                            .string("name", resource.string("uri", "download"))
                            .substringAfterLast('/')
                            .ifBlank { "download" }
                            .take(200)
                    )
                    check(answer.await()) { "Save cancelled." }
                }
                JsonObject(emptyMap())
            }
            else -> kotlin.error("Unsupported app method: $method")
        }
    val body: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            error?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            document?.let { html ->
                PreviewContent(
                    html,
                    name,
                    Modifier.weight(1f),
                    onView = { view = it },
                    allowRequest = { uri ->
                        app.csp.values.flatten().any { mcpDomainAllows(it, uri.toString()) }
                    },
                    onMessage = { raw ->
                        val envelope =
                            runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                                ?: return@PreviewContent
                        if (envelope.string("type") in setOf("renderProcessGone", "navigated")) {
                            document = null
                            confirmation?.answer?.complete(false)
                            confirmation = null
                            download?.answer?.complete(false)
                            download = null
                            if (
                                envelope.string("type") == "renderProcessGone" &&
                                    rendererRestarts < 1
                            ) {
                                rendererRestarts++
                                generation++
                            } else
                                error = "The app stopped or left its page. Reload it to continue."
                            return@PreviewContent
                        }
                        val message = envelope["message"] as? JsonObject ?: envelope
                        if (message.string("jsonrpc") != "2.0") return@PreviewContent
                        val method = message.string("method")
                        if (method.isBlank()) return@PreviewContent
                        val params = message.obj("params")
                        val id = message["id"]
                        if (id == null) {
                            when (method) {
                                "ui/notifications/initialized" -> initialized = true
                                "ui/notifications/request-teardown" -> closeApp()
                                "ui/notifications/size-changed" ->
                                    if (displayMode == "inline")
                                        inlineHeight =
                                            params.long("height", 320).coerceIn(80, 560).toInt()
                            }
                        } else if (mcpRequestId(id) != null) {
                            fun fail(text: String) =
                                post(
                                    buildJsonObject {
                                        put("jsonrpc", "2.0")
                                        put("id", id)
                                        put(
                                            "error",
                                            buildJsonObject {
                                                put("code", -32000)
                                                put("message", text)
                                            },
                                        )
                                    }
                                )
                            if (pending.size >= 16 || !pending.add(id))
                                fail("Too many pending requests or duplicate request id.")
                            else
                                requests.launch {
                                    try {
                                        val result = handle(method, params, envelope.bool("active"))
                                        post(
                                            buildJsonObject {
                                                put("jsonrpc", "2.0")
                                                put("id", id)
                                                put("result", result)
                                            }
                                        )
                                    } catch (failure: Exception) {
                                        if (failure is CancellationException) throw failure
                                        fail(failure.message ?: "App request failed.")
                                    } finally {
                                        pending.remove(id)
                                    }
                                }
                        }
                    },
                )
            }
                ?: if (error != null)
                    TextButton(
                        onClick = {
                            rendererRestarts = 0
                            generation++
                        }
                    ) {
                        Text("Reload app")
                    }
                else Text("Loading app…", Modifier.padding(24.dp))
        }
    }
    if (displayMode == "fullscreen")
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(app.tool) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, "Close app") }
                    },
                )
            }
        ) { padding ->
            body(Modifier.fillMaxSize().padding(padding))
        }
    else
        Column(Modifier.fillMaxWidth()) {
            Row {
                Text(
                    app.server,
                    Modifier.weight(1f).padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                onFullscreen?.let { open -> TextButton(onClick = open) { Text("Full screen") } }
                TextButton(onClick = ::closeApp) { Text("Close") }
            }
            if (closed)
                TextButton(
                    onClick = {
                        closed = false
                        rendererRestarts = 0
                        generation++
                    }
                ) {
                    Text("Show app")
                }
            else body(Modifier.fillMaxWidth().height(inlineHeight.dp))
        }
    confirmation?.let { request ->
        AlertDialog(
            onDismissRequest = { request.answer.complete(false) },
            title = { Text(request.title) },
            text = {
                SelectionContainer {
                    Text(
                        request.body,
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { request.answer.complete(true) }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = { request.answer.complete(false) }) { Text("Cancel") }
            },
        )
    }
}

internal fun mcpOuterDocument(html: String, app: NativeMcpApp, name: String): String {
    val policy =
        mcpContentPolicy(app).replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    val secured =
        "<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"$policy\">$html"
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{height:100%;margin:0}iframe{display:block;border:0;width:100%;height:100%}</style></head><body><iframe id="app" sandbox="allow-scripts allow-forms"></iframe><script>
${nativePreviewBridge(name)}
(()=>{const frame=document.getElementById('app');let live=true,loads=0;frame.addEventListener('load',()=>{if(++loads>1){live=false;window.T3NativePreview.postMessage(JSON.stringify({type:'navigated'}));}});window.addEventListener('message',e=>{if(live&&e.source===frame.contentWindow){const u=navigator.userActivation;window.T3NativePreview.postMessage(JSON.stringify({active:!!(u&&u.isActive)&&document.activeElement===frame,message:e.data}));}});window.__t3Receive=m=>{if(live)frame.contentWindow.postMessage(m,'*');};window.__t3Pause=()=>{live=false;};window.__t3Resume=()=>{live=true;};window.__t3Dispose=()=>{frame.contentWindow.postMessage({jsonrpc:'2.0',id:'teardown',method:'ui/resource-teardown',params:{}},'*');live=false;};frame.srcdoc=${JsonPrimitive(secured).toString().replace("<", "\\u003c")};})();
</script></body></html>"""
}
