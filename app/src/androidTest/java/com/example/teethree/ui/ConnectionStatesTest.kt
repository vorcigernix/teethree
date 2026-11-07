package com.example.teethree.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import com.example.teethree.data.ApprovalOption
import com.example.teethree.data.ComposerDraft
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.ModelSelection
import com.example.teethree.data.RuntimeRequest
import com.example.teethree.data.T3Project
import com.example.teethree.data.T3Thread
import com.example.teethree.data.TimelineItem
import com.example.teethree.ui.adaptive.ConversationPaneScaffold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectionStatesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()

    private fun thread(id: String, title: String, attention: Boolean = false, running: Boolean = false) =
        T3Thread(
            id = id,
            projectId = "showcase",
            title = title,
            status = if (running) "running" else "completed",
            modelSelection = ModelSelection("codex", "Codex · high"),
            activeRunId = if (running) "run" else null,
            archived = false,
            preview = "",
            updatedAt = "2026-11-06T10:00:00Z",
            needsAttention = attention,
        )

    private val threads =
        listOf(
            thread("clamp", "Add clamp helper to math module", attention = true),
            thread("ws", "Fix flaky WebSocket reconnect", running = true),
        ) + (1..10).map { thread("t$it", "Earlier task number $it") }

    private fun item(id: String, type: String, text: String = "", title: String? = null) =
        TimelineItem(id, type, text, title, "completed", 0, streaming = false)

    private val timeline =
        listOf(
            item("u", "user", "Add a clamp helper next to sum and cover it with tests."),
            item("r1", "tool", title = "Read package.json"),
            item("r2", "tool", title = "Read src/math.ts"),
            item("e1", "file_change", "src/math.ts"),
            item("a", "assistant", "Done — clamp() sits next to sum(), and all six tests pass. " +
                "I need your OK to write two files. ".repeat(4)),
            item("u2", "user", "Great. Keep the API small and document the edge cases."),
            item("a2", "assistant", "Sure. " + "The helper takes min and max and clamps the value. ".repeat(6)),
        )

    private val approval =
        RuntimeRequest(
            "request",
            "approval",
            "Write 2 files?",
            canRespond = true,
            options = listOf(ApprovalOption("accept", "Allow once"), ApprovalOption("decline", "Decline")),
            questions = emptyList(),
        )

    private fun link(phase: LinkPhase, queued: Int = 0) =
        LinkUi({}, {}).apply {
            info =
                LinkInfo(
                    status =
                        if (phase == LinkPhase.Online || phase == LinkPhase.Syncing)
                            ConnectionStatus.Connected
                        else ConnectionStatus.Reconnecting,
                    host = "devbox",
                    address = "192.168.1.24:13773",
                    attempts = if (phase == LinkPhase.Unreachable) 6 else 2,
                    offlineSince =
                        when (phase) {
                            LinkPhase.Connecting -> now - 5_000
                            LinkPhase.Unreachable -> now - 80_000
                            else -> null
                        },
                    lastLiveAt = now - 42_000,
                    nextRetryAt = now + 12_000,
                    syncing = phase == LinkPhase.Syncing,
                    queued = queued,
                )
            this.now = this@ConnectionStatesTest.now
        }

    private val selected = mutableStateOf<String?>(null)
    private val sent = mutableStateListOf<String>()

    private fun show(link: LinkUi, connected: Boolean, queued: List<ComposerDraft> = emptyList()) {
        compose.setContent {
            T3Theme(AppTheme.Light) {
                CompositionLocalProvider(
                    LocalLink provides link,
                    LocalComposerInputMode provides ComposerInputMode(voice = true, onChange = {}),
                ) {
                    Box(Modifier.size(412.dp, 915.dp)) {
                        ConversationPaneScaffold(
                            selectedThreadId = selected.value,
                            adaptiveInfo =
                                WindowAdaptiveInfo(
                                    WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(412f, 915f),
                                    Posture(),
                                ),
                            listPane = { open ->
                                WorkspaceScreen(
                                    projects = listOf(T3Project("showcase", "showcase", "/repo")),
                                    threads = threads,
                                    connectionLabel = "devbox",
                                    isConnected = connected,
                                    loading = false,
                                    error = null,
                                    showArchived = false,
                                    onShowArchivedChange = {},
                                    onThreadOpen = {
                                        selected.value = it.id
                                        open(it.id)
                                    },
                                    onNewThread = {},
                                    onSettings = {},
                                    onConnect = {},
                                    onRefresh = {},
                                    bottomComposer = { project ->
                                        TaskComposer(
                                            draft = ComposerDraft(),
                                            onDraftChange = {},
                                            onSubmit = {},
                                            onAttach = {},
                                            onRemoveAttachment = {},
                                            projectLabel = project?.title,
                                            modelLabel = "Codex · high",
                                            voiceHint = "Tap to start a task  ·  hold to talk",
                                        )
                                    },
                                )
                            },
                            detailPane = { back ->
                                ChatScreen(
                                    thread = threads.first(),
                                    timeline = timeline,
                                    pendingRequests = listOf(approval),
                                    loading = false,
                                    hasOlderMessages = false,
                                    historyLoading = false,
                                    historyRevision = 0,
                                    draft = ComposerDraft(),
                                    busy = false,
                                    busyLabel = null,
                                    canOperate = connected,
                                    connected = connected,
                                    error = null,
                                    onBack = back,
                                    onDraftChange = {},
                                    onCancelTransfer = {},
                                    onAttach = {},
                                    onRemoveAttachment = {},
                                    onOpenAttachment = {},
                                    onTools = {},
                                    onSettings = {},
                                    onSend = { sent += "send" },
                                    onStop = {},
                                    onArchive = {},
                                    onRespond = { _, _ -> },
                                    onAnswer = { _, _ -> },
                                    onReconnect = {},
                                    onLoadOlder = {},
                                    canQueue = !connected,
                                    projectLabel = "showcase",
                                    queued = queued,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    @Test
    fun onlinePhoneSharesOneFloatingDock() {
        show(link(LinkPhase.Online), connected = true)
        compose.onAllNodesWithTag("floating-dock").assertCountEquals(1)
        compose.onNodeWithText("New task in", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("connection-status").assertDoesNotExist()
        // The dot sits inside the host button, which merges its children.
        compose.onNodeWithTag("host-dot", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Add clamp helper to math module").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("floating-dock").assertCountEquals(1)
    }

    @Test
    fun connectingIsQuietAndNothingLocks() {
        selected.value = "clamp"
        show(
            link(LinkPhase.Connecting, queued = 1),
            connected = false,
            queued = listOf(ComposerDraft(text = "Also run the linter before you finish.")),
        )
        compose.onNodeWithText("Connecting to devbox…").assertIsDisplayed()
        // The approval stays usable; the reply goes out once connected.
        compose.onNodeWithText("Allow once").assertIsEnabled()
        compose.onNodeWithText("Sends when connected").assertIsDisplayed()
        // The end of the chat sits above the floating dock, not behind it.
        compose.onNodeWithTag("queued-message").assertIsDisplayed()
        val dockTop = compose.onNodeWithTag("floating-dock").fetchSemanticsNode().boundsInRoot.top
        val queuedBottom =
            compose.onNodeWithTag("queued-message").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(queuedBottom <= dockTop + 1)
    }

    @Test
    fun takingLongOffersRetryInline() {
        show(link(LinkPhase.Unreachable, queued = 2), connected = false)
        compose.onNodeWithText("Can't reach devbox").assertIsDisplayed()
        compose.onNodeWithText("· Retry").assertIsDisplayed()
        compose.onNodeWithContentDescription("devbox, can't reach it").assertIsDisplayed()
    }

    @Test
    fun helpSheetTellsPhoneFromDesktop() {
        compose.setContent {
            T3Theme(AppTheme.Light) {
                CompositionLocalProvider(LocalLink provides link(LinkPhase.Unreachable, queued = 2)) {
                    ConnectionHelpSheet(onDismiss = {}, onSwitchHost = {}, onRePair = {})
                }
            }
        }
        compose.onNodeWithTag("connection-help").assertIsDisplayed()
        compose.onNodeWithText("Waiting for devbox").assertIsDisplayed()
        compose.onNodeWithText("Retry now").assertIsDisplayed()
    }

    @Test
    fun syncingThenConnectedSaysNothingExtra() {
        // Syncing is only announced after a visible wait; a fast connect goes straight to normal.
        val link = link(LinkPhase.Connecting)
        show(link, connected = true)
        compose.onNodeWithText("Connecting to devbox…").assertIsDisplayed()
        link.info = link(LinkPhase.Syncing).info
        compose.onNodeWithText("Syncing…").assertIsDisplayed()
        compose.onNodeWithText("Back online").assertDoesNotExist()
        assertEquals(0, sent.size)
    }
}
