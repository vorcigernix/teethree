package com.example.teethree.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingMessageDispatchTest {
    @Test
    fun malformedStoredIdentifiersAndDispatchModeCannotBeSent() {
        val pending =
            prepareDraftDispatch(ComposerDraft("Message"), "environment", "thread", null, "queue")
                .pendingDispatch!!
        for (invalid in
            listOf(
                pending.copy(commandId = " "),
                pending.copy(messageId = " leading"),
                pending.copy(threadId = ""),
                pending.copy(
                    dispatchMode = JsonObject(mapOf("type" to JsonPrimitive("steer_active")))
                ),
            )) {
            assertThrows(IllegalArgumentException::class.java) { invalid.wire() }
        }
    }

    @Test
    fun unchangedDraftAfterRestartReusesIdsAndOriginalSteeringPayload() {
        val source =
            appendComposerContext(
                ComposerDraft(
                    "Message",
                    listOf(ChatAttachment("file", "file", "notes.txt", "text/plain", 3)),
                ),
                listOf(mentionComposerContext("src/main.kt")),
            )
        val first = prepareDraftDispatch(source, "environment", "thread", "original-run", "steer")
        val restored = Json.decodeFromString<ComposerDraft>(Json.encodeToString(first))
        val retry = prepareDraftDispatch(restored, "environment", "thread", null, "queue")
        assertEquals(first.pendingDispatch!!.wire(), retry.pendingDispatch!!.wire())
        assertEquals("original-run", retry.pendingDispatch!!.dispatchMode.string("targetRunId"))
    }

    @Test
    fun changedContentAttachmentContextOrDestinationGetsANewIdentity() {
        val first =
            prepareDraftDispatch(ComposerDraft("Message"), "environment", "thread", null, "queue")
        val changes =
            listOf(
                first.copy(text = "Changed"),
                first.copy(
                    attachments =
                        listOf(ChatAttachment("file", "file", "notes.txt", "text/plain", 3))
                ),
                appendComposerContext(first, listOf(mentionComposerContext("src/main.kt"))),
            )
        for (change in changes) assertNotEquals(
            first.pendingDispatch!!.commandId,
            prepareDraftDispatch(change, "environment", "thread", null, "queue")
                .pendingDispatch!!
                .commandId,
        )
        assertNotEquals(
            first.pendingDispatch!!.commandId,
            prepareDraftDispatch(first, "other-environment", "thread", null, "queue")
                .pendingDispatch!!
                .commandId,
        )
        assertNotEquals(
            first.pendingDispatch!!.commandId,
            prepareDraftDispatch(first, "environment", "other-thread", null, "queue")
                .pendingDispatch!!
                .commandId,
        )
        assertThrows(IllegalArgumentException::class.java) {
            prepareDraftDispatch(
                ComposerDraft("x".repeat(120_001)),
                "environment",
                "thread",
                null,
                "queue",
            )
        }
    }
}
