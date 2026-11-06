package com.example.teethree.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import com.example.teethree.ui.AppTheme
import com.example.teethree.ui.FloatingDockLayout
import com.example.teethree.ui.T3Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FloatingDockTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val selected = mutableStateOf<String?>(null)

    private fun show(width: Float) {
        compose.setContent {
            T3Theme(AppTheme.Light) {
                ConversationPaneScaffold(
                    selectedThreadId = selected.value,
                    adaptiveInfo =
                        WindowAdaptiveInfo(
                            WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(width, 800f),
                            Posture(),
                        ),
                    listPane = { open ->
                        FloatingDockLayout(
                            DockSlots.List,
                            Modifier.fillMaxSize(),
                            dock = { Dock("New task dock") },
                        ) { bottom ->
                            LazyColumn(
                                Modifier.fillMaxSize().testTag("list-rows"),
                                contentPadding = PaddingValues(bottom = bottom),
                            ) {
                                items(30) { index ->
                                    Text(
                                        "Chat $index",
                                        Modifier.fillMaxWidth().height(56.dp).clickable {
                                            selected.value = "thread"
                                            open("thread")
                                        },
                                    )
                                }
                            }
                        }
                    },
                    detailPane = {
                        FloatingDockLayout(
                            DockSlots.Detail,
                            Modifier.fillMaxSize(),
                            dock = { Dock("Reply dock") },
                        ) { bottom ->
                            LazyColumn(
                                Modifier.fillMaxSize().testTag("chat-rows"),
                                contentPadding = PaddingValues(bottom = bottom),
                            ) {
                                items(40) { Text("Message $it", Modifier.height(48.dp)) }
                            }
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun Dock(label: String) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp).height(180.dp),
        ) {
            Box { Text(label) }
        }
    }

    @Test
    fun phoneKeepsOneDockInPlaceWhileThePanesChange() {
        show(width = 400f)
        compose.onAllNodesWithTag("floating-dock").assertCountEquals(1)
        compose.onNodeWithText("New task dock").assertIsDisplayed()
        val before = compose.onNodeWithTag("floating-dock").fetchSemanticsNode().boundsInRoot
        // The list runs on under the dock instead of stopping at its top edge.
        val rows = compose.onNodeWithTag("list-rows").fetchSemanticsNode().boundsInRoot
        assertTrue(rows.bottom > before.top + 100)

        compose.onNodeWithText("Chat 0").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Reply dock").assertIsDisplayed()
        compose.onNodeWithText("New task dock").assertDoesNotExist()
        compose.onAllNodesWithTag("floating-dock").assertCountEquals(1)
        val after = compose.onNodeWithTag("floating-dock").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, 1f)
        assertEquals(before.bottom, after.bottom, 1f)
    }

    @Test
    fun twoPanesKeepADockEach() {
        selected.value = "thread"
        show(width = 1000f)
        compose.onAllNodesWithTag("floating-dock").assertCountEquals(2)
        compose.onNodeWithText("New task dock").assertIsDisplayed()
        compose.onNodeWithText("Reply dock").assertIsDisplayed()
    }
}
