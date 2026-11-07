package com.example.teethree.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.example.teethree.data.ModelSelection
import com.example.teethree.data.T3Project
import com.example.teethree.data.T3Thread
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettledListTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun thread(id: String, title: String, settled: Boolean = false, running: Boolean = false) =
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
            settled = settled,
        )

    private val calls = mutableStateListOf<Pair<String, Boolean>>()

    private fun show(threads: List<T3Thread>) {
        compose.setContent {
            T3Theme(AppTheme.Light) {
                Box(Modifier.size(412.dp, 915.dp)) {
                    WorkspaceScreen(
                        projects = listOf(T3Project("showcase", "showcase", "/repo")),
                        threads = threads,
                        connectionLabel = "devbox",
                        isConnected = true,
                        loading = false,
                        error = null,
                        showArchived = false,
                        onShowArchivedChange = {},
                        onThreadOpen = {},
                        onNewThread = {},
                        onSettings = {},
                        onConnect = {},
                        onRefresh = {},
                        canSettle = { true },
                        onSetSettled = { _, thread, settled -> calls += thread.id to settled },
                    )
                }
            }
        }
    }

    @Test
    fun currentAndSettledAreSeparateTabs() {
        show(
            listOf(
                thread("live", "Fix flaky WebSocket reconnect", running = true),
                thread("done", "Add clamp helper"),
                thread("old", "Rename the math module", settled = true),
            )
        )
        compose.onNodeWithText("Add clamp helper").assertIsDisplayed()
        compose.onNodeWithText("Rename the math module").assertDoesNotExist()
        compose.onNodeWithText("Settled · 1").performClick()
        compose.onNodeWithText("Rename the math module").assertIsDisplayed()
        compose.onNodeWithText("Add clamp helper").assertDoesNotExist()
        compose.onNodeWithText("Fix flaky WebSocket reconnect").assertDoesNotExist()
    }

    @Test
    fun swipingAFinishedChatSlidesItOffWithUndo() {
        show(listOf(thread("done", "Add clamp helper"), thread("other", "Another finished task")))
        compose.onNodeWithText("Add clamp helper").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(listOf("done" to true), calls.toList())
        compose.onNodeWithText("Add clamp helper").assertDoesNotExist()
        compose.onNodeWithText("Chat settled").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(listOf("done" to true, "done" to false), calls.toList())
        compose.onNodeWithText("Add clamp helper").assertIsDisplayed()
    }
}
