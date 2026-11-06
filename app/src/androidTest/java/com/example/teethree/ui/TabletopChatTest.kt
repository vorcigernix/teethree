package com.example.teethree.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.teethree.data.ApprovalOption
import com.example.teethree.data.ComposerDraft
import com.example.teethree.data.ModelSelection
import com.example.teethree.data.RuntimeRequest
import com.example.teethree.data.T3Thread
import com.example.teethree.data.TimelineItem
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TabletopChatTest {
    @get:Rule val compose = createComposeRule()

    private val thread =
        T3Thread(
            id = "thread",
            projectId = "showcase",
            title = "Add clamp helper",
            status = "running",
            modelSelection = ModelSelection("codex", "Codex · high"),
            activeRunId = null,
            archived = false,
            preview = "",
            updatedAt = "",
        )

    private fun item(id: String, type: String, text: String = "", title: String? = null) =
        TimelineItem(id, type, text, title, "completed", 0, streaming = false)

    private val timeline =
        listOf(
            item("u", "user", "Add a clamp helper next to sum and cover it with tests."),
            item("r1", "tool", title = "Read package.json"),
            item("r2", "tool", title = "Read src/math.ts"),
            item("e1", "file_change", "src/math.ts"),
            item("e2", "file_change", "test/math.test.ts"),
            item(
                "a",
                "assistant",
                "All six tests pass. I'd like to write two files, **src/math.ts** and `test/math.test.ts`. Should I go ahead?",
            ),
        )

    private val approval =
        RuntimeRequest(
            "request",
            "approval",
            "Write 2 files?",
            canRespond = true,
            options =
                listOf(
                    ApprovalOption("accept", "Allow once"),
                    ApprovalOption("acceptForSession", "Always"),
                    ApprovalOption("decline", "Decline"),
                ),
            questions = emptyList(),
        )

    @Test
    fun tabletopSplitsAtTheHingeAndAnswersTheApproval() {
        val responses = mutableStateListOf<String>()
        compose.setContent {
            T3Theme(AppTheme.Light) {
                val hinge = with(androidx.compose.ui.platform.LocalDensity.current) { 470.dp.toPx() }
                TabletopChat(
                    hingeTop = hinge,
                    hingeBottom = hinge,
                    thread = thread,
                    projectLabel = "showcase",
                    modelLabel = "Codex · high",
                    modeLabel = "Ask first",
                    timeline = timeline,
                    pendingRequests = listOf(approval),
                    draft = ComposerDraft(),
                    busy = false,
                    canOperate = true,
                    onBack = {},
                    onKeyboard = {},
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    onRespond = { _, decision -> responses += decision },
                    onAttach = {},
                )
            }
        }
        compose.onNodeWithTag("tabletop-chat").assertIsDisplayed()
        compose.onNodeWithText("Explored 2 files").assertIsDisplayed()
        compose.onNodeWithText("Say “allow”, “always” or “no”").assertIsDisplayed()
        compose.onNodeWithText("Allow once").performClick()
        compose.runOnIdle { assertEquals(listOf("accept"), responses) }
    }

    @Test
    fun spokenMessagesAreLabelledAndHandsFreeFoldsFinishedSteps() {
        SpokenMessages.record(
            InstrumentationRegistry.getInstrumentation().targetContext,
            timeline.first().text,
            6_000,
        )
        compose.setContent {
            T3Theme(AppTheme.Light) {
                ChatTimeline(
                    timeline,
                    Modifier.size(412.dp, 700.dp),
                    collapseFinishedSteps = true,
                )
            }
        }
        compose.onNodeWithText("Voice · 0:06").assertIsDisplayed()
        compose.onNodeWithText("Explored 2 files · edited 2 files").assertIsDisplayed()
        // Folded away until the header is tapped.
        compose.onNodeWithText("package.json").assertDoesNotExist()
        compose.onNodeWithText("Explored 2 files · edited 2 files").performClick()
        compose.onNodeWithText("package.json").assertIsDisplayed()
    }
}
