package com.example.teethree.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.BrowserPreviewState
import com.example.teethree.data.BrowserPreviewTab
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.PreviewTools
import com.example.teethree.data.PreviewTransfers
import com.example.teethree.data.T3Repository
import com.example.teethree.data.bool
import com.example.teethree.data.browserPdfLocation
import com.example.teethree.data.normalizeBrowserPreviewUrl
import com.example.teethree.data.previewAnnotationComposerContext
import com.example.teethree.data.reduceBrowserPreviewEvent
import com.example.teethree.data.string
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPreviewScreen(
    repository: T3Repository,
    threadId: String,
    onBack: () -> Unit,
    onAddContext: ((JsonObject) -> Unit)? = null,
) {
    val context = LocalContext.current
    val session by repository.state.collectAsStateWithLifecycle()
    val tools = remember(repository) { PreviewTools(repository) }
    val scope = rememberCoroutineScope()
    var state by remember(threadId) { mutableStateOf(BrowserPreviewState()) }
    var selectedId by rememberSaveable(threadId) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var newTab by rememberSaveable { mutableStateOf(false) }
    var newUrl by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Connecting…") }
    var control by remember { mutableStateOf<JsonObject?>(null) }
    var view by remember { mutableStateOf<PreviewContentView?>(null) }
    var prompt by remember { mutableStateOf("") }
    var fileChooser by remember { mutableStateOf<Pair<BrowserPreviewTab, JsonObject>?>(null) }
    var activeUpload by remember { mutableStateOf<Pair<BrowserPreviewTab, JsonObject>?>(null) }
    var browserDownload by remember { mutableStateOf<Pair<BrowserPreviewTab, JsonObject>?>(null) }
    var activeDownload by remember { mutableStateOf<Pair<BrowserPreviewTab, JsonObject>?>(null) }
    var clipboardText by remember { mutableStateOf<String?>(null) }
    var noteTarget by remember { mutableStateOf<BrowserPreviewTab?>(null) }
    var pageNote by remember { mutableStateOf("") }
    val transfers =
        remember(repository, context) { PreviewTransfers(repository, context.contentResolver) }
    val canOperate =
        session.connectionStatus == ConnectionStatus.Connected &&
            "preview:operate" in session.scopes
    val canControl = canOperate && control?.string("controller") == "you"
    val tab = state.tabs.find { it.id == selectedId } ?: state.tabs.firstOrNull()
    var accessRetries by remember(tab?.id) { mutableStateOf(0) }
    var addressDraft by rememberSaveable(tab?.id) { mutableStateOf<String?>(null) }
    fun command(type: String, values: JsonObject = JsonObject(emptyMap())) {
        view?.command(
            buildJsonObject {
                put("type", type)
                values.forEach { (key, value) -> put(key, value) }
            }
        )
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Browser operation failed."
            } finally {
                busy = false
            }
        }
    }
    fun uploadChosen(files: List<Uri>) {
        val target = activeUpload ?: return
        activeUpload = null
        action { transfers.upload(target.first, target.second, files) }
    }
    val chooseFile =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uploadChosen(listOfNotNull(uri))
        }
    val chooseFiles =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uploadChosen(uris)
        }
    val saveDownload =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val target = activeDownload
            activeDownload = null
            if (uri != null && target != null)
                action { transfers.download(target.first, target.second, uri) }
        }
    val saveVisible = rememberSaveVisiblePreview(view) { error = it }
    LaunchedEffect(repository, threadId, session.connectionStatus) {
        if (session.connectionStatus != ConnectionStatus.Connected) return@LaunchedEffect
        val pending = mutableListOf<JsonObject>()
        var initial = true
        var subscription: AutoCloseable? = null
        try {
            control = null
            accessRetries = 0
            attempt++
            subscription =
                tools.watchBrowsers({ failure -> launch { error = failure.message } }) { event ->
                    launch {
                        if (initial) pending.add(event)
                        else state = reduceBrowserPreviewEvent(state, event, threadId)
                    }
                }
            var snapshot = tools.browsers(threadId)
            pending.forEach { snapshot = reduceBrowserPreviewEvent(snapshot, it, threadId) }
            state = snapshot
            initial = false
            awaitCancellation()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message
        } finally {
            subscription?.close()
        }
    }
    fun navigate() {
        try {
            val url = normalizeBrowserPreviewUrl(addressDraft ?: tab?.url.orEmpty())
            command("navigate", buildJsonObject { put("url", url) })
            addressDraft = null
        } catch (failure: IllegalArgumentException) {
            error = failure.message
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Browser", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat")
                    }
                },
                actions = {
                    IconButton(onClick = { newTab = true }, enabled = canOperate && !busy) {
                        Icon(Icons.Outlined.Add, "New browser tab")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, "Browser options")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            if (onAddContext != null)
                                DropdownMenuItem(
                                    text = { Text("Add page note to task") },
                                    enabled =
                                        tab != null &&
                                            session.connectionStatus == ConnectionStatus.Connected,
                                    onClick = {
                                        menu = false
                                        noteTarget = tab
                                        pageNote = ""
                                    },
                                )
                            DropdownMenuItem(
                                text = { Text("Reconnect stream") },
                                onClick = {
                                    menu = false
                                    control = null
                                    attempt++
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Save visible preview") },
                                enabled = view != null,
                                onClick = {
                                    menu = false
                                    saveVisible()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Paste into page") },
                                enabled = canControl,
                                onClick = {
                                    menu = false
                                    (context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as ClipboardManager)
                                        .primaryClip
                                        ?.getItemAt(0)
                                        ?.coerceToText(context)
                                        ?.toString()
                                        ?.let { text ->
                                            command("text", buildJsonObject { put("text", text) })
                                        }
                                },
                            )
                            listOf(
                                    "system" to "System appearance",
                                    "light" to "Light appearance",
                                    "dark" to "Dark appearance",
                                )
                                .forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        enabled = tab != null && canOperate && !busy,
                                        onClick = {
                                            menu = false
                                            tab?.let { action { tools.adjustBrowser(it, value) } }
                                        },
                                    )
                                }
                            listOf(
                                    "fill" to "Fit viewport to screen",
                                    "iphone-se" to "Phone viewport (375 × 667)",
                                    "ipad-mini" to "Tablet viewport (768 × 1024)",
                                )
                                .forEach { (preset, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        enabled = tab != null && canOperate && !busy,
                                        onClick = {
                                            menu = false
                                            tab?.let { target ->
                                                action {
                                                    tools.resizeBrowser(
                                                        target,
                                                        buildJsonObject {
                                                            put(
                                                                "_tag",
                                                                if (preset == "fill") "fill"
                                                                else "preset",
                                                            )
                                                            if (preset != "fill") {
                                                                put("presetId", preset)
                                                                put(
                                                                    "width",
                                                                    if (preset == "iphone-se") 375
                                                                    else 768,
                                                                )
                                                                put(
                                                                    "height",
                                                                    if (preset == "iphone-se") 667
                                                                    else 1024,
                                                                )
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            val zoomLevels =
                                listOf(
                                    .25,
                                    .33,
                                    .5,
                                    .67,
                                    .75,
                                    .8,
                                    .9,
                                    1.0,
                                    1.1,
                                    1.25,
                                    1.5,
                                    1.75,
                                    2.0,
                                    2.5,
                                    3.0,
                                    4.0,
                                    5.0,
                                )
                            listOf(-1 to "Zoom out", 0 to "Actual size", 1 to "Zoom in").forEach {
                                (step, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    enabled = tab != null && canOperate && !busy,
                                    onClick = {
                                        menu = false
                                        tab?.let { target ->
                                            action {
                                                val index =
                                                    zoomLevels.indexOf(target.zoomFactor).takeIf {
                                                        it >= 0
                                                    } ?: 7
                                                tools.adjustBrowser(
                                                    target,
                                                    buildJsonObject {
                                                        put(
                                                            "zoomFactor",
                                                            if (step == 0) 1.0
                                                            else
                                                                zoomLevels[
                                                                    (index + step).coerceIn(
                                                                        0,
                                                                        zoomLevels.lastIndex,
                                                                    )],
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Hard reload") },
                                enabled = tab != null && canOperate && !busy,
                                onClick = {
                                    menu = false
                                    tab?.let {
                                        action {
                                            tools.adjustBrowser(
                                                it,
                                                buildJsonObject { put("hardReload", true) },
                                            )
                                        }
                                    }
                                },
                            )
                            listOf("cookies" to "Clear cookies", "cache" to "Clear cache")
                                .forEach { (clear, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        enabled = tab != null && canOperate && !busy,
                                        onClick = {
                                            menu = false
                                            tab?.let {
                                                action {
                                                    tools.adjustBrowser(
                                                        it,
                                                        buildJsonObject { put("clear", clear) },
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            DropdownMenuItem(
                                text = { Text("Close tab") },
                                enabled = tab != null && canOperate && !busy,
                                onClick = {
                                    menu = false
                                    tab?.let {
                                        action {
                                            tools.closeBrowser(it)
                                            selectedId = null
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (state.tabs.isNotEmpty())
                LazyRow(
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.tabs, key = { it.id }) { entry ->
                        FilterChip(
                            selected = tab?.id == entry.id,
                            onClick = {
                                selectedId = entry.id
                                control = null
                            },
                            label = {
                                Text(
                                    entry.title.ifBlank { "Browser" },
                                    Modifier.widthIn(max = 180.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            if (error != null)
                Text(
                    error.orEmpty(),
                    Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            if (tab != null) {
                OutlinedTextField(
                    value = addressDraft ?: tab.url,
                    onValueChange = { addressDraft = it },
                    enabled = canControl,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    singleLine = true,
                    label = { Text("Address") },
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { navigate() }),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = { command("history", buildJsonObject { put("delta", -1) }) },
                        enabled = canControl && tab.canGoBack,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous page")
                    }
                    IconButton(
                        onClick = { command("history", buildJsonObject { put("delta", 1) }) },
                        enabled = canControl && tab.canGoForward,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next page")
                    }
                    IconButton(onClick = { command("reload") }, enabled = canControl) {
                        Icon(Icons.Outlined.Refresh, "Reload page")
                    }
                    TextButton(
                        onClick = { command(if (canControl) "releaseControl" else "takeControl") },
                        enabled = canOperate,
                    ) {
                        Text(if (canControl) "Release control" else "Take control")
                    }
                    Text(
                        status,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (tab.runtime != "server") {
                    Text(
                        "This tab runs in the desktop browser. Open a server tab to preview it here.",
                        Modifier.padding(24.dp),
                    )
                } else
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        PreviewStream(
                            repository,
                            threadId,
                            tab.id,
                            attempt = attempt,
                            modifier = Modifier.fillMaxSize(),
                            onView = { view = it },
                            onMessage = { message ->
                                when (message.string("type")) {
                                    "control" -> {
                                        control = message
                                        status =
                                            if (message.string("controller") == "you")
                                                "You have control"
                                            else if (!message.bool("canOperate")) "Read-only"
                                            else "Watching"
                                    }
                                    "status" -> {
                                        status =
                                            message.string("detail").ifBlank {
                                                message.string("status")
                                            }
                                        if (message.string("status") == "streaming")
                                            accessRetries = 0
                                    }
                                    "unauthorized" ->
                                        if (accessRetries < 1) {
                                            accessRetries++
                                            control = null
                                            attempt++
                                        } else {
                                            error = "Preview access expired. Reconnect the stream."
                                        }
                                    "gone" -> error = "This browser tab has closed."
                                    "hostSetup" ->
                                        error =
                                            "Browser host setup is required: ${message.string("command")}"
                                    "fileChooser" ->
                                        fileChooser =
                                            (message["chooser"] as? JsonObject)?.let { tab to it }
                                    "download" -> browserDownload = tab to message
                                    "clipboard" -> clipboardText = message.string("text")
                                }
                            },
                        )
                        repository.httpBaseUrl
                            ?.let { origin -> browserPdfLocation(tab.url, origin) }
                            ?.let { pdf ->
                                BrowserPdfPreview(repository, pdf, Modifier.fillMaxSize())
                            }
                    }
            } else {
                Text("No browser tabs are open for this chat.", Modifier.padding(24.dp))
                Button(
                    onClick = { newTab = true },
                    enabled = canOperate && !busy,
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text("Open a browser tab")
                }
            }
        }
    }
    noteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { noteTarget = null },
            title = { Text("Add page note") },
            text = {
                Column {
                    Text(target.url, maxLines = 3)
                    Text(
                        "This note includes the page address and your comment.",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    OutlinedTextField(
                        pageNote,
                        { if (it.length <= 8000) pageNote = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Comment") },
                        minLines = 2,
                        maxLines = 6,
                    )
                    if (target.url.length > 2048)
                        Text(
                            "This page address is too long to attach as context.",
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pageNote.isNotBlank() && target.url.length <= 2048,
                    onClick = {
                        onAddContext?.invoke(
                            previewAnnotationComposerContext(
                                target.url,
                                target.title.take(2048),
                                pageNote,
                            )
                        )
                        noteTarget = null
                    },
                ) {
                    Text("Attach note")
                }
            },
            dismissButton = { TextButton(onClick = { noteTarget = null }) { Text("Cancel") } },
        )
    }
    if (newTab)
        AlertDialog(
            onDismissRequest = { if (!busy) newTab = false },
            title = { Text("Open server browser") },
            text = {
                Column {
                    Text("The page opens in the connected environment.")
                    OutlinedTextField(
                        newUrl,
                        { newUrl = it },
                        label = { Text("URL") },
                        singleLine = true,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canOperate && !busy && newUrl.isNotBlank(),
                    onClick = {
                        action {
                            tools.openBrowser(threadId, newUrl)
                            state = tools.browsers(threadId)
                            selectedId = state.tabs.lastOrNull()?.id
                            newTab = false
                            newUrl = ""
                        }
                    },
                ) {
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(onClick = { newTab = false }, enabled = !busy) { Text("Cancel") }
            },
        )
    fileChooser?.let { chooser ->
        fun cancelPicker() {
            fileChooser = null
            action { transfers.upload(chooser.first, chooser.second, emptyList()) }
        }
        AlertDialog(
            onDismissRequest = ::cancelPicker,
            title = { Text("Upload files to this page?") },
            text = { Text("Select files from this device. Each file must be at most 50 MiB.") },
            confirmButton = {
                TextButton(
                    enabled = canControl && !busy,
                    onClick = {
                        fileChooser = null
                        activeUpload = chooser
                        val types =
                            chooser.second
                                .string("accept")
                                .split(',')
                                .map(String::trim)
                                .filter { it.contains('/') }
                                .toTypedArray()
                                .ifEmpty { arrayOf("*/*") }
                        if (chooser.second.bool("multiple")) chooseFiles.launch(types)
                        else chooseFile.launch(types)
                    },
                ) {
                    Text("Choose files")
                }
            },
            dismissButton = { TextButton(onClick = ::cancelPicker) { Text("Cancel") } },
        )
    }
    browserDownload?.let { item ->
        AlertDialog(
            onDismissRequest = { browserDownload = null },
            title = { Text("Save browser download?") },
            text = { Text(item.second.string("fileName", "download")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        browserDownload = null
                        activeDownload = item
                        saveDownload.launch(
                            item.second
                                .string("fileName", "download")
                                .substringAfterLast('/')
                                .take(200)
                        )
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { browserDownload = null }) { Text("Cancel") } },
        )
    }
    clipboardText?.let { text ->
        AlertDialog(
            onDismissRequest = { clipboardText = null },
            title = { Text("Copy text from browser?") },
            text = { Text(text) },
            confirmButton = {
                TextButton(
                    onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Browser", text))
                        clipboardText = null
                    }
                ) {
                    Text("Copy")
                }
            },
            dismissButton = { TextButton(onClick = { clipboardText = null }) { Text("Cancel") } },
        )
    }
    val dialog = control?.get("dialog") as? JsonObject
    if (dialog != null)
        AlertDialog(
            onDismissRequest = { command("dialog", buildJsonObject { put("accept", false) }) },
            title = { Text("Browser dialog") },
            text = {
                Column {
                    Text(dialog.string("message"))
                    if (dialog.string("type") == "prompt")
                        OutlinedTextField(prompt, { prompt = it }, label = { Text("Response") })
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        command(
                            "dialog",
                            buildJsonObject {
                                put("accept", true)
                                put("promptText", prompt)
                            },
                        )
                        prompt = ""
                    },
                    enabled = canControl,
                ) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        command("dialog", buildJsonObject { put("accept", false) })
                        prompt = ""
                    },
                    enabled = canControl,
                ) {
                    Text("Dismiss")
                }
            },
        )
}
