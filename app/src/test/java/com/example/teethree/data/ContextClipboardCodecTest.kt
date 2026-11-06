package com.example.teethree.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextClipboardCodecTest {
    @Test
    fun htmlRoundTripsUnicodeApostrophesAndPlusWithoutExecutingOrTruncatingText() {
        val record =
            terminalComposerContext(
                "shell",
                "Build",
                1,
                1,
                "日本語".repeat(21_000) + " can't + <script>",
            )
        val text = composerContextReference(record)
        val payload =
            encodeContextClipboard(
                text,
                ClipboardSource("environment", "thread", "message"),
                contextOf(listOf(record)),
            )
        assertTrue(payload.html!!.length > payload.fragment!!.length * 8)
        val decoded = decodeContextClipboard(payload.copy(fragment = null))!!
        assertEquals(record, decoded.records.single())
        assertEquals(ClipboardSource("environment", "thread", "message"), decoded.source)
        assertFalse(payload.html!!.contains("<script>"))
        assertNull(
            decodeContextClipboard(
                ContextClipboardPayload(
                    "Visible fallback",
                    html = "<pre data-t3-context-fragment=\"%ZZ\">bad</pre>",
                )
            )
        )
        assertNull(
            decodeContextClipboard(
                ContextClipboardPayload(
                    "Visible fallback",
                    html = "<pre data-t3-context-fragment=\"%C0%AF\">bad</pre>",
                )
            )
        )
    }

    @Test
    fun rawRecordLimitIsCheckedBeforeDroppingMalformedRecords() {
        val valid = mentionComposerContext("src/main.kt")
        val invalid = buildJsonObject {
            put("version", 1)
            put("kind", "image")
            put("contextId", "bad")
            put("label", "Missing file fields")
        }
        fun fragment(records: List<JsonObject>) = buildJsonObject {
            put("version", 1)
            put("source", buildJsonObject { put("environmentId", "source") })
            put("records", JsonArray(records))
        }.toString()
        val decoded =
            decodeContextClipboard(
                ContextClipboardPayload("Fallback", fragment(listOf(valid, invalid)))
            )!!
        assertEquals(listOf(valid), decoded.records)
        assertTrue(decoded.warnings.isNotEmpty())
        assertNull(
            decodeContextClipboard(
                ContextClipboardPayload("Fallback", fragment(List(201) { invalid }))
            )
        )
        assertNull(
            decodeContextClipboard(ContextClipboardPayload("Fallback", "x".repeat(16_000_001)))
        )
        assertNull(
            decodeContextClipboard(
                ContextClipboardPayload("Fallback", fragment(listOf(valid, valid)))
            )
        )
    }

    @Test
    fun freshContextIdentitiesPreserveScreenshotBindingsAndMarkForeignThreadsUnavailable() {
        val image = buildJsonObject {
            put("version", 1)
            put("kind", "image")
            put("contextId", "picture")
            put("label", "Image")
            put("attachmentId", "asset")
            put("name", "picture.png")
            put("mimeType", "image/png")
            put("sizeBytes", 1)
        }
        val annotation =
            JsonObject(
                previewAnnotationComposerContext("https://page.test/", "Page", "Fix this") +
                    ("screenshotContextId" to JsonPrimitive("picture"))
            )
        val foreign = buildJsonObject {
            put("version", 1)
            put("kind", "thread")
            put("contextId", "foreign")
            put("label", "Other chat")
            put("environmentId", "other")
            put("threadId", "chat")
            put("title", "Other chat")
        }
        val text =
            listOf(annotation, foreign).joinToString(" ", transform = ::composerContextReference)
        val payload =
            encodeContextClipboard(
                text,
                ClipboardSource("source"),
                contextOf(listOf(image, annotation, foreign)),
            )
        var next = 0
        val imported =
            reidentifyContextClipboard(payload, "destination", createId = { "fresh-${next++}" })!!
        val records = imported.context.array("records").map { it as JsonObject }
        val copiedImage = records.single { it.string("kind") == "image" }
        val copiedAnnotation = records.single { it.string("kind") == "preview-annotation" }
        assertEquals(
            copiedImage.string("contextId"),
            copiedAnnotation.string("screenshotContextId"),
        )
        assertTrue(
            records.none {
                it.string("contextId") in
                    setOf("picture", "foreign", annotation.string("contextId"))
            }
        )
        assertTrue(imported.warnings.single().contains("Other chat"))
        assertTrue(
            composerEditableText(imported.text, imported.context)
                .contains("t3-context://v1/thread/fresh-")
        )
        assertTrue(
            reidentifyContextClipboard(payload, "destination", setOf("other"))!!
                .context
                .array("records")
                .any { (it as JsonObject).string("kind") == "thread" }
        )
    }

    @Test
    fun threadLinksInsideCodeStayLiteralAndOnlyReachableMachinesBecomeReferences() {
        val payload =
            ContextClipboardPayload(
                "`https://app.t3.codes/threads/destination/code` [Current](t3-thread://v2/destination/local) <t3-thread://v2/foreign/remote>"
            )
        val imported = reidentifyContextClipboard(payload, "destination")!!
        assertTrue(imported.text.contains("`https://app.t3.codes/threads/destination/code`"))
        assertTrue(imported.text.contains("<t3-thread://v2/foreign/remote>"))
        assertEquals(
            "local",
            (imported.context.array("records").single() as JsonObject).string("threadId"),
        )
        assertNull(
            reidentifyContextClipboard(ContextClipboardPayload("Ordinary prose"), "destination")
        )
    }

    @Test
    fun copyingAnAttachedFileProducesPortableMetadataWithoutChangingTheSourceDraft() {
        val attachment = ChatAttachment("asset", "file", "notes.txt", "text/plain", 12)
        val draft = ComposerDraft("Read this", listOf(attachment))
        val payload = clipboardCopyOfDraft(draft, ClipboardSource("source"))
        val copied = decodeContextClipboard(payload)!!.records.single()
        assertEquals("asset", copied.string("attachmentId"))
        assertEquals("file", copied.string("kind"))
        assertTrue(payload.text.contains(composerContextReference(copied)))
        assertNull(draft.context)
        assertEquals("Read this", draft.text)
    }
}
