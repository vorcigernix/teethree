package com.example.teethree.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.AppAction
import com.example.teethree.AppViewModel
import com.example.teethree.awareness.AgentNotifications
import com.example.teethree.awareness.AwarenessRoute
import com.example.teethree.awareness.parseAwarenessRoute
import com.example.teethree.data.ChatAttachment
import com.example.teethree.data.ComposerContextSelection
import com.example.teethree.data.ComposerDraft
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.ModelSelection
import com.example.teethree.data.SavedConnection
import com.example.teethree.data.NativeMcpApp
import com.example.teethree.data.ProjectDefaultsTools
import com.example.teethree.data.RepositoryState
import com.example.teethree.data.T3Repository
import com.example.teethree.data.ThreadSearchJump
import com.example.teethree.data.WorkspaceEnvironmentShell
import com.example.teethree.data.WorkspaceProjectRef
import com.example.teethree.data.effectiveProjectDefaults
import com.example.teethree.data.nullableString
import com.example.teethree.data.obj
import com.example.teethree.data.parseModelSelection
import com.example.teethree.data.projectDefaultModelOptions
import com.example.teethree.data.resolvedNewTaskWorkspaceMode
import com.example.teethree.ui.adaptive.ConversationPaneScaffold
import com.example.teethree.ui.adaptive.FoldSafeContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class ComposerContextTarget(
    val connectionId: String,
    val threadId: String?,
    val projectId: String?,
)

@Composable
fun T3App(model: AppViewModel, incomingLink: String?, onLinkConsumed: () -> Unit) {
    val app by model.state.collectAsStateWithLifecycle()
    val repository by model.repository.state.collectAsStateWithLifecycle()
    val usage by model.usage.collectAsStateWithLifecycle()
    val notificationStatus by model.awareness.status.collectAsStateWithLifecycle()
    val cloudAccount by model.cloud.account.state.collectAsStateWithLifecycle()
    val allWorkspaceSources by model.workspaces.environments.collectAsStateWithLifecycle()
    val workspaceSources = allWorkspaceSources.filter { source ->
        app.connections.any {
            it.id == source.connectionId &&
                (it.cloudAccountId == null || it.cloudAccountId == cloudAccount.identity?.accountId)
        }
    }
    var screen by rememberSaveable { mutableStateOf("workspace") }
    var openedMcpApp by rememberSaveable { mutableStateOf<String?>(null) }
    var openedAttachment by rememberSaveable { mutableStateOf<String?>(null) }
    var attachmentConnectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var terminalId by rememberSaveable { mutableStateOf("term-1") }
    var pendingSearchJump by rememberSaveable { mutableStateOf<String?>(null) }
    var contextTargetJson by rememberSaveable { mutableStateOf<String?>(null) }
    var commandsVisible by remember { mutableStateOf(false) }
    var settingsPage by rememberSaveable {
        mutableStateOf(com.example.teethree.data.SettingsPage.General)
    }
    var pairingLink by rememberSaveable { mutableStateOf("") }
    var pairingSession by rememberSaveable { mutableLongStateOf(0L) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var toolsProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var newTaskProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var newTaskSession by rememberSaveable { mutableLongStateOf(0L) }
    val snackbar = remember { SnackbarHostState() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val threadState = rememberSaveableStateHolder()
    val destinationState = rememberSaveableStateHolder()
    val connection = app.connections.find { it.id == app.activeConnectionId }
    val authBlocked =
        connection?.cloudAccountId != null &&
            connection.cloudAccountId != cloudAccount.identity?.accountId
    val connected = repository.connectionStatus == ConnectionStatus.Connected && !authBlocked
    val canOperate = connected && "orchestration:operate" in repository.scopes
    val selectedThread = repository.threads.find { it.id == repository.selectedThreadId }
    val searchJump =
        pendingSearchJump
            ?.let { Json.decodeFromString<ThreadSearchJump>(it) }
            ?.takeIf {
                it.connectionId == app.activeConnectionId && it.threadId == selectedThread?.id
            }
    val contextTarget =
        contextTargetJson
            ?.let { Json.decodeFromString<ComposerContextTarget>(it) }
            ?.takeIf { it.connectionId == app.activeConnectionId && !authBlocked }
    val incomingShare = app.incomingShares.find { it.id == app.selectedIncomingShareId }
    val pairingVisible =
        app.initialized &&
            (screen == "pair" ||
                (app.connections.isEmpty() &&
                    screen !in setOf("cloud", "notifications", "usage", "share", "shares")))

    fun startPairing(link: String) {
        destinationState.removeState("pair:$pairingSession")
        pairingSession += 1
        pairingLink = link
        screen = "pair"
    }

    fun startNewTask(projectId: String? = null) {
        destinationState.removeState("${app.activeConnectionId}:new:$newTaskSession")
        newTaskSession += 1
        newTaskProjectId = projectId
        screen = "new"
    }

    fun openComposerContext(threadId: String?, projectId: String?) {
        val owner = app.activeConnectionId ?: return
        contextTargetJson = Json.encodeToString(ComposerContextTarget(owner, threadId, projectId))
    }

    LaunchedEffect(incomingLink, app.initialized) {
        if (incomingLink != null && app.initialized) {
            val route = parseAwarenessRoute(incomingLink)
            when {
                route is AwarenessRoute.Thread -> {
                    model.openNotification(route)
                    screen = "workspace"
                }
                route is AwarenessRoute.Usage -> screen = "usage"
                model.handleSignInLink(incomingLink) -> screen = "cloud"
                else -> startPairing(incomingLink)
            }
            onLinkConsumed()
        }
    }
    LaunchedEffect(screen, repository.selectedThreadId, connection?.environmentId) {
        val environmentId = connection?.environmentId
        val threadId = repository.selectedThreadId
        fun encode(value: String) =
            java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
        AgentNotifications.setThreadOnScreen(
            if (screen == "workspace" && environmentId != null && threadId != null)
                "/threads/${encode(environmentId)}/${encode(threadId)}"
            else null
        )
    }
    LaunchedEffect(screen, repository.pendingRequests) {
        if (screen == "mcp" && repository.pendingRequests.isNotEmpty()) screen = "workspace"
    }
    LaunchedEffect(app.selectedIncomingShareId) {
        if (app.selectedIncomingShareId != null) screen = "share"
    }
    LaunchedEffect(app.error) {
        app.error?.let {
            snackbar.showSnackbar(it)
            model.clearError()
        }
    }
    LaunchedEffect(app.actionCompletion) {
        val completion = app.actionCompletion ?: return@LaunchedEffect
        if (completion.connectionId == app.activeConnectionId) {
            val source =
                when (completion.action) {
                    AppAction.Pair -> "pair"
                    AppAction.LaunchConversation -> "new"
                    AppAction.CreateProject -> "project"
                    AppAction.ImportShare -> "share"
                    AppAction.OpenWorkspaceTask -> "workspace"
                }
            if (screen == source || (completion.action == AppAction.Pair && screen == "cloud")) {
                if (completion.action in setOf(AppAction.ImportShare, AppAction.OpenWorkspaceTask))
                    startNewTask(completion.projectId)
                else screen = "workspace"
                if (completion.action == AppAction.Pair) pairingLink = ""
            }
        }
        model.consumeActionCompletion(completion.id)
    }
    BackHandler(enabled = screen != "workspace") { screen = "workspace" }
    LaunchedEffect(showArchived) { model.setWorkspaceArchived(showArchived) }

    var handsFree by rememberSaveable { mutableStateOf(false) }
    val composerInputMode =
        remember(app.preferences.composerInput, handsFree) {
            ComposerInputMode(
                voice = app.preferences.composerInput != "keyboard",
                onChange = { voice -> model.setComposerInput(if (voice) "voice" else "keyboard") },
                handsFree = handsFree,
                onHandsFreeChange = { handsFree = it },
            )
        }
    var connectionHelp by rememberSaveable { mutableStateOf(false) }
    val link =
        rememberLinkUi(
            LinkInfo(
                status = repository.connectionStatus,
                host = connection?.let(::hostLabel) ?: "desktop",
                address = connection?.let(::hostAddress),
                attempts = repository.reconnectAttempts,
                offlineSince = repository.offlineSince,
                lastLiveAt = repository.lastLiveAt,
                nextRetryAt = repository.nextRetryAt,
                syncing = repository.connectionStatus == ConnectionStatus.Connected &&
                    !repository.shellReady,
                queued =
                    app.outbox
                        .filterKeys { it.startsWith("${app.activeConnectionId}:") }
                        .values
                        .sumOf { it.size },
                running = repository.threads.count { it.activeRunId != null },
                waiting = repository.threads.count { it.needsAttention },
                error = repository.error,
            ),
            retry = model::retryNow,
            help = { connectionHelp = true },
        )
    // Replies written while the desktop is away wait in the outbox instead of failing.
    val canQueue =
        !connected &&
            connection != null &&
            !authBlocked &&
            "orchestration:operate" in repository.scopes
    val appContext = LocalContext.current
    LaunchedEffect(Unit) { SpokenMessages.load(appContext) }
    // Half-folded and resting flat: the open chat becomes the tabletop voice console.
    val windowPosture = currentWindowAdaptiveInfoV2().windowPosture
    val tabletopHinge =
        windowPosture.takeIf { it.isTabletop }?.hingeList?.firstOrNull { !it.isVertical }?.bounds
    var tabletopDismissed by
        remember(tabletopHinge != null, selectedThread?.id, app.preferences.composerInput) {
            mutableStateOf(false)
        }
    val tabletopThread =
        selectedThread.takeIf {
            tabletopHinge != null &&
                !tabletopDismissed &&
                screen == "workspace" &&
                app.preferences.composerInput != "keyboard"
        }
    T3Theme(app.theme, appearance = app.preferences.appearance) {
      CompositionLocalProvider(
          LocalComposerInputMode provides composerInputMode,
          LocalLink provides link.takeIf { connection != null && !authBlocked },
          LocalGlassBlur provides rememberGlassBlurAllowed(),
      ) {
        if (connectionHelp && connection != null)
            ConnectionHelpSheet(
                onDismiss = { connectionHelp = false },
                onSwitchHost = {
                    connectionHelp = false
                    screen = "settings"
                },
                onRePair = {
                    connectionHelp = false
                    startPairing("")
                },
            )
        Surface(
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (
                    event.type != KeyEventType.KeyDown ||
                        (!event.isCtrlPressed && !event.isMetaPressed)
                )
                    false
                else
                    when (event.key) {
                        Key.K -> {
                            commandsVisible = true
                            true
                        }
                        Key.N -> {
                            if (canOperate) startNewTask()
                            true
                        }
                        Key.Comma -> {
                            screen = "settings"
                            true
                        }
                        else -> false
                    }
            }
        ) {
            if (tabletopThread != null && tabletopHinge != null)
                TabletopChat(
                    hingeTop = tabletopHinge.top,
                    hingeBottom = tabletopHinge.bottom,
                    thread = tabletopThread,
                    projectLabel =
                        repository.projects.find { it.id == tabletopThread.projectId }?.title,
                    modelLabel = tabletopThread.modelSelection.model,
                    modeLabel =
                        when (tabletopThread.runtimeMode) {
                            "approval-required" -> "Ask first"
                            "auto-accept-edits" -> "Auto-edit"
                            "auto" -> "Automatic"
                            "full-access" -> "Full access"
                            else -> tabletopThread.runtimeMode
                        },
                    timeline = repository.timeline,
                    pendingRequests = repository.pendingRequests,
                    draft = app.drafts[model.draftKey(tabletopThread.id)] ?: ComposerDraft(),
                    busy = app.busy,
                    canOperate = canOperate,
                    onBack = { model.selectThread(null) },
                    onKeyboard = {
                        tabletopDismissed = true
                        composerInputMode.onChange(false)
                    },
                    onDraftChange = { model.updateDraft(tabletopThread.id, it) },
                    onSend = { model.sendMessage(tabletopThread.id) },
                    onStop = { model.stopThread(tabletopThread.id) },
                    onRespond = { request, decision ->
                        model.respond(tabletopThread.id, request, decision)
                    },
                    onAttach = { model.attach(tabletopThread.id, it) },
                )
            else
            FoldSafeContent(
                avoidVerticalHinges =
                    screen != "workspace" || app.connections.isEmpty() || !app.initialized
            ) { windowAdaptiveInfo ->
                val globalRoutes = listOf("workspace", "tools", "environment", "settings")
                val navigationVisible =
                    app.initialized && app.connections.isNotEmpty() && screen in globalRoutes
                val recommendedType =
                    NavigationSuiteScaffoldDefaults.navigationSuiteType(windowAdaptiveInfo)
                val compactNavigation =
                    recommendedType != NavigationSuiteType.WideNavigationRailCollapsed
                val destinations =
                    listOf(
                        Triple("workspace", "Chats", Icons.Outlined.ChatBubbleOutline),
                        Triple("tools", "Tools", Icons.Outlined.Code),
                        Triple("environment", "Environment", Icons.Outlined.Tune),
                        Triple("settings", "Settings", Icons.Outlined.Settings),
                    )
                LaunchedEffect(compactNavigation) { if (!compactNavigation) drawer.close() }
                ModalNavigationDrawer(
                    drawerState = drawer,
                    gesturesEnabled = compactNavigation && drawer.isOpen,
                    drawerContent = {
                        if (compactNavigation)
                            ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
                                Text(
                                    "Chats",
                                    Modifier.padding(24.dp),
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch { drawer.close() }
                                        startNewTask()
                                    },
                                    enabled = canOperate,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                ) {
                                    Icon(Icons.Outlined.Add, null)
                                    Text("New task", Modifier.padding(start = 8.dp))
                                }
                                destinations.forEach { (route, label, icon) ->
                                    if (route == "settings") Spacer(Modifier.weight(1f))
                                    val enabled =
                                        route == "settings" ||
                                            route == "workspace" ||
                                            app.activeConnectionId != null
                                    NavigationDrawerItem(
                                        label = { Text(label) },
                                        icon = { Icon(icon, null) },
                                        selected = screen == route,
                                        onClick = {
                                            if (enabled) {
                                                screen = route
                                                scope.launch { drawer.close() }
                                            }
                                        },
                                        modifier =
                                            Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                                .semantics { if (!enabled) disabled() },
                                    )
                                }
                                Text(
                                    connection?.baseUrl ?: "No environment selected",
                                    Modifier.padding(24.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                    },
                ) {
                    NavigationSuiteScaffoldLayout(
                        navigationSuiteType =
                            if (navigationVisible && !compactNavigation)
                                NavigationSuiteType.WideNavigationRailCollapsed
                            else NavigationSuiteType.None,
                        navigationSuite = {
                            if (navigationVisible && !compactNavigation)
                                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Column(
                                        Modifier.width(64.dp)
                                            .fillMaxHeight()
                                            .statusBarsPadding()
                                            .navigationBarsPadding(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        IconButton(
                                            onClick = { startNewTask() },
                                            enabled = canOperate,
                                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                                        ) {
                                            Icon(Icons.Outlined.Add, "New task")
                                        }
                                        destinations.forEach { (route, label, icon) ->
                                            if (route == "settings") Spacer(Modifier.weight(1f))
                                            val enabled =
                                                route == "settings" ||
                                                    route == "workspace" ||
                                                    app.activeConnectionId != null
                                            Surface(
                                                onClick = { screen = route },
                                                enabled = enabled,
                                                color =
                                                    if (screen == route)
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                    else Color.Transparent,
                                                shape = RoundedCornerShape(16.dp),
                                                modifier =
                                                    Modifier.width(60.dp)
                                                        .padding(vertical = 4.dp)
                                                        .semantics { selected = screen == route },
                                            ) {
                                                Column(
                                                    Modifier.height(60.dp),
                                                    horizontalAlignment =
                                                        Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center,
                                                ) {
                                                    Icon(icon, null)
                                                    Text(label, fontSize = 10.sp, maxLines = 1)
                                                }
                                            }
                                        }
                                        if (connection != null && !authBlocked)
                                            HostIndicator(
                                                onClick = {
                                                    if (link.phase.offline) connectionHelp = true
                                                    else screen = "settings"
                                                },
                                                label = link.info.host,
                                                modifier = Modifier.padding(vertical = 8.dp),
                                            )
                                    }
                                }
                        },
                    ) {
                        Box {
                            destinationState.SaveableStateProvider(
                                when {
                                    pairingVisible -> "pair:$pairingSession"
                                    screen == "new" ->
                                        "${app.activeConnectionId}:new:$newTaskSession"
                                    else -> "${app.activeConnectionId}:$screen"
                                }
                            ) {
                                when {
                                    !app.initialized ->
                                        Box(
                                            Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                "Opening your workspace…",
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
                                    screen == "cloud" ||
                                        (authBlocked &&
                                            screen !in
                                                setOf(
                                                    "pair",
                                                    "settings",
                                                    "notifications",
                                                    "usage",
                                                    "share",
                                                    "shares",
                                                )) ->
                                        T3ConnectScreen(
                                            model.cloud,
                                            app.connections,
                                            onConnect = { model.saveCloudConnection(it) },
                                            onBack = { screen = "settings" },
                                            onRemoved = { model.forgetCloudEnvironment(it) },
                                        )
                                    screen == "share" && incomingShare != null ->
                                        IncomingShareScreen(
                                            draft = incomingShare,
                                            connections =
                                                app.connections.filter {
                                                    it.cloudAccountId == null ||
                                                        it.cloudAccountId ==
                                                            cloudAccount.identity?.accountId
                                                },
                                            activeConnectionId = app.activeConnectionId,
                                            projects =
                                                repository.projects.filterNot { it.archived },
                                            canImport = canOperate,
                                            busy = app.busy,
                                            error = app.error,
                                            onDraftChange = model::updateIncomingShare,
                                            onConnectionChange = { model.connect(it) },
                                            onImport = { model.importIncomingShare(it) },
                                            onClose = {
                                                model.selectIncomingShare(null)
                                                screen = "shares"
                                            },
                                            onDiscard = {
                                                model.discardIncomingShare(incomingShare.id)
                                            },
                                        )
                                    screen == "shares" || screen == "share" ->
                                        IncomingShareListScreen(
                                            app.incomingShares,
                                            app.stagingShare,
                                            onOpen = model::selectIncomingShare,
                                            onBack = { screen = "settings" },
                                        )
                                    screen == "usage-details" ->
                                        RichUsageScreen(
                                            model.repository,
                                            onBack = { screen = "settings" },
                                            onManageAccounts = { screen = "environment" },
                                            configs = model.usageConfigurations,
                                            environmentLabel =
                                                connection?.label ?: "Current environment",
                                        )
                                    screen == "notifications" ->
                                        AwarenessSettingsScreen(
                                            onBack = { screen = "settings" },
                                            onPreferencesChanged = model.awareness::refresh,
                                            registrationStatus = notificationStatus,
                                        )
                                    screen == "usage" ->
                                        SubscriptionUsageScreen(
                                            usage,
                                            onBack = { screen = "settings" },
                                            onRefresh = { model.refreshUsage() },
                                            onDetails = { screen = "usage-details" },
                                        )
                                    screen == "attachment" &&
                                        openedAttachment != null &&
                                        attachmentConnectionId != null &&
                                        attachmentConnectionId == app.activeConnectionId ->
                                        AttachmentViewerScreen(
                                            model.repository,
                                            Json.decodeFromString<ChatAttachment>(
                                                openedAttachment!!
                                            ),
                                            onBack = { screen = "workspace" },
                                        )
                                    screen == "mcp" &&
                                        openedMcpApp != null &&
                                        selectedThread != null ->
                                        McpAppScreen(
                                            model.repository,
                                            selectedThread.id,
                                            Json.decodeFromString<NativeMcpApp>(openedMcpApp!!),
                                            onBack = { screen = "workspace" },
                                        )
                                    screen == "browser" && selectedThread != null -> {
                                        val captureGeneration =
                                            remember(app.activeConnectionId, selectedThread.id) {
                                                model.repository.connectionGeneration
                                            }
                                        BrowserPreviewScreen(
                                            model.repository,
                                            selectedThread.id,
                                            onBack = { screen = "workspace" },
                                            onAddContext = { record ->
                                                app.activeConnectionId?.let { owner ->
                                                    model.insertComposerContext(
                                                        ComposerContextSelection(
                                                            owner,
                                                            captureGeneration,
                                                            selectedThread.id,
                                                            listOf(record),
                                                        )
                                                    )
                                                }
                                                screen = "workspace"
                                            },
                                        )
                                    }
                                    screen == "device" && selectedThread != null ->
                                        DevicePreviewScreen(
                                            model.repository,
                                            selectedThread.id,
                                            onBack = { screen = "workspace" },
                                        )
                                    screen == "pair" || app.connections.isEmpty() ->
                                        ConnectionScreen(
                                            loading = app.busy,
                                            error = app.error,
                                            onConnect = { url, code ->
                                                model.pair(url, code)
                                            },
                                            onBack =
                                                if (app.connections.isEmpty()) null
                                                else ({ screen = "settings" }),
                                            initialUrl = pairingLink,
                                            onT3Connect = { screen = "cloud" },
                                        )
                                    screen == "settings" ->
                                        SettingsScreen(
                                            theme = app.theme,
                                            onThemeChange = model::setTheme,
                                            connections =
                                                app.connections.filter {
                                                    it.cloudAccountId == null ||
                                                        it.cloudAccountId ==
                                                            cloudAccount.identity?.accountId
                                                },
                                            activeConnectionId = app.activeConnectionId,
                                            onConnectionSelect = {
                                                model.connect(it)
                                                screen = "workspace"
                                            },
                                            onDisconnect = model::disconnect,
                                            onConnectionDelete = model::removeConnection,
                                            onAddConnection = {
                                                startPairing("")
                                            },
                                            onEnvironmentSettings = { screen = "environment" },
                                            onNotifications = { screen = "notifications" },
                                            onUsage = { screen = "usage-details" },
                                            onCommands = { commandsVisible = true },
                                            onIncomingShares = { screen = "shares" },
                                            onT3Connect = { screen = "cloud" },
                                            onBack = { screen = "workspace" },
                                        )
                                    screen == "new" ->
                                        NewConversationScreen(
                                            service = model.repository,
                                            connectionKey = app.activeConnectionId,
                                            repository = repository,
                                            initialProjectId = newTaskProjectId,
                                            draft =
                                                app.drafts[model.draftKey(null)] ?: ComposerDraft(),
                                            busy = app.busy,
                                            busyLabel = app.busyLabel,
                                            canOperate = canOperate,
                                            onDraftChange = { model.updateDraft(null, it) },
                                            onCancelTransfer = model::cancelTransfer,
                                            onAttach = { model.attach(null, it) },
                                            onRemoveAttachment = {
                                                model.removeAttachment(null, it)
                                            },
                                            onAddContext = { project ->
                                                openComposerContext(null, project)
                                            },
                                            onRemoveContext = {
                                                model.removeComposerContext(null, it)
                                            },
                                            enterBehavior = app.preferences.composerEnterBehavior,
                                            onCopyContext = { model.copyComposerContext(null) },
                                            onPasteContext = { model.pasteComposerContext(null) },
                                            onCreate = {
                                                project,
                                                selection,
                                                runtime,
                                                interaction,
                                                workspace ->
                                                model.launchThread(
                                                    project,
                                                    selection,
                                                    runtime,
                                                    interaction,
                                                    workspace,
                                                )
                                            },
                                            onBack = { screen = "workspace" },
                                        )
                                    screen == "project" ->
                                        AddProjectScreen(
                                            busy = app.busy,
                                            canOperate = canOperate,
                                            error = app.error,
                                            onCreate = { title, path ->
                                                model.createProject(title, path)
                                            },
                                            onBack = { screen = "workspace" },
                                            onOpenOnboarding = { screen = "project-onboarding" },
                                        )
                                    screen == "project-onboarding" ->
                                        ProjectOnboardingScreen(
                                            model.repository,
                                            app.activeConnectionId.orEmpty(),
                                            onBack = { screen = "project" },
                                            onProjectReady = { startNewTask(it) },
                                        )
                                    screen == "tools" ->
                                        key(app.activeConnectionId) {
                                            ToolsScreen(
                                                model.repository,
                                                repository.projects,
                                                onBack = { screen = "workspace" },
                                                initialProjectId = toolsProjectId,
                                            )
                                        }
                                    screen == "environment" ->
                                        key(app.activeConnectionId) {
                                            EnvironmentScreen(
                                                model.repository,
                                                repository.projects,
                                                repository.providers,
                                                onBack = { screen = "settings" },
                                                onAdvancedSettings = {
                                                    settingsPage = it
                                                    screen = "advanced-settings"
                                                },
                                            )
                                        }
                                    screen == "advanced-settings" ->
                                        AdvancedSettingsScreen(
                                            model.repository,
                                            onBack = { screen = "environment" },
                                            initialPage = settingsPage,
                                            client =
                                                ClientSettingsControls(
                                                    app.preferences.followUpBehavior,
                                                    app.preferences.projectGrouping,
                                                    app.cacheBytes,
                                                    model::setFollowUpBehavior,
                                                    model::setProjectGrouping,
                                                    { model.clearClientCache() },
                                                    composerEnterBehavior =
                                                        app.preferences.composerEnterBehavior,
                                                    onComposerEnterBehavior =
                                                        model::setComposerEnterBehavior,
                                                    appearance = app.preferences.appearance,
                                                    onAppearanceChange = model::setAppearance,
                                                ),
                                            onProjectRemoved = {
                                                if (selectedThread?.projectId == it)
                                                    model.selectThread(null)
                                            },
                                        )
                                    screen == "history" && selectedThread != null ->
                                        ConversationHistoryScreen(
                                            repository = model.repository,
                                            thread = selectedThread,
                                            onBack = { screen = "workspace" },
                                            onForked = {
                                                model.selectThread(it)
                                                screen = "workspace"
                                            },
                                        )
                                    screen == "thread-settings" && selectedThread != null ->
                                        ThreadSettingsScreen(
                                            repository = model.repository,
                                            thread = selectedThread,
                                            providers = repository.providers,
                                            onBack = { screen = "workspace" },
                                        )
                                    screen == "history-search" && selectedThread != null ->
                                        ThreadSearchScreen(
                                            model.repository,
                                            selectedThread,
                                            onBack = { screen = "workspace" },
                                            onJump = { jump ->
                                                if (
                                                    jump.connectionId == app.activeConnectionId &&
                                                        jump.threadId == selectedThread.id
                                                ) {
                                                    pendingSearchJump = Json.encodeToString(jump)
                                                    screen = "workspace"
                                                }
                                            },
                                        )
                                    screen == "terminal" && selectedThread != null -> {
                                        val project =
                                            repository.projects.find {
                                                it.id == selectedThread.projectId
                                            }
                                        if (project != null) {
                                            val captureGeneration =
                                                remember(
                                                    app.activeConnectionId,
                                                    selectedThread.id,
                                                    terminalId,
                                                ) {
                                                    model.repository.connectionGeneration
                                                }
                                            val session =
                                                remember(
                                                    app.activeConnectionId,
                                                    selectedThread.id,
                                                    selectedThread.worktreePath,
                                                    terminalId,
                                                ) {
                                                    model.repository.terminalSession(
                                                        selectedThread.id,
                                                        terminalId = terminalId,
                                                        cwd = project.workspaceRoot,
                                                        worktreePath = selectedThread.worktreePath,
                                                    )
                                                }
                                            TerminalScreen(
                                                session,
                                                onBack = { screen = "workspace" },
                                                onAddContext = { record ->
                                                    app.activeConnectionId?.let { owner ->
                                                        model.insertComposerContext(
                                                            ComposerContextSelection(
                                                                owner,
                                                                captureGeneration,
                                                                selectedThread.id,
                                                                listOf(record),
                                                            )
                                                        )
                                                    }
                                                    screen = "workspace"
                                                },
                                            )
                                        }
                                    }
                                    else ->
                                        ConversationPaneScaffold(
                                            selectedThreadId = repository.selectedThreadId,
                                            selectionReady = !app.restoringSelection,
                                            adaptiveInfo = windowAdaptiveInfo,
                                            listPane = { showDetail ->
                                                WorkspaceScreen(
                                                    environments =
                                                        workspaceSources.takeIf { it.isNotEmpty() },
                                                    selectedConnectionId = app.activeConnectionId,
                                                    onEnvironmentNewThread = { target ->
                                                        if (target == null) startNewTask()
                                                        else if (
                                                            target.connectionId ==
                                                                app.activeConnectionId
                                                        )
                                                            startNewTask(target.projectId)
                                                        else model.prepareWorkspaceTask(target)
                                                    },
                                                    onEnvironmentThreadOpen = { ref ->
                                                        if (
                                                            ref.connectionId ==
                                                                app.activeConnectionId
                                                        ) {
                                                            model.selectThread(ref.threadId)
                                                            showDetail(ref.threadId)
                                                        } else model.openWorkspaceThread(ref)
                                                    },
                                                    serverConfig = repository.serverConfig,
                                                    projects = repository.projects,
                                                    threads = repository.threads,
                                                    connectionLabel =
                                                        connection?.baseUrl
                                                            ?: "Choose an environment",
                                                    isConnected = connected,
                                                    loading =
                                                        repository.connectionStatus ==
                                                            ConnectionStatus.Connecting ||
                                                            repository.connectionStatus ==
                                                                ConnectionStatus.Reconnecting,
                                                    error = repository.error,
                                                    showArchived = showArchived,
                                                    onShowArchivedChange = { showArchived = it },
                                                    onThreadOpen = {
                                                        screen = "workspace"
                                                        model.selectThread(it.id)
                                                        showDetail(it.id)
                                                    },
                                                    onNewThread = { startNewTask() },
                                                    onSettings = { screen = "settings" },
                                                    onConnect = { screen = "settings" },
                                                    onRefresh = model::reconnect,
                                                    onAddProject = { screen = "project" },
                                                    onTools = {
                                                        toolsProjectId = null
                                                        screen = "tools"
                                                    },
                                                    canCreateThread = canOperate,
                                                    selectedThreadId = selectedThread?.id,
                                                    showNavigationActions = false,
                                                    compactLayout = compactNavigation,
                                                    projectGrouping =
                                                        app.preferences.projectGrouping,
                                                    onOpenNavigation =
                                                        if (compactNavigation)
                                                            ({ scope.launch { drawer.open() } })
                                                        else null,
                                                    bottomEnvironmentComposer = { target ->
                                                        WorkspaceTaskComposer(
                                                            model,
                                                            target,
                                                            workspaceSources.size > 1,
                                                            workspaceSources.find {
                                                                it.connectionId ==
                                                                    target?.connectionId
                                                            },
                                                            app.activeConnectionId,
                                                            onConfigure = {
                                                                if (target == null) startNewTask()
                                                                else if (
                                                                    target.connectionId ==
                                                                        app.activeConnectionId
                                                                )
                                                                    startNewTask(target.projectId)
                                                                else
                                                                    model.prepareWorkspaceTask(
                                                                        target
                                                                    )
                                                            },
                                                            onAddContext = {
                                                                openComposerContext(
                                                                    null,
                                                                    target?.projectId,
                                                                )
                                                            },
                                                        )
                                                    },
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            },
                                            detailPane = { showList ->
                                                if (selectedThread != null) {
                                                    threadState.SaveableStateProvider(
                                                        "${app.activeConnectionId}:${selectedThread.id}"
                                                    ) {
                                                        Column(
                                                            Modifier.fillMaxSize().statusBarsPadding()
                                                        ) {
                                                            Row(
                                                                Modifier.fillMaxWidth(),
                                                                verticalAlignment =
                                                                    Alignment.CenterVertically,
                                                            ) {
                                                                Box(Modifier.weight(1f)) {
                                                                    ThreadStatusPanel(
                                                                        model.repository,
                                                                        selectedThread,
                                                                        onOpenThread =
                                                                            model::selectThread,
                                                                    )
                                                                }
                                                                Box(
                                                                    Modifier.widthIn(max = 160.dp)
                                                                ) {
                                                                    ProjectActionsMenu(
                                                                        model.repository,
                                                                        selectedThread,
                                                                        onOpenTerminal = {
                                                                            if (
                                                                                model.repository
                                                                                    .connectionId ==
                                                                                    app
                                                                                        .activeConnectionId &&
                                                                                    model.repository
                                                                                        .state
                                                                                        .value
                                                                                        .selectedThreadId ==
                                                                                        selectedThread
                                                                                            .id
                                                                            ) {
                                                                                terminalId = it
                                                                                screen = "terminal"
                                                                            }
                                                                        },
                                                                    )
                                                                }
                                                            }
                                                            Box(Modifier.heightIn(max = 240.dp)) {
                                                                WorktreeSetupPanel(
                                                                    model.repository,
                                                                    selectedThread.id,
                                                                    onOpenTerminal = {
                                                                        terminalId = it
                                                                        screen = "terminal"
                                                                    },
                                                                    onWorkLocally =
                                                                        if (canOperate && !app.busy)
                                                                            ({
                                                                                model.workLocally(
                                                                                    selectedThread
                                                                                        .id
                                                                                )
                                                                            })
                                                                        else null,
                                                                )
                                                            }
                                                            ChatScreen(
                                                                renderMcpApp = { item ->
                                                                    item.mcpApp?.let { descriptor ->
                                                                        InlineMcpApp(
                                                                            model.repository,
                                                                            selectedThread.id,
                                                                            descriptor,
                                                                            onFullscreen =
                                                                                if (
                                                                                    repository
                                                                                        .pendingRequests
                                                                                        .isEmpty()
                                                                                )
                                                                                    ({
                                                                                        openedMcpApp =
                                                                                            Json
                                                                                                .encodeToString(
                                                                                                    descriptor
                                                                                                )
                                                                                        screen =
                                                                                            "mcp"
                                                                                    })
                                                                                else null,
                                                                        )
                                                                    }
                                                                },
                                                                onOpenMcpApp = { item ->
                                                                    item.mcpApp?.let {
                                                                        openedMcpApp =
                                                                            Json.encodeToString(it)
                                                                        screen = "mcp"
                                                                    }
                                                                },
                                                                onBrowserPreview = {
                                                                    screen = "browser"
                                                                },
                                                                onDevicePreview = {
                                                                    screen = "device"
                                                                },
                                                                embedded = !compactNavigation,
                                                                thread = selectedThread,
                                                                timeline = repository.timeline,
                                                                pendingRequests =
                                                                    repository.pendingRequests,
                                                                loading = repository.threadLoading,
                                                                hasOlderMessages =
                                                                    repository.hasOlderMessages,
                                                                historyLoading =
                                                                    repository.historyLoading,
                                                                historyRevision =
                                                                    repository.historyRevision,
                                                                draft =
                                                                    app.drafts[
                                                                            model.draftKey(
                                                                                selectedThread.id
                                                                            )] ?: ComposerDraft(),
                                                                busy = app.busy,
                                                                busyLabel = app.busyLabel,
                                                                canOperate = canOperate,
                                                                connected = connected,
                                                                error = repository.error,
                                                                onBack = showList,
                                                                onDraftChange = {
                                                                    model.updateDraft(
                                                                        selectedThread.id,
                                                                        it,
                                                                    )
                                                                },
                                                                onCancelTransfer =
                                                                    model::cancelTransfer,
                                                                onAttach = {
                                                                    model.attach(
                                                                        selectedThread.id,
                                                                        it,
                                                                    )
                                                                },
                                                                onRemoveAttachment = {
                                                                    model.removeAttachment(
                                                                        selectedThread.id,
                                                                        it,
                                                                    )
                                                                },
                                                                onOpenAttachment = { attachment ->
                                                                    attachmentConnectionId =
                                                                        app.activeConnectionId
                                                                    openedAttachment =
                                                                        Json.encodeToString(
                                                                            attachment
                                                                        )
                                                                    screen = "attachment"
                                                                },
                                                                onHistory = { screen = "history" },
                                                                onTools = {
                                                                    toolsProjectId =
                                                                        selectedThread.projectId
                                                                    screen = "tools"
                                                                },
                                                                onSettings = {
                                                                    screen = "thread-settings"
                                                                },
                                                                onTerminal =
                                                                    if (
                                                                        repository.projects.any {
                                                                            it.id ==
                                                                                selectedThread
                                                                                    .projectId
                                                                        } &&
                                                                            "terminal:read" in
                                                                                repository.scopes
                                                                    )
                                                                        ({
                                                                            terminalId = "term-1"
                                                                            screen = "terminal"
                                                                        })
                                                                    else null,
                                                                onSend = {
                                                                    if (connected)
                                                                        model.sendMessage(
                                                                            selectedThread.id
                                                                        )
                                                                    else if (canQueue)
                                                                        model.queueMessage(
                                                                            selectedThread.id
                                                                        )
                                                                },
                                                                canQueue = canQueue,
                                                                pendingReplies = app.pendingReplies.keys,
                                                                projectLabel =
                                                                    repository.projects
                                                                        .find {
                                                                            it.id ==
                                                                                selectedThread
                                                                                    .projectId
                                                                        }
                                                                        ?.title,
                                                                queued =
                                                                    app.outbox[
                                                                            model.draftKey(
                                                                                selectedThread.id
                                                                            )]
                                                                        .orEmpty(),
                                                                onEditQueued = {
                                                                    model.unqueueMessage(
                                                                        selectedThread.id,
                                                                        it,
                                                                    )
                                                                },
                                                                onStop = {
                                                                    model.stopThread(
                                                                        selectedThread.id
                                                                    )
                                                                },
                                                                onArchive = {
                                                                    model.archive(
                                                                        selectedThread.id,
                                                                        !selectedThread.archived,
                                                                    )
                                                                },
                                                                onRespond = { request, decision ->
                                                                    model.respond(
                                                                        selectedThread.id,
                                                                        request,
                                                                        decision,
                                                                    )
                                                                },
                                                                onAnswer = { request, answers ->
                                                                    model.answer(
                                                                        selectedThread.id,
                                                                        request,
                                                                        answers,
                                                                    )
                                                                },
                                                                onReconnect = model::reconnect,
                                                                onLoadOlder = model::loadOlder,
                                                                onSearchHistory =
                                                                    if (
                                                                        connected &&
                                                                            "orchestration:read" in
                                                                                repository.scopes
                                                                    )
                                                                        ({
                                                                            screen =
                                                                                "history-search"
                                                                        })
                                                                    else null,
                                                                searchJump = searchJump,
                                                                onSearchJumpFinished = { id, error
                                                                    ->
                                                                    if (searchJump?.id == id)
                                                                        pendingSearchJump = null
                                                                    error?.let {
                                                                        scope.launch {
                                                                            snackbar.showSnackbar(
                                                                                it
                                                                            )
                                                                        }
                                                                    }
                                                                },
                                                                onLoadThrough =
                                                                    model::loadHistoryThrough,
                                                                enterBehavior =
                                                                    app.preferences
                                                                        .composerEnterBehavior,
                                                                onAddContext = {
                                                                    openComposerContext(
                                                                        selectedThread.id,
                                                                        selectedThread.projectId,
                                                                    )
                                                                },
                                                                onRemoveContext = {
                                                                    model.removeComposerContext(
                                                                        selectedThread.id,
                                                                        it,
                                                                    )
                                                                },
                                                                onCopyContext = {
                                                                    model.copyComposerContext(
                                                                        selectedThread.id
                                                                    )
                                                                },
                                                                onPasteContext = {
                                                                    model.pasteComposerContext(
                                                                        selectedThread.id
                                                                    )
                                                                },
                                                                modifier = Modifier.weight(1f),
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    Box(
                                                        Modifier.fillMaxSize(),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Column(
                                                            horizontalAlignment =
                                                                Alignment.CenterHorizontally,
                                                            verticalArrangement =
                                                                Arrangement.spacedBy(12.dp),
                                                        ) {
                                                            Text(
                                                                "Your workspace, wherever you are.",
                                                                style =
                                                                    MaterialTheme.typography
                                                                        .headlineSmall,
                                                            )
                                                            Text(
                                                                "Open a conversation or start something new.",
                                                                color =
                                                                    MaterialTheme.colorScheme
                                                                        .onSurfaceVariant,
                                                            )
                                                            Button(
                                                                onClick = { startNewTask() },
                                                                enabled = canOperate,
                                                            ) {
                                                                Text("New task")
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                }
                            }
                            SnackbarHost(
                                snackbar,
                                Modifier.align(Alignment.BottomCenter).padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
        contextTarget?.let { target ->
            ComposerContextPicker(
                model.repository,
                repository.projects.find { it.id == target.projectId },
                repository.threads.find { it.id == target.threadId },
                onAdd = { selection ->
                    if (
                        selection.connectionId == target.connectionId &&
                            selection.ownerThreadId == target.threadId
                    )
                        model.insertComposerContext(selection)
                    contextTargetJson = null
                },
                onDismiss = { contextTargetJson = null },
            )
        }
        if (commandsVisible)
            CommandPalette(
                listOf(
                    AppCommand("new", "New task", "Ctrl/Cmd+N", canOperate) { startNewTask() },
                    AppCommand("chats", "Open chats") { screen = "workspace" },
                    AppCommand("tools", "Files, Git and pull requests", enabled = connected) {
                        screen = "tools"
                    },
                    AppCommand(
                        "terminal",
                        "Open terminal",
                        enabled = selectedThread != null && "terminal:read" in repository.scopes,
                    ) {
                        terminalId = "term-1"
                        screen = "terminal"
                    },
                    AppCommand(
                        "browser",
                        "Open browser preview",
                        enabled = selectedThread != null && connected,
                    ) {
                        screen = "browser"
                    },
                    AppCommand(
                        "device",
                        "Open device preview",
                        enabled = selectedThread != null && connected,
                    ) {
                        screen = "device"
                    },
                    AppCommand(
                        "history",
                        "Conversation history",
                        enabled = selectedThread != null,
                    ) {
                        screen = "history"
                    },
                    AppCommand(
                        "search-history",
                        "Find in chat",
                        enabled =
                            connected &&
                                selectedThread != null &&
                                "orchestration:read" in repository.scopes,
                    ) {
                        screen = "history-search"
                    },
                    AppCommand(
                        "stop",
                        "Stop current agent",
                        enabled = canOperate && selectedThread?.activeRunId != null,
                    ) {
                        selectedThread?.let { model.stopThread(it.id) }
                    },
                    AppCommand("settings", "Open settings", "Ctrl/Cmd+,") { screen = "settings" },
                    AppCommand("connect", "T3 Connect") { screen = "cloud" },
                    AppCommand("notifications", "Notifications and widget") {
                        screen = "notifications"
                    },
                    AppCommand("usage", "Usage and subscription limits") {
                        screen = "usage-details"
                    },
                    AppCommand("shares", "Shared drafts") { screen = "shares" },
                    AppCommand("clone", "Clone or create a project", enabled = connected) {
                        screen = "project-onboarding"
                    },
                ),
                onDismiss = { commandsVisible = false },
            )
    }
  }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTaskComposer(
    model: AppViewModel,
    target: WorkspaceProjectRef?,
    showEnvironment: Boolean,
    source: WorkspaceEnvironmentShell?,
    foregroundConnectionId: String?,
    onConfigure: () -> Unit,
    onAddContext: () -> Unit,
) {
    val app by model.state.collectAsStateWithLifecycle()
    val foreground = target?.connectionId == foregroundConnectionId
    val current by model.repository.state.collectAsStateWithLifecycle()
    val project =
        source?.projects?.find { it.id == target?.projectId }
            ?: current.projects.find { foreground && it.id == target?.projectId }
    val config = source?.serverConfig ?: current.serverConfig.takeIf { foreground }
    val defaults = effectiveProjectDefaults(config, project)
    val inherited = defaults.modelSelection?.let(::parseModelSelection)
    val name =
        current.providers
            .find { foreground && it.instanceId == inherited?.instanceId }
            ?.models
            ?.find { it.slug == inherited?.model }
            ?.name ?: inherited?.model ?: "Choose model"
    val owner = app.connections.find { it.id == target?.connectionId }
    val scopes = source?.scopes?.takeIf { it.isNotEmpty() } ?: owner?.scopes.orEmpty()
    val draft = app.drafts["${target?.connectionId.orEmpty()}:new"] ?: ComposerDraft()
    val canConfigure = target != null && "orchestration:operate" in scopes && !app.busy
    TaskComposer(
        draft,
        onDraftChange = { text -> target?.let { model.updateWorkspaceDraft(it, text) } },
        onSubmit = onConfigure,
        onAttach = { if (foreground) model.attach(null, it) },
        onRemoveAttachment = { if (foreground) model.removeAttachment(null, it) },
        actionsEnabled =
            foreground && canConfigure && current.connectionStatus == ConnectionStatus.Connected,
        attachmentRemovalEnabled = foreground && !app.busy,
        submitEnabled = canConfigure && (draft.text.isNotBlank() || draft.attachments.isNotEmpty()),
        busyLabel = app.busyLabel,
        onCancelTransfer = model::cancelTransfer,
        modelLabel = name,
        modeLabel =
            when (defaults.runtimeMode) {
                "full-access" -> "Full access"
                "auto" -> "Automatic"
                "auto-accept-edits" -> "Auto-edit"
                else -> "Ask first"
            },
        onModelOptions = onConfigure,
        onModeOptions = onConfigure,
        placeholder = "Describe a task for the agent…",
        submitDescription = "Configure and start task",
        textFieldTag = "new-task-quick-composer",
        projectLabel =
            listOfNotNull(project?.title, source?.label?.takeIf { showEnvironment })
                .joinToString(" · ")
                .ifEmpty { null },
        voiceHint = "Tap to start a task  ·  hold to talk",
        voiceTarget = "new task",
        onAddContext = onAddContext.takeIf { foreground },
        onRemoveContext = { if (foreground) model.removeComposerContext(null, it) },
        enterBehavior = app.preferences.composerEnterBehavior,
        onCopyContext = if (foreground) ({ model.copyComposerContext(null) }) else null,
        onPasteContext = if (foreground) ({ model.pasteComposerContext(null) }) else null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewConversationScreen(
    service: T3Repository,
    connectionKey: String?,
    repository: RepositoryState,
    initialProjectId: String?,
    draft: ComposerDraft,
    busy: Boolean,
    busyLabel: String?,
    canOperate: Boolean,
    onDraftChange: (String) -> Unit,
    onCancelTransfer: () -> Unit,
    onAttach: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onAddContext: (String?) -> Unit,
    onRemoveContext: (String) -> Unit,
    enterBehavior: String,
    onCopyContext: () -> Unit,
    onPasteContext: () -> Unit,
    onCreate: (String, ModelSelection, String, String, JsonObject) -> Unit,
    onBack: () -> Unit,
) {
    var projectId by rememberSaveable { mutableStateOf(initialProjectId) }
    val projects = repository.projects.filterNot { it.archived }
    val project = projects.find { it.id == projectId } ?: projects.firstOrNull()
    var instanceId by rememberSaveable(connectionKey, project?.id) { mutableStateOf<String?>(null) }
    var modelSlug by rememberSaveable(connectionKey, project?.id) { mutableStateOf<String?>(null) }
    var runtimeOverride by
        rememberSaveable(connectionKey, project?.id) { mutableStateOf<String?>(null) }
    var interactionMode by
        rememberSaveable(connectionKey, project?.id) { mutableStateOf("default") }
    var workspaceOverride by
        rememberSaveable(connectionKey, project?.id) { mutableStateOf<String?>(null) }
    var workspaceValueOverride by
        rememberSaveable(connectionKey, project?.id) { mutableStateOf<String?>(null) }
    var branch by rememberSaveable(connectionKey, project?.id) { mutableStateOf("") }
    var projectFile by remember(connectionKey, project?.id) { mutableStateOf<JsonObject?>(null) }
    var defaultsLoading by remember(connectionKey, project?.id) { mutableStateOf(false) }
    var defaultsError by remember(connectionKey, project?.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(connectionKey, project?.id, repository.connectionStatus) {
        val target = project ?: return@LaunchedEffect
        if (
            repository.connectionStatus != ConnectionStatus.Connected ||
                "filesystem:read" !in repository.scopes
        )
            return@LaunchedEffect
        defaultsLoading = true
        defaultsError = null
        try {
            projectFile = ProjectDefaultsTools(service).projectFile(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            defaultsError = error.message ?: "Project defaults could not be read."
        } finally {
            defaultsLoading = false
        }
    }
    val defaults = effectiveProjectDefaults(repository.serverConfig, project, projectFile)
    val inheritedModel = defaults.modelSelection?.let(::parseModelSelection)
    val defaultWorkspace =
        resolvedNewTaskWorkspaceMode(
            repository.serverConfig,
            project,
            repository.threads.any { it.projectId == project?.id },
            projectFile,
        )
    val workspaceType =
        workspaceOverride ?: if (defaultWorkspace == "worktree") "worktree" else "root"
    val workspaceValue = workspaceValueOverride ?: if (workspaceType == "worktree") "HEAD" else ""
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var projectOptionsOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val allRuntimeModes =
        listOf(
            "approval-required" to "Ask for approval",
            "auto-accept-edits" to "Automatically accept edits",
            "auto" to "Automatic",
            "full-access" to "Full access",
        )
    val ceiling =
        repository.serverConfig
            ?.obj("managedPolicy")
            ?.obj("limits")
            ?.nullableString("maxRuntimeMode")
    val ceilingIndex = allRuntimeModes.indexOfFirst { it.first == ceiling }
    val runtimeModes =
        if (ceilingIndex >= 0) allRuntimeModes.take(ceilingIndex + 1) else allRuntimeModes
    val runtimeMode =
        runtimeOverride?.takeIf { mode -> runtimeModes.any { it.first == mode } }
            ?: defaults.runtimeMode
    val allowedModels = repository.serverConfig?.let { projectDefaultModelOptions(it, project?.id) }
    val providers =
        repository.providers
            .filter { it.available }
            .map { provider ->
                provider.copy(
                    models =
                        provider.models.filter { model ->
                            allowedModels == null ||
                                allowedModels.any {
                                    it.instanceId == provider.instanceId && it.model == model.slug
                                }
                        }
                )
            }
            .filter { it.models.isNotEmpty() }
    val provider =
        providers.find { it.instanceId == (instanceId ?: inheritedModel?.instanceId) }
            ?: providers.firstOrNull()
    val selectedModel =
        provider?.models?.find {
            it.slug ==
                (modelSlug
                    ?: inheritedModel?.takeIf { it.instanceId == provider.instanceId }?.model)
        } ?: provider?.models?.find { it.isDefault } ?: provider?.models?.firstOrNull()
    var optionsOverride by
        rememberSaveable(connectionKey, project?.id, provider?.instanceId, selectedModel?.slug) {
            mutableStateOf<String?>(null)
        }
    val selectedOptions =
        optionsOverride?.let { Json.parseToJsonElement(it) as JsonArray }
            ?: inheritedModel
                ?.takeIf {
                    it.instanceId == provider?.instanceId && it.model == selectedModel?.slug
                }
                ?.options

    val submit = {
        if (project != null && provider != null && selectedModel != null)
            onCreate(
                project.id,
                ModelSelection(provider.instanceId, selectedModel.slug, selectedOptions),
                runtimeMode,
                interactionMode,
                buildJsonObject {
                    put("type", workspaceType)
                    if (workspaceType == "worktree") {
                        put("baseRef", workspaceValue.trim())
                        put("startFromOrigin", defaults.startFromOrigin)
                        if (branch.isNotBlank()) put("branch", branch.trim())
                    }
                    if (workspaceType == "existing_worktree")
                        put("worktreePath", workspaceValue.trim())
                },
            )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New task") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, "Close new task") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreHoriz, "New task options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Task settings") },
                                onClick = {
                                    menuOpen = false
                                    showAdvanced = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Clear message") },
                                enabled = !busy && draft.text.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    onDraftChange("")
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            TaskComposer(
                enterBehavior = enterBehavior,
                onCopyContext = onCopyContext,
                onPasteContext = onPasteContext,
                draft = draft,
                onDraftChange = onDraftChange,
                onSubmit = submit,
                onAttach = onAttach,
                onRemoveAttachment = onRemoveAttachment,
                onAddContext = { onAddContext(project?.id) },
                onRemoveContext = onRemoveContext,
                modifier = Modifier.navigationBarsPadding().imePadding(),
                actionsEnabled = canOperate && !busy,
                attachmentRemovalEnabled = !busy,
                submitEnabled =
                    !busy &&
                        canOperate &&
                        project != null &&
                        selectedModel != null &&
                        (!defaultsLoading || workspaceOverride != null) &&
                        (draft.text.isNotBlank() || draft.attachments.isNotEmpty()) &&
                        (workspaceType == "root" || workspaceValue.isNotBlank()),
                busyLabel = busyLabel,
                onCancelTransfer = onCancelTransfer,
                modelLabel = selectedModel?.name ?: "Choose model",
                modeLabel = runtimeModes.first { it.first == runtimeMode }.second,
                onModelOptions = { showAdvanced = !showAdvanced },
                onModeOptions = { showAdvanced = !showAdvanced },
                placeholder = "Describe a task for the agent…",
                submitDescription = "Start task",
                textFieldTag = "new-task-composer",
                projectLabel = project?.title,
                voiceHint = "Tap to start a task  ·  hold to talk",
                voiceTarget = "new task",
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom),
            ) {
                Text("What should the\nagent do?", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Give it a task. Pick up where you left off.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        FilterChip(
                            selected = true,
                            onClick = { projectOptionsOpen = true },
                            label = {
                                Text(
                                    project?.title ?: "Choose project",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = { Icon(Icons.Default.ExpandMore, null) },
                        )
                        DropdownMenu(
                            expanded = projectOptionsOpen,
                            onDismissRequest = { projectOptionsOpen = false },
                        ) {
                            projects.forEach { item ->
                                DropdownMenuItem(
                                    text = { Text(item.title) },
                                    onClick = {
                                        projectId = item.id
                                        projectOptionsOpen = false
                                    },
                                )
                            }
                        }
                    }
                    FilterChip(
                        selected = workspaceType != "root",
                        onClick = { showAdvanced = true },
                        label = {
                            Text(
                                when (workspaceType) {
                                    "worktree" -> "New worktree"
                                    "existing_worktree" -> "Worktree"
                                    else -> "Project folder"
                                }
                            )
                        },
                    )
                }
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Hide task settings" else "Task settings")
                    Icon(Icons.Default.ExpandMore, null)
                }
                if (defaultsLoading)
                    Text("Reading project defaults…", style = MaterialTheme.typography.bodySmall)
                defaultsError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (showAdvanced) {
                    ChoiceField(
                        "Provider",
                        provider?.displayName ?: "No providers available",
                        providers.map { it.instanceId to it.displayName },
                    ) {
                        instanceId = it
                        modelSlug = null
                    }
                    ChoiceField(
                        "Model",
                        selectedModel?.name ?: "No models available",
                        provider?.models.orEmpty().map { it.slug to it.name },
                    ) {
                        modelSlug = it
                    }
                    selectedModel?.let { model ->
                        ModelOptionControls(model, selectedOptions, enabled = !busy && canOperate) {
                            optionsOverride = (it ?: JsonArray(emptyList())).toString()
                        }
                    }
                    ChoiceField(
                        "Agent permissions",
                        runtimeModes.first { it.first == runtimeMode }.second,
                        runtimeModes,
                    ) {
                        runtimeOverride = it
                    }
                    ChoiceField(
                        "Agent mode",
                        if (interactionMode == "plan") "Plan" else "Build",
                        listOf("default" to "Build", "plan" to "Plan"),
                    ) {
                        interactionMode = it
                    }
                    ChoiceField(
                        "Workspace",
                        when (workspaceType) {
                            "worktree" -> "New worktree"
                            "existing_worktree" -> "Existing worktree"
                            else -> "Project folder"
                        },
                        listOf(
                            "root" to "Project folder",
                            "worktree" to "New worktree",
                            "existing_worktree" to "Existing worktree",
                        ),
                    ) {
                        workspaceOverride = it
                        workspaceValueOverride = null
                    }
                    if (workspaceType != "root")
                        OutlinedTextField(
                            value = workspaceValue,
                            onValueChange = { workspaceValueOverride = it },
                            label = {
                                Text(
                                    if (workspaceType == "worktree") "Base branch or commit"
                                    else "Worktree folder on the environment"
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    if (workspaceType == "worktree")
                        OutlinedTextField(
                            value = branch,
                            onValueChange = { branch = it },
                            label = { Text("New branch (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                }
                if (!canOperate)
                    Text(
                        "Reconnect with permission to run agents to start a conversation.",
                        color = MaterialTheme.colorScheme.error,
                    )
                if (providers.isEmpty())
                    Text("Configure a provider in your environment before starting a conversation.")
            }
        }
    }
}

@Composable
internal fun ChoiceField(
    label: String,
    value: String,
    choices: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = choices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(value, Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, "Show options")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** What the desktop is called in the UI: its saved name, else its host name. */
internal fun hostLabel(connection: SavedConnection): String =
    connection.label?.takeIf { it.isNotBlank() }
        ?: runCatching { java.net.URI(connection.baseUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "desktop"

internal fun hostAddress(connection: SavedConnection): String? =
    runCatching {
            val uri = java.net.URI(connection.baseUrl)
            uri.host?.let { host -> if (uri.port > 0) "$host:${uri.port}" else host }
        }
        .getOrNull()
