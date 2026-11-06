package com.example.teethree.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryReducerTest {
    @Test
    fun queueUsesDeliveryOrderAndDoesNotExposeAutomaticDeliveriesForEditing() {
        val history =
            ConversationHistoryReducer(
                "thread",
                json(
                    """{
            "runs":[
                {"id":"active","ordinal":1,"status":"running","userMessageId":"original"},
                {"id":"one","ordinal":2,"status":"queued","userMessageId":"first","queuePosition":1,"queueHeld":true},
                {"id":"two","ordinal":3,"status":"queued","userMessageId":"second","queuePosition":2},
                {"id":"automatic","ordinal":4,"status":"queued","userMessageId":"notification","queuePosition":9}
            ],
            "messages":[
                {"id":"first","role":"user","text":"First","attachments":[{"id":"attachment"}]},
                {"id":"second","role":"user","text":"Second","attachments":[]},
                {"id":"notification","role":"user","text":"Completed work","delegatedCompletion":{"parentRunId":"parent"}}
            ]
        }"""
                ),
                10,
            )
        assertEquals(
            listOf("automatic", "one", "two"),
            history.value().queue.map(QueuedMessage::runId),
        )
        assertFalse(history.value().queue.first().editable)
        assertTrue(history.value().queue[1].editable)
        assertTrue(history.value().queue[1].held)
        assertEquals(1, history.value().queue[1].attachmentCount)
        assertEquals("active", history.value().activeRunId)
        assertTrue(
            history.applyEvent(
                event(
                    11,
                    "message.updated",
                    """{"id":"first","role":"user","text":"Edited","attachments":[{"id":"attachment"}]}""",
                )
            )
        )
        assertEquals("Edited", history.value().queue[1].text)
        history.applyEvent(
            event(
                12,
                "run.updated",
                """{"id":"one","ordinal":2,"status":"cancelled","userMessageId":"first"}""",
            )
        )
        assertEquals(listOf("automatic", "two"), history.value().queue.map(QueuedMessage::runId))
        assertFalse(
            history.applyEvent(
                event(
                    11,
                    "run.updated",
                    """{"id":"one","ordinal":2,"status":"queued","userMessageId":"first"}""",
                )
            )
        )
        assertFalse(
            history.applyEvent(
                event(
                    13,
                    "message.updated",
                    """{"id":"answer","role":"assistant","text":"Streaming output"}""",
                )
            )
        )
    }

    @Test
    fun pagedCheckpointsKeepSourceOwnershipAndAuthoritativeAvailability() {
        val history =
            ConversationHistoryReducer(
                "thread",
                json(
                    """{
            "runs":[{"id":"run","ordinal":2,"status":"completed","userMessageId":"message"}],
            "checkpoints":[
                {"id":"baseline","scopeId":"scope","runId":"run","appRunOrdinal":null,"status":"ready","files":[]},
                {"id":"current","scopeId":"scope","runId":"run","appRunOrdinal":2,"status":"missing","files":[],"filesTotal":{"count":8}}
            ]
        }"""
                ),
                1,
            )
        history.mergeHistory(
            json(
                """{"items":[
            {"sourceThreadId":"parent","item":{"type":"checkpoint","checkpointId":"inherited","scopeId":"parent-scope"}},
            {"sourceThreadId":"thread","item":{"type":"checkpoint","checkpointId":"old","scopeId":"scope","runId":"older","files":[{"path":"old.kt","additions":2,"deletions":1}]}},
            {"sourceThreadId":"thread","item":{"type":"checkpoint","checkpointId":"current","scopeId":"scope","runId":"run","files":[]}}
        ]}"""
            )
        )
        val checkpoints = history.value().checkpoints.associateBy(ConversationCheckpoint::id)
        assertFalse(checkpoints.containsKey("inherited"))
        assertNull(checkpoints.getValue("baseline").turnCount)
        assertEquals("missing", checkpoints.getValue("current").status)
        assertFalse(checkpoints.getValue("current").canRequestRestore)
        assertEquals(8L, checkpoints.getValue("current").fileCount)
        assertNull(checkpoints.getValue("old").status)
        assertTrue(checkpoints.getValue("old").canRequestRestore)
        assertEquals("old.kt", checkpoints.getValue("old").files.single().path)
        history.applyEvent(
            event(
                2,
                "checkpoint.captured",
                """{"id":"old","scopeId":"scope","status":"stale","appRunOrdinal":1,"files":[]}""",
            )
        )
        assertFalse(history.value().checkpoints.first { it.id == "old" }.canRequestRestore)
    }

    @Test
    fun asynchronousRestoreFailureIsShownAndClearsWhenANewRequestStarts() {
        val history = ConversationHistoryReducer("thread", json("""{"thread":{}}"""), 1)
        assertTrue(
            history.applyEvent(
                event(
                    2,
                    "thread.metadata-updated",
                    """{"rollbackFailure":{"requestId":"request","message":"Workspace is shared"}}""",
                )
            )
        )
        assertEquals("Workspace is shared", history.value().restoreFailure)
        assertTrue(
            history.applyEvent(
                event(
                    3,
                    "thread.metadata-updated",
                    """{"rollbackRequestId":"retry","rollbackFailure":null}""",
                )
            )
        )
        assertNull(history.value().restoreFailure)
    }

    private fun json(source: String): JsonObject = Json.parseToJsonElement(source).jsonObject

    private fun event(sequence: Long, type: String, payload: String): JsonObject =
        json("""{"sequence":$sequence,"event":{"type":"$type","payload":$payload}}""")
}
