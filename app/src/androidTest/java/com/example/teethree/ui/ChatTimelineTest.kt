package com.example.teethree.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.example.teethree.data.ThreadSearchJump
import com.example.teethree.data.TimelineItem
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatTimelineTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchResultLoadsTargetedHistoryAndRevealsOccurrenceInsideLongMessage() {
        val target =
            message(5)
                .copy(
                    text =
                        "Earlier paragraph before the match.\n\n".repeat(80) +
                            "A needle in this message.\n\n" +
                            "Later paragraph after the match.\n\n".repeat(30),
                    searchEntryId = "entry-5",
                )
        val messages = mutableStateOf((51..60).map { message(it) })
        val older = mutableStateOf(true)
        val revision = mutableStateOf(0L)
        val jump =
            mutableStateOf<ThreadSearchJump?>(
                ThreadSearchJump("environment", "thread", "entry-5", 0, "needle", "request")
            )
        val completed = AtomicReference<Pair<String, String?>?>(null)
        val loadedThrough = mutableListOf<String>()
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            T3Theme(AppTheme.Light) {
                ChatTimeline(
                    messages.value,
                    Modifier.width(360.dp).height(600.dp),
                    list = list,
                    hasOlderMessages = older.value,
                    historyRevision = revision.value,
                    searchJump = jump.value,
                    onLoadThrough = { entry ->
                        loadedThrough.add(entry)
                        messages.value = (1..60).map { if (it == 5) target else message(it) }
                        older.value = false
                        revision.value += 1
                    },
                    onSearchJumpFinished = { id, error ->
                        completed.set(id to error)
                        jump.value = null
                    },
                )
            }
        }
        compose.waitUntil { completed.get() != null }
        var offset = 0
        compose.runOnIdle {
            assertEquals(listOf("entry-5"), loadedThrough)
            assertEquals("request" to null, completed.get())
            assertEquals(
                "message-5",
                list.layoutInfo.visibleItemsInfo
                    .first { it.index == list.firstVisibleItemIndex }
                    .key,
            )
            offset = list.firstVisibleItemScrollOffset
            assertTrue(
                "The selected occurrence must be revealed inside the long message",
                offset > 1000,
            )
            messages.value = messages.value + message(61, streaming = true)
        }
        compose.runOnIdle {
            assertEquals(
                "message-5",
                list.layoutInfo.visibleItemsInfo
                    .first { it.index == list.firstVisibleItemIndex }
                    .key,
            )
            assertEquals(offset, list.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun incomingMessagesPreserveReadingPositionAndJumpResumesFollowing() {
        val messages = mutableStateOf((1..60).map { message(it) })
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            T3Theme(AppTheme.Light) {
                ChatTimeline(messages.value, Modifier.width(360.dp).height(600.dp), list = list)
            }
        }
        compose.onNodeWithTag("chat-history").performTouchInput { swipeDown() }
        var anchor: Any? = null
        var offset = 0
        compose.runOnIdle {
            assertTrue(
                "The gesture must move into history",
                list.layoutInfo.visibleItemsInfo.last().key != "chat-tail",
            )
            anchor = list.layoutInfo.visibleItemsInfo.first().key
            offset = list.firstVisibleItemScrollOffset
            messages.value =
                messages.value.dropLast(1) + message(60, streaming = true) + message(61)
        }
        compose.runOnIdle {
            assertEquals(anchor, list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
        }
        compose.onNodeWithTag("jump-to-latest").performClick()
        compose.runOnIdle {
            assertEquals("chat-tail", list.layoutInfo.visibleItemsInfo.last().key)
            messages.value = messages.value + message(62)
        }
        compose.runOnIdle {
            assertEquals("chat-tail", list.layoutInfo.visibleItemsInfo.last().key)
        }
    }

    @Test
    fun growingMessageAndKeyboardResizeStayAtBottomWhenFollowing() {
        val messages = mutableStateOf(listOf(message(1)))
        val height = mutableStateOf(600.dp)
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            T3Theme(AppTheme.Light) {
                ChatTimeline(
                    messages.value,
                    Modifier.width(360.dp).height(height.value),
                    list = list,
                )
            }
        }
        compose.runOnIdle {
            messages.value =
                listOf(
                    message(1).copy(text = "A streaming response\n".repeat(100), streaming = true)
                )
            height.value = 300.dp
        }
        compose.runOnIdle {
            assertEquals("chat-tail", list.layoutInfo.visibleItemsInfo.last().key)
            val tail = list.layoutInfo.visibleItemsInfo.last()
            assertTrue(tail.offset + tail.size <= list.layoutInfo.viewportEndOffset)
        }
    }

    @Test
    fun readingWithinLongStreamingMessageKeepsItsTopOffset() {
        val messages =
            mutableStateOf(
                listOf(message(1).copy(text = "A long streaming response.\n".repeat(150)))
            )
        lateinit var list: LazyListState
        compose.setContent {
            list = rememberLazyListState()
            T3Theme(AppTheme.Light) {
                ChatTimeline(messages.value, Modifier.width(360.dp).height(600.dp), list = list)
            }
        }
        compose.onNodeWithTag("chat-history").performTouchInput { swipeDown() }
        var offset = 0
        compose.runOnIdle {
            assertEquals(0, list.firstVisibleItemIndex)
            offset = list.firstVisibleItemScrollOffset
            messages.value =
                listOf(
                    messages.value
                        .single()
                        .copy(
                            text =
                                messages.value.single().text +
                                    "New content appended below.\n".repeat(30)
                        )
                )
        }
        compose.runOnIdle { assertEquals(offset, list.firstVisibleItemScrollOffset) }
    }

    @Test
    fun returningRestoresTheSavedMessageAndBoundsMissingAnchorHistory() {
        val messages = mutableStateOf((1..80).map { message(it) })
        val shown = mutableStateOf(true)
        val hasOlder = mutableStateOf(false)
        val revision = mutableStateOf(0L)
        var loadedPages = 0
        var anchorRemoved = false
        lateinit var list: LazyListState
        compose.setContent {
            val saved = rememberSaveableStateHolder()
            if (shown.value)
                saved.SaveableStateProvider("thread") {
                    list = rememberLazyListState()
                    T3Theme(AppTheme.Light) {
                        ChatTimeline(
                            messages.value,
                            Modifier.width(360.dp).height(600.dp),
                            list = list,
                            hasOlderMessages = hasOlder.value,
                            historyRevision = revision.value,
                            onLoadOlder = {
                                val start =
                                    (messages.value.first().ordinal.toInt() - 20).coerceAtLeast(1)
                                messages.value =
                                    (start..(if (anchorRemoved) 10019 else 80)).map { message(it) }
                                hasOlder.value = anchorRemoved || start > 1
                                revision.value += 1
                                loadedPages += 1
                            },
                        )
                    }
                }
        }
        compose.onNodeWithTag("chat-history").performTouchInput { swipeDown() }
        compose.onNodeWithTag("chat-history").performScrollToIndex(5)
        var anchor: Any? = null
        var offset = 0
        compose.runOnIdle {
            anchor = list.layoutInfo.visibleItemsInfo.first().key
            offset = list.firstVisibleItemScrollOffset
            assertTrue(
                "The saved message must be outside the initial history window",
                anchor.toString().removePrefix("message-").toInt() < 61,
            )
            shown.value = false
        }
        compose.runOnIdle {
            messages.value = (61..80).map { message(it) }
            hasOlder.value = true
            shown.value = true
        }
        compose.runOnIdle {
            assertTrue(loadedPages > 0)
            assertEquals(anchor, list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
            shown.value = false
        }
        var pagesBeforeMissingAnchor = 0
        compose.runOnIdle {
            pagesBeforeMissingAnchor = loadedPages
            anchorRemoved = true
            messages.value = (10000..10019).map { message(it) }
            hasOlder.value = true
            shown.value = true
        }
        compose.runOnIdle {
            assertTrue(loadedPages > pagesBeforeMissingAnchor)
            assertTrue(
                "A removed anchor must not load all history",
                loadedPages - pagesBeforeMissingAnchor <= 20,
            )
        }
    }

    private fun message(id: Int, streaming: Boolean = false) =
        TimelineItem(
            id = "message-$id",
            type = "assistant",
            text =
                "Message $id\nFirst line of the reply.\nSecond line of the reply.\nThird line of the reply.",
            title = null,
            status = "complete",
            ordinal = id.toLong(),
            streaming = streaming,
        )
}
