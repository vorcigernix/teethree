package com.example.teethree.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadProjectionTest {
    @Test
    fun streamingReplacesStableRowsAndHistoryPreservesNewerLiveBodies() {
        val projection =
            ThreadProjection(
                json(
                    """{
          "runs":[{"id":"run","status":"running"}], "attempts":[], "runtimeRequests":[],
          "turnItems":[{"id":"reply","type":"assistant_message","threadId":"thread","runId":"run","ordinal":4,"text":"Hello","streaming":true}],
          "visibleTurnItems":[{"sourceThreadId":"thread","sourceItemId":"reply","visibility":"local","item":{"id":"reply","type":"assistant_message","runId":"run","ordinal":4,"text":"Hello","streaming":true}}]
        }"""
                ),
                10,
                latestLocalOrdinal = 4,
                partial = true,
            )
        val stableId = projection.timeline().single().id
        val unchangedTimeline = projection.timeline()
        projection.applyEvent(
            event(
                11,
                "subagent.updated",
                """{"id":"agent","runId":"run","status":"running","prompt":"Work","updatedAt":"now"}""",
            )
        )
        assertSame(unchangedTimeline, projection.timeline())
        assertEquals("Work", projection.subagents().single().jsonObject.string("prompt"))
        projection.applyEvent(
            event(
                12,
                "message.updated",
                """{"id":"message","role":"assistant","text":"Hello world"}""",
            )
        )
        projection.applyEvent(
            event(
                13,
                "turn-item.updated",
                """{"id":"reply","messageId":"searchable-message","type":"assistant_message","threadId":"thread","runId":"run","ordinal":4,"text":"Hello world","streaming":false,"attachments":[{"id":"asset","type":"file","name":"notes.txt","mimeType":"text/plain","sizeBytes":4}]}""",
            )
        )
        projection.applyEvent(
            event(
                12,
                "turn-item.updated",
                """{"id":"reply","type":"assistant_message","ordinal":4,"text":"stale"}""",
            )
        )
        assertEquals(1, projection.timeline().size)
        assertEquals(stableId, projection.timeline().single().id)
        assertEquals("Hello world", projection.timeline().single().text)
        assertEquals("assistant", projection.timeline().single().type)
        assertEquals("searchable-message", projection.timeline().single().searchEntryId)
        assertEquals("notes.txt", projection.timeline().single().attachments.single().name)
        projection.mergeHistory(
            json(
                """{"items":[
          {"sourceThreadId":"thread","sourceItemId":"question","visibility":"local","item":{"id":"question","type":"user_message","runId":"run","ordinal":3,"text":"Question"}},
          {"sourceThreadId":"thread","sourceItemId":"reply","visibility":"local","item":{"id":"reply","type":"assistant_message","runId":"run","ordinal":4,"text":"old reply"}}
        ]}"""
            )
        )
        assertEquals(
            listOf("Question", "Hello world"),
            projection.timeline().map(TimelineItem::text),
        )
    }

    @Test
    fun boundedWindowsIgnoreUpdatesToUnloadedHistoryButKeepQueuedInput() {
        val projection =
            ThreadProjection(
                json(
                    """{"runs":[{"id":"old","status":"completed"},{"id":"queue","status":"queued"}],"attempts":[],"runtimeRequests":[],"turnItems":[],"visibleTurnItems":[]}"""
                ),
                10,
                latestLocalOrdinal = 20,
                partial = true,
            )
        projection.applyEvent(
            event(
                11,
                "turn-item.updated",
                """{"id":"unloaded","type":"assistant_message","threadId":"thread","runId":"old","ordinal":2,"text":"Old history"}""",
            )
        )
        assertTrue(projection.timeline().isEmpty())
        projection.applyEvent(
            event(
                12,
                "turn-item.updated",
                """{"id":"queued","type":"user_message","threadId":"thread","runId":"queue","ordinal":3,"inputIntent":"queued_turn","text":"Queued input"}""",
            )
        )
        assertEquals("Queued input", projection.timeline().single().text)
        projection.applyEvent(event(13, "run.updated", """{"id":"queue","status":"cancelled"}"""))
        assertTrue(projection.timeline().isEmpty())
    }

    @Test
    fun resolvedApprovalsDisappearAndNonResumableRequestsCannotBeAnswered() {
        val projection =
            ThreadProjection(
                json(
                    """{
          "runs":[],"attempts":[],"turnItems":[],
          "runtimeRequests":[{"id":"approval","kind":"command","status":"pending","responseCapability":{"type":"live"}}],
          "visibleTurnItems":[{"sourceThreadId":"thread","sourceItemId":"approval-item","visibility":"local","item":{"id":"approval-item","type":"approval_request","requestId":"approval","prompt":"Run command?","options":[{"decision":"accept","label":"Allow"},{"decision":"decline","label":"Deny"}]}}]
        }"""
                ),
                1,
            )
        assertEquals("Run command?", projection.pendingRequests().single().prompt)
        assertTrue(projection.pendingRequests().single().canRespond)
        assertEquals(
            listOf("accept", "decline"),
            projection.pendingRequests().single().options.map(ApprovalOption::decision),
        )
        projection.applyEvent(
            event(
                2,
                "runtime-request.updated",
                """{"id":"approval","kind":"command","status":"pending","responseCapability":{"type":"not_resumable","reason":"Session ended"}}""",
            )
        )
        assertFalse(projection.pendingRequests().single().canRespond)
        projection.applyEvent(
            event(
                3,
                "runtime-request.updated",
                """{"id":"approval","kind":"command","status":"resolved","responseCapability":{"type":"live"}}""",
            )
        )
        assertTrue(projection.pendingRequests().isEmpty())
        projection.applyEvent(
            event(
                4,
                "runtime-request.updated",
                """{"id":"question","kind":"user_input","status":"pending","responseCapability":{"type":"message"}}""",
            )
        )
        projection.applyEvent(
            event(
                5,
                "turn-item.updated",
                """{"id":"question-item","threadId":"thread","type":"user_input_request","requestId":"question","questions":[{"id":"one","question":"Choose an option","options":[{"label":"Displayed label","value":"wire-value"}],"allowCustomAnswer":false,"initialAnswer":"Suggested","required":false},{"id":"two","question":"Anything else?","options":[]}]}""",
            )
        )
        val questions = projection.pendingRequests().single().questions
        assertFalse(questions[0].allowCustomAnswer)
        assertEquals("Suggested", questions[0].initialAnswer)
        assertTrue(questions[1].allowCustomAnswer)
        assertFalse(questions[0].required)
        assertTrue(questions[1].required)
        assertEquals(
            listOf("wire-value"),
            projection.answerValues("question", "one", listOf("Displayed label")),
        )
    }

    @Test
    fun rollbackHidesOnlyLocalRowsAndUnknownEventsStillAdvanceTheCursor() {
        val projection =
            ThreadProjection(
                json(
                    """{"runs":[{"id":"run","status":"completed"}],"attempts":[],"runtimeRequests":[],"turnItems":[],"visibleTurnItems":[
          {"sourceThreadId":"parent","sourceItemId":"inherited","visibility":"inherited","item":{"id":"inherited","type":"assistant_message","runId":"run","text":"Inherited"}},
          {"sourceThreadId":"thread","sourceItemId":"local","visibility":"local","item":{"id":"local","type":"assistant_message","runId":"run","text":"Local"}}
        ]}"""
                ),
                1,
            )
        projection.applyEvent(event(2, "run.updated", """{"id":"run","status":"rolled_back"}"""))
        assertEquals(listOf("Inherited"), projection.timeline().map(TimelineItem::text))
        projection.applyEvent(event(4, "future.type", "{}"))
        projection.applyEvent(event(3, "run.updated", """{"id":"run","status":"completed"}"""))
        assertEquals(listOf("Inherited"), projection.timeline().map(TimelineItem::text))
    }

    private fun event(sequence: Int, type: String, payload: String): JsonObject =
        json(
            """{"kind":"event","sequence":$sequence,"event":{"type":"$type","payload":$payload}}"""
        )

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
}
