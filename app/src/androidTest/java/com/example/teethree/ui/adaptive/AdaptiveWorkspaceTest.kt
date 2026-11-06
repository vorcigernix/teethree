package com.example.teethree.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.HingeInfo
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import androidx.window.layout.FoldingFeature.Orientation
import androidx.window.layout.WindowLayoutInfo
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import com.example.teethree.data.TimelineItem
import com.example.teethree.ui.AppTheme
import com.example.teethree.ui.ChatTimeline
import com.example.teethree.ui.T3Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveWorkspaceTest {
    @get:Rule(order = 0) val folds = WindowLayoutInfoPublisherRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun resizeAndBookPosturePreserveSelectedConversationDraftAndReadingPosition() {
        val info = mutableStateOf(adaptiveInfo(400f, 800f))
        val selected = mutableStateOf<String?>(null)
        val selectionReady = mutableStateOf(true)
        var widthPx = 0
        var heightPx = 0
        lateinit var list: LazyListState
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            widthPx = LocalWindowInfo.current.containerSize.width
            heightPx = LocalWindowInfo.current.containerSize.height
            val saved = rememberSaveableStateHolder()
            val destinations = rememberSaveableStateHolder()
            T3Theme(AppTheme.Light) {
                val suiteType = NavigationSuiteScaffoldDefaults.navigationSuiteType(info.value)
                NavigationSuiteScaffold(
                    navigationSuiteType = suiteType,
                    navigationItems = {
                        NavigationSuiteItem(
                            selected = true,
                            onClick = {},
                            icon = { Text("W") },
                            label = { Text("Workspace") },
                            navigationSuiteType = suiteType,
                        )
                    },
                ) {
                    destinations.SaveableStateProvider("environment:workspace") {
                        ConversationPaneScaffold(
                            selectedThreadId = selected.value,
                            selectionReady = selectionReady.value,
                            adaptiveInfo = info.value,
                            listPane = { open ->
                                Button(
                                    onClick = {
                                        selected.value = "thread"
                                        open("thread")
                                    },
                                    modifier = Modifier.testTag("open-thread"),
                                ) {
                                    Text("Open conversation")
                                }
                            },
                            detailPane = { showList ->
                                if (selected.value != null)
                                    saved.SaveableStateProvider("environment:thread") {
                                        var draft by rememberSaveable { mutableStateOf("") }
                                        list = rememberLazyListState()
                                        Column(Modifier.fillMaxSize()) {
                                            OutlinedTextField(
                                                draft,
                                                { draft = it },
                                                modifier = Modifier.testTag("adaptive-draft"),
                                            )
                                            ChatTimeline(
                                                (1..80).map { message(it) },
                                                Modifier.weight(1f),
                                                list = list,
                                            )
                                            TextButton(
                                                onClick = showList,
                                                modifier = Modifier.testTag("show-conversations"),
                                            ) {
                                                Text("Conversations")
                                            }
                                        }
                                    }
                            },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("open-thread").performClick()
        compose.onNodeWithTag("adaptive-draft").performTextInput("Keep this draft")
        compose.onNodeWithTag("chat-history").performScrollToIndex(12)
        compose.onNodeWithTag("chat-history").performTouchInput { swipeDown() }
        var anchor: Any? = null
        var offset = 0
        var hinge = Rect.Zero
        compose.runOnIdle {
            anchor = list.layoutInfo.visibleItemsInfo.first().key
            offset = list.firstVisibleItemScrollOffset
            hinge = Rect(widthPx / 2f - 12f, 0f, widthPx / 2f + 12f, heightPx.toFloat())
            info.value =
                adaptiveInfo(
                    1000f,
                    800f,
                    Posture(hingeList = listOf(HingeInfo(hinge, false, true, true, true))),
                )
        }
        compose.onNodeWithTag("adaptive-draft").assertTextContains("Keep this draft")
        val listBounds =
            compose.onNodeWithTag("workspace-list-pane").fetchSemanticsNode().boundsInWindow
        val detailBounds =
            compose.onNodeWithTag("workspace-detail-pane").fetchSemanticsNode().boundsInWindow
        assertTrue(
            "Neither pane may cover a separating hinge",
            (listBounds.right <= hinge.left && detailBounds.left >= hinge.right) ||
                (detailBounds.right <= hinge.left && listBounds.left >= hinge.right),
        )
        compose.runOnIdle {
            assertEquals(anchor, list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
            info.value = adaptiveInfo(400f, 800f)
        }
        compose.onNodeWithTag("adaptive-draft").assertTextContains("Keep this draft")
        compose.runOnIdle {
            selectionReady.value = false
            selected.value = null
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            selected.value = "thread"
            selectionReady.value = true
        }
        compose.onNodeWithTag("adaptive-draft").assertTextContains("Keep this draft")
        compose.onNodeWithTag("show-conversations").performClick()
        compose.onNodeWithTag("open-thread").assertIsDisplayed()
        compose.runOnIdle { assertEquals("thread", selected.value) }
        compose.runOnIdle {
            selectionReady.value = false
            selected.value = null
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            selected.value = "thread"
            selectionReady.value = true
        }
        compose.onNodeWithTag("open-thread").assertIsDisplayed()
        compose.onNodeWithTag("adaptive-draft").assertDoesNotExist()
        compose.onNodeWithTag("open-thread").performClick()
        compose.onNodeWithTag("adaptive-draft").assertTextContains("Keep this draft")
        compose.runOnIdle { assertEquals(anchor, list.layoutInfo.visibleItemsInfo.first().key) }
    }

    @Test
    fun nativeWindowManagerFoldMovesFocusedFormAndOpenDialogAwayFromHinge() {
        val dialog = mutableStateOf(false)
        compose.setContent {
            T3Theme(AppTheme.Light) {
                FoldSafeContent(avoidVerticalHinges = true) {
                    Button(
                        onClick = { dialog.value = true },
                        modifier = Modifier.testTag("open-confirmation"),
                    ) {
                        Text("Confirm change")
                    }
                }
                if (dialog.value)
                    AdaptiveAlertDialog(
                        onDismissRequest = { dialog.value = false },
                        title = { Text("Confirm change") },
                        text = {
                            OutlinedTextField(
                                "Saved form value",
                                {},
                                modifier = Modifier.testTag("dialog-input"),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { dialog.value = false }) { Text("Confirm") }
                        },
                        modifier = Modifier.testTag("adaptive-confirmation"),
                    )
            }
        }
        compose.onNodeWithTag("open-confirmation").performClick()
        val feature =
            FoldingFeature(compose.activity, size = 24, orientation = Orientation.VERTICAL)
        compose.runOnIdle { folds.overrideWindowLayoutInfo(WindowLayoutInfo(listOf(feature))) }
        compose.onNodeWithTag("dialog-input").assertIsDisplayed()
        compose.runOnIdle { folds.overrideWindowLayoutInfo(WindowLayoutInfo(listOf(feature))) }
        val form = compose.onNodeWithTag("open-confirmation").fetchSemanticsNode().boundsInWindow
        val confirmation =
            compose.onNodeWithTag("adaptive-confirmation").fetchSemanticsNode().boundsInWindow
        assertTrue(form.right <= feature.bounds.left || form.left >= feature.bounds.right)
        assertTrue(
            confirmation.right <= feature.bounds.left || confirmation.left >= feature.bounds.right
        )
    }

    private fun adaptiveInfo(width: Float, height: Float, posture: Posture = Posture()) =
        WindowAdaptiveInfo(
            WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(width, height),
            posture,
        )

    private fun message(index: Int) =
        TimelineItem(
            id = "message-$index",
            type = "assistant",
            text = "Message $index",
            title = null,
            status = "completed",
            streaming = false,
            ordinal = index.toLong(),
        )
}
