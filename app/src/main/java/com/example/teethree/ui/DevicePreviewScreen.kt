package com.example.teethree.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.PreviewDevice
import com.example.teethree.data.PreviewDeviceState
import com.example.teethree.data.PreviewTools
import com.example.teethree.data.T3Repository
import com.example.teethree.data.array
import com.example.teethree.data.bool
import com.example.teethree.data.deviceToolsNeedUpdate
import com.example.teethree.data.nullableString
import com.example.teethree.data.obj
import com.example.teethree.data.reduceDeviceSnapshot
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
fun DevicePreviewScreen(repository: T3Repository, threadId: String, onBack: () -> Unit) {
    val session by repository.state.collectAsStateWithLifecycle()
    val tools = remember(repository) { PreviewTools(repository) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(PreviewDeviceState()) }
    var firstSnapshotPending by remember { mutableStateOf(true) }
    fun receiveSnapshot(next: PreviewDeviceState) {
        state = reduceDeviceSnapshot(state, next, firstSnapshotPending)
        firstSnapshotPending = false
    }
    var selected by rememberSaveable(threadId) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var choose by rememberSaveable { mutableStateOf(false) }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    var diagnosticsHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var shutdown by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<PreviewDevice?>(null) }
    var detail by remember { mutableStateOf<JsonObject?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Connecting…") }
    var inputConnected by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<PreviewContentView?>(null) }
    val saveVisible = rememberSaveVisiblePreview(view) { error = it }
    val canRead =
        session.connectionStatus == ConnectionStatus.Connected &&
            "orchestration:read" in session.scopes
    val canOperate =
        session.connectionStatus == ConnectionStatus.Connected &&
            "orchestration:operate" in session.scopes
    val previews = state.sessions.filter { it.threadId == threadId }
    val target =
        previews.find { "${it.hostId}\u0000${it.deviceId}" == selected } ?: previews.firstOrNull()
    val device = target?.let { selectedTarget ->
        state.devices.find {
            it.hostId == selectedTarget.hostId && it.id == selectedTarget.deviceId
        }
    }
    var accessRetries by remember(device?.key) { mutableStateOf(0) }
    val host = state.hosts.find { it.id == device?.hostId }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "Device operation failed."
            } finally {
                busy = false
            }
        }
    }
    fun hardware(button: String) {
        view?.command(buildJsonObject { put("button", button) })
    }
    LaunchedEffect(repository, threadId, session.connectionStatus, session.scopes) {
        if (!canRead) return@LaunchedEffect
        var subscription: AutoCloseable? = null
        try {
            inputConnected = false
            accessRetries = 0
            attempt++
            firstSnapshotPending = true
            subscription =
                tools.watchDevices({ failure -> launch { error = failure.message } }) { next ->
                    launch { receiveSnapshot(next) }
                }
            val snapshot = tools.devices()
            receiveSnapshot(snapshot)
            awaitCancellation()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message
        } finally {
            subscription?.close()
        }
    }
    LaunchedEffect(device?.key, settingsOpen) {
        detail = null
        if (device != null && settingsOpen)
            try {
                detail = tools.deviceDetail(device)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message
            }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.name ?: "Devices") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat")
                    }
                },
                actions = {
                    TextButton(onClick = { choose = true }, enabled = !busy) { Text("Devices") }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, "Device options")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            state.hosts
                                .filter { state.supportsHostRetry && it.status == "failed" }
                                .forEach { failedHost ->
                                    DropdownMenuItem(
                                        text = { Text("Retry ${failedHost.label}") },
                                        enabled = canRead && canOperate && !busy,
                                        onClick = {
                                            menu = false
                                            action {
                                                receiveSnapshot(
                                                    tools.retryDeviceHost(failedHost.id)
                                                )
                                            }
                                        },
                                    )
                                }
                            if (state.supportsToolInspection)
                                DropdownMenuItem(
                                    text = { Text("Check device tool versions") },
                                    enabled = canRead && !busy,
                                    onClick = {
                                        menu = false
                                        action {
                                            receiveSnapshot(tools.devices(inspectOnly = true))
                                        }
                                    },
                                )
                            DropdownMenuItem(
                                text = { Text("Device tool versions") },
                                onClick = {
                                    menu = false
                                    diagnosticsHostId = host?.id ?: state.hosts.firstOrNull()?.id
                                    diagnosticsOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Reconnect stream") },
                                enabled = device != null,
                                onClick = {
                                    menu = false
                                    inputConnected = false
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
                                text = { Text("Device settings") },
                                enabled = device != null,
                                onClick = {
                                    menu = false
                                    settingsOpen = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Close preview") },
                                enabled = device != null && canOperate && !busy,
                                onClick = {
                                    menu = false
                                    device?.let {
                                        action {
                                            tools.closeDevice(threadId, it)
                                            receiveSnapshot(tools.devices())
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Shut down device") },
                                enabled = device != null && canOperate && !busy,
                                onClick = {
                                    menu = false
                                    shutdown = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh device list") },
                                enabled = canRead && !busy,
                                onClick = {
                                    menu = false
                                    action {
                                        receiveSnapshot(tools.devices())
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (previews.size > 1)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(previews, key = { "${it.hostId}\u0000${it.deviceId}" }) { entry ->
                        FilterChip(
                            selected = entry == target,
                            onClick = {
                                selected = "${entry.hostId}\u0000${entry.deviceId}"
                                inputConnected = false
                            },
                            label = {
                                Text(
                                    state.devices
                                        .find {
                                            it.hostId == entry.hostId && it.id == entry.deviceId
                                        }
                                        ?.name ?: entry.deviceId
                                )
                            },
                        )
                    }
                }
            error?.let {
                Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            }
            if (device != null) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if (device.platform == "android")
                        TextButton(
                            onClick = { hardware("back") },
                            enabled = canOperate && inputConnected,
                        ) {
                            Text("Back")
                        }
                    TextButton(
                        onClick = { hardware("home") },
                        enabled = canOperate && inputConnected,
                    ) {
                        Text("Home")
                    }
                    TextButton(
                        onClick = { hardware("appSwitcher") },
                        enabled = canOperate && inputConnected,
                    ) {
                        Text("App switcher")
                    }
                    if (device.platform == "ios")
                        TextButton(
                            onClick = { hardware("rotate") },
                            enabled = canOperate && inputConnected,
                        ) {
                            Text("Rotate")
                        }
                }
                Text(
                    status,
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
                PreviewStream(
                    repository,
                    threadId,
                    device = device,
                    serveSimOnly = host?.kind == "simlock",
                    attempt = attempt,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onView = { view = it },
                    onMessage = { message ->
                        when (message.string("type")) {
                            "input" -> inputConnected = message.bool("connected")
                            "status" -> {
                                status =
                                    message.string("detail").ifBlank { message.string("status") }
                                if (message.string("status") == "streaming") accessRetries = 0
                            }
                            "unauthorized" -> {
                                inputConnected = false
                                if (accessRetries < 1) {
                                    accessRetries++
                                    attempt++
                                } else error = "Device stream access expired. Reconnect the stream."
                            }
                            "retry" -> {
                                inputConnected = false
                                attempt++
                            }
                        }
                    },
                )
            } else {
                Text(
                    state.detail
                        ?: "Open a device from the connected environment to preview it here.",
                    Modifier.padding(24.dp),
                )
                Button(
                    onClick = { choose = true },
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text("Choose a device")
                }
            }
        }
    }
    if (choose)
        AlertDialog(
            onDismissRequest = { choose = false },
            title = { Text("Devices in this environment") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    state.hosts.forEach { hostEntry ->
                        item(key = "host:${hostEntry.id}") {
                            Text(
                                "${hostEntry.label} · ${hostEntry.status}${hostEntry.detail?.let { "\n$it" }.orEmpty()}",
                                Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                    items(state.devices, key = { it.key }) { item ->
                        ListItem(
                            headlineContent = { Text(item.name) },
                            supportingContent = {
                                Text(
                                    "${state.hosts.find { it.id == item.hostId }?.label ?: item.hostId} · ${item.version} · ${if (item.booted) "Running" else "Stopped"}"
                                )
                            },
                            trailingContent = {
                                TextButton(
                                    enabled = canOperate && !busy,
                                    onClick = {
                                        if (!item.booted) opening = item
                                        else
                                            action {
                                                tools.openDevice(threadId, item)
                                                selected = item.key
                                                receiveSnapshot(tools.devices())
                                                choose = false
                                            }
                                    },
                                ) {
                                    Text("Open")
                                }
                            },
                        )
                    }
                    if (state.devices.isEmpty())
                        item {
                            Text(
                                "No devices reported. Configure device support in the connected environment."
                            )
                        }
                }
            },
            confirmButton = { TextButton(onClick = { choose = false }) { Text("Done") } },
        )
    opening?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!busy) opening = null },
            title = { Text("Start ${item.name}?") },
            text = {
                Text(
                    if (state.hosts.find { it.id == item.hostId }?.kind == "eas")
                        "This starts a cloud simulator billed to the environment's Expo account."
                    else "The simulator or emulator starts on its device host."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && canOperate,
                    onClick = {
                        action {
                            tools.openDevice(threadId, item)
                            selected = item.key
                            receiveSnapshot(tools.devices())
                            opening = null
                            choose = false
                        }
                    },
                ) {
                    Text("Start")
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { opening = null }) { Text("Cancel") }
            },
        )
    }
    if (shutdown && device != null)
        AlertDialog(
            onDismissRequest = { shutdown = false },
            title = { Text("Shut down ${device.name}?") },
            text = {
                Text(
                    "The device stops for everyone using this environment. Its preview can be reopened later."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && canOperate,
                    onClick = {
                        action {
                            tools.shutdownDevice(device)
                            shutdown = false
                            receiveSnapshot(tools.devices())
                        }
                    },
                ) {
                    Text("Shut down")
                }
            },
            dismissButton = { TextButton(onClick = { shutdown = false }) { Text("Cancel") } },
        )
    if (diagnosticsOpen)
        AlertDialog(
            onDismissRequest = { diagnosticsOpen = false },
            title = { Text("Device tool versions") },
            text = {
                Column(
                    Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "The environment's T3 server chooses device tool versions for this machine and its SSH hosts. Update that server to receive newer tools; updating the mobile app does not update a remote server.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.hosts.size > 1)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.hosts.forEach { candidate ->
                                FilterChip(
                                    selected = candidate.id == diagnosticsHostId,
                                    onClick = { diagnosticsHostId = candidate.id },
                                    label = { Text(candidate.label) },
                                )
                            }
                        }
                    val diagnosticsHost =
                        state.hosts.firstOrNull { it.id == diagnosticsHostId }
                            ?: host
                            ?: state.hosts.firstOrNull()
                    if (diagnosticsHost != null)
                        Text(diagnosticsHost.label, style = MaterialTheme.typography.titleSmall)
                    val versions = diagnosticsHost?.tools
                    Text(
                        when {
                            versions == null ->
                                "Versions have not been checked. Reconnect the host and check versions."
                            deviceToolsNeedUpdate(versions) ->
                                "An update is pending. Required tools install automatically when next used. The host needs network access; older installs are not used as a fallback."
                            else ->
                                "Required tools install automatically when needed. Checking versions does not install or start anything."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (versions != null)
                        listOf("hub" to "Device hub", "agent" to "Agent tools").forEach {
                            (key, label) ->
                            val version = versions.obj(key)
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            val installed =
                                version.array("installedVersions").mapNotNull {
                                    (it as? kotlinx.serialization.json.JsonPrimitive)
                                        ?.takeIf { it.isString }
                                        ?.content
                                }
                            SelectionContainer {
                                Text(
                                    "Installed: ${installed.joinToString(", ").ifBlank { "none" }}\nRequired: ${version.string("requiredVersion")}" +
                                        version
                                            .nullableString("runningVersion")
                                            ?.let { "\nRunning: $it" }
                                            .orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    (diagnosticsHost?.toolInspectionError ?: diagnosticsHost?.detail)?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.supportsToolInspection)
                        TextButton(
                            onClick = {
                                action {
                                    receiveSnapshot(tools.devices(inspectOnly = true))
                                }
                            },
                            enabled = canRead && !busy,
                        ) {
                            Text(if (busy) "Checking…" else "Check versions")
                        }
                    if (diagnosticsHost?.status == "failed" && state.supportsHostRetry)
                        TextButton(
                            onClick = {
                                action {
                                    receiveSnapshot(tools.retryDeviceHost(diagnosticsHost.id))
                                }
                            },
                            enabled = canRead && canOperate && !busy,
                        ) {
                            Text("Retry ${diagnosticsHost.label}")
                        }
                }
            },
            confirmButton = { TextButton(onClick = { diagnosticsOpen = false }) { Text("Done") } },
        )
    if (settingsOpen && device != null)
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Device settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val settings = detail?.obj("settings") ?: JsonObject(emptyMap())
                    detail?.obj("foregroundApp")?.string("id")?.takeIf(String::isNotBlank)?.let {
                        Text("Current app: $it")
                    }
                    if ("appearance" in settings)
                        ChoiceField(
                            "Appearance",
                            settings.string("appearance"),
                            if (canOperate && !busy) listOf("light" to "Light", "dark" to "Dark")
                            else emptyList(),
                        ) { value ->
                            action {
                                detail =
                                    tools.deviceAction(
                                        device,
                                        "setAppearance",
                                        buildJsonObject { put("value", value) },
                                    )
                            }
                        }
                    if ("textSize" in settings)
                        ChoiceField(
                            "Text size",
                            settings.string("textSize"),
                            if (canOperate && !busy)
                                listOf(
                                    "small" to "Small",
                                    "default" to "Default",
                                    "large" to "Large",
                                    "extra-large" to "Extra large",
                                )
                            else emptyList(),
                        ) { value ->
                            action {
                                detail =
                                    tools.deviceAction(
                                        device,
                                        "setTextSize",
                                        buildJsonObject { put("value", value) },
                                    )
                            }
                        }
                    listOf(
                            "reduceMotion" to "Reduce motion",
                            "increaseContrast" to "Increase contrast",
                            "reduceTransparency" to "Reduce transparency",
                            "showBorders" to "Show borders",
                            "voiceOver" to "VoiceOver",
                            "networkEnabled" to "Network enabled",
                        )
                        .filter { it.first in settings }
                        .forEach { (key, label) ->
                            Row {
                                Text(label, Modifier.weight(1f))
                                Switch(
                                    settings.bool(key),
                                    enabled = canOperate && !busy,
                                    onCheckedChange = { value ->
                                        action {
                                            detail =
                                                tools.deviceAction(
                                                    device,
                                                    "setToggle",
                                                    buildJsonObject {
                                                        put("setting", key)
                                                        put("value", value)
                                                    },
                                                )
                                        }
                                    },
                                )
                            }
                        }
                    if (settings.isEmpty()) Text("This device does not expose settings here.")
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Done") } },
        )
}
