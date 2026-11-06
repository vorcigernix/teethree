package com.example.teethree.ui

import com.example.teethree.data.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityStepsTest {
    private fun item(
        id: String,
        type: String,
        title: String? = null,
        status: String = "completed",
        text: String = "",
    ) = TimelineItem(id, type, text, title, status, 0, streaming = false)

    @Test
    fun consecutiveToolItemsShareOneSegmentBetweenMessages() {
        val steps =
            activitySteps(
                listOf(
                    item("u", "user"),
                    item("a", "command_execution", status = "running"),
                    item("b", "file_change", status = "failed"),
                    item("c", "file_search"),
                    item("m", "assistant"),
                    item("d", "todo_list"),
                )
            )
        assertNull(steps[0])
        assertEquals(ActivityStep("a", first = true, count = 3, working = 1, failed = 1), steps[1])
        assertEquals(ActivityStep("a", first = false, count = 3, working = 1, failed = 1), steps[3])
        assertNull(steps[4])
        assertEquals(ActivityStep("d", first = true, count = 1, working = 0, failed = 0), steps[5])
    }

    @Test
    fun labelsSplitIntoVerbAndTarget() {
        assertEquals(
            "Read" to "/repo/native-android/src/math.ts",
            stepLabel(item("1", "tool", title = "Read /repo/native-android/src/math.ts")),
        )
        assertEquals(
            "Run" to "pnpm test reconnect",
            stepLabel(item("2", "command_execution", title = "pnpm test reconnect\n--watch")),
        )
        assertEquals(
            "Edit" to "src/math.ts",
            stepLabel(
                item("3", "file_change", title = "Changed files", text = "src/math.ts\n@@ -1 +1 @@")
            ),
        )
        assertEquals(
            "Run" to "vp test run",
            stepLabel(item("6", "command_execution", title = "Tool", text = "vp test run\nok")),
        )
        assertEquals("Todo list" to null, stepLabel(item("4", "todo_list", title = "Todo list")))
        assertEquals("Mcp tool call" to null, stepLabel(item("5", "mcp_tool_call")))
    }

    @Test
    fun segmentsSummarizeWhatTheyDid() {
        val summary =
            activitySummary(
                listOf(
                    item("1", "tool", title = "Read package.json"),
                    item("2", "tool", title = "Read src/math.ts"),
                    item("3", "file_search", text = "sum("),
                    item("4", "file_change", text = "src/math.ts"),
                    item("5", "file_change", text = "test/math.test.ts"),
                    item("6", "command_execution", title = "pnpm test"),
                )
            )
        assertEquals(
            listOf("Explored 2 files", "Searched once", "Edited 2 files", "Ran 1 command"),
            summary,
        )
        assertEquals(
            "Explored 2 files · searched once · edited 2 files · ran 1 command",
            summaryLine(summary),
        )
    }
}
