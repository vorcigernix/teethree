package com.example.teethree.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.example.teethree.data.PreviewDevice
import com.example.teethree.data.PreviewTools
import com.example.teethree.data.T3Repository
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
internal fun PreviewStream(
    repository: T3Repository,
    threadId: String,
    tabId: String? = null,
    device: PreviewDevice? = null,
    serveSimOnly: Boolean = false,
    attempt: Int = 0,
    modifier: Modifier = Modifier,
    onMessage: (JsonObject) -> Unit,
    onView: (PreviewContentView?) -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val identity = "${repository.connectionId}:$threadId:$tabId:${device?.key}:$attempt"
    var html by remember(identity) { mutableStateOf<String?>(null) }
    var origin by remember(identity) { mutableStateOf<URI?>(null) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var rendererFailed by remember(identity) { mutableStateOf(false) }
    val name = remember(identity) { UUID.randomUUID().toString() }
    val background = "#%06x".format(colors.surface.toArgb() and 0xffffff)
    LaunchedEffect(identity, background) {
        try {
            val access = PreviewTools(repository).mediaAccess(device)
            origin = URI(((access["httpBase"] as JsonPrimitive).content))
            val script =
                withContext(Dispatchers.IO) {
                    context.assets
                        .open("previews/${if (device == null) "preview" else "device"}-stream.js")
                        .bufferedReader()
                        .use { it.readText() }
                }
            val configuration = buildJsonObject {
                put("access", access)
                if (device == null) {
                    put("threadId", threadId)
                    put("tabId", checkNotNull(tabId))
                    put("interactive", "preview:operate" in repository.state.value.scopes)
                    put("background", background)
                } else {
                    put("platform", device.platform)
                    put("deviceId", device.id)
                    put("serveSimOnly", serveSimOnly)
                    put("videoOnly", "orchestration:operate" !in repository.state.value.scopes)
                    put(
                        "colors",
                        buildJsonObject {
                            put("background", background)
                            put(
                                "foreground",
                                "#%06x".format(colors.onSurface.toArgb() and 0xffffff),
                            )
                            put(
                                "muted",
                                "#%06x".format(colors.onSurfaceVariant.toArgb() and 0xffffff),
                            )
                            put("buttonBackground", background)
                            put(
                                "buttonForeground",
                                "#%06x".format(colors.primary.toArgb() and 0xffffff),
                            )
                            put("buttonBorder", background)
                        },
                    )
                }
            }
            html = streamPreviewDocument(script, configuration, device != null, name)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "Could not open the preview."
        }
    }
    val content = html
    if (content != null && !rendererFailed)
        PreviewContent(
            content,
            name,
            modifier,
            onMessage = { raw ->
                (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)?.let {
                    message ->
                    if ((message["type"] as? JsonPrimitive)?.content == "renderProcessGone") {
                        rendererFailed = true
                        error = "The preview renderer stopped. Reconnect the stream."
                    }
                    onMessage(message)
                }
            },
            allowRequest = { uri ->
                val base = origin
                base != null &&
                    uri.scheme == base.scheme &&
                    uri.host == base.host &&
                    (if (uri.port < 0) if (uri.scheme == "https") 443 else 80 else uri.port) ==
                        (if (base.port < 0) if (base.scheme == "https") 443 else 80
                        else base.port) &&
                    uri.path.orEmpty().startsWith(base.path.trimEnd('/') + "/")
            },
            onView = onView,
        )
    else
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "Connecting to preview…")
        }
}
