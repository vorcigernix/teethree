package com.example.teethree.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerContextToolsTest {
    @Test
    fun unboundContextReferencesRemainVisibleLiteralProse() {
        val literal = "Review [copied context](t3-context://v1/mention/not-attached) exactly.\n"
        assertEquals(literal, composerEditableText(literal))
        val standalone = "[copied context](t3-context://v1/mention/not-attached)"
        assertEquals(standalone, composerEditableText(standalone))
        val attached =
            appendComposerContext(ComposerDraft(literal), listOf(mentionComposerContext("real.kt")))
        assertEquals(literal, composerEditableText(attached.text, attached.context))
        val edited = replaceComposerDraftText(attached, literal + "More prose.")
        assertEquals(literal + "More prose.", composerEditableText(edited, attached.context))
    }

    @Test
    fun insertingEditingAndRemovingContextPreservesProseWhitespaceAttachmentsAndShareReceipts() {
        val attachment = ChatAttachment("asset", "file", "file.txt", "text/plain", 3)
        val original = ComposerDraft("  Keep this text \n", listOf(attachment), setOf("share"))
        val first = mentionComposerContext("src/a[b].kt")
        val inserted = appendComposerContext(original, listOf(first))
        assertEquals(original.text, composerEditableText(inserted.text, inserted.context))
        assertEquals(original.attachments, inserted.attachments)
        assertEquals(original.importedShareIds, inserted.importedShareIds)
        assertFalse(composerContextReference(first).contains("a[b]"))
        val second = skillComposerContext("testing", "Tests")
        val twice = appendComposerContext(inserted, listOf(second))
        assertEquals(original.text, composerEditableText(twice.text, twice.context))
        assertEquals(twice, appendComposerContext(twice, listOf(second)))
        val prose = "   Edited prose  \n\n"
        val edited = twice.copy(text = replaceComposerDraftText(twice, prose))
        assertEquals(prose, composerEditableText(edited.text, edited.context))
        val removed =
            removeComposerContext(
                removeComposerContext(edited, first.string("contextId")),
                second.string("contextId"),
            )
        assertEquals(prose, removed.text)
        assertNull(removed.context)
        assertEquals(" \n", replaceComposerDraftText(ComposerDraft(), " \n"))
        assertEquals(" \n", composerEditableText(" \n"))
    }

    @Test
    fun annotationKeepsItsReferencedScreenshotButOrphanedContextDoesNotTravel() {
        val annotation =
            JsonObject(
                previewAnnotationComposerContext("https://site.test/", "Page", "Fix the header") +
                    ("screenshotContextId" to JsonPrimitive("picture"))
            )
        val image =
            json(
                """{"version":1,"kind":"image","contextId":"picture","label":"Screenshot","attachmentId":"asset","name":"preview.png","mimeType":"image/png","sizeBytes":4}"""
            )
        val orphan = mentionComposerContext("unused.txt")
        val context = context(listOf(annotation, image, orphan))
        val attached = referencedComposerContext(composerContextReference(annotation), context)!!
        assertEquals(
            listOf(annotation.string("contextId"), "picture"),
            composerContextItems(attached).map { it.contextId },
        )
        assertNull(referencedComposerContext("No references", context))
        val removed =
            removeComposerContext(
                ComposerDraft(composerContextReference(annotation), context = context),
                annotation.string("contextId"),
            )
        assertNull(removed.context)
    }

    @Test
    fun malformedKnownRecordsCannotMasqueradeAsUnknownContextAndLimitsAreEnforced() {
        val terminal = terminalComposerContext("terminal-1", "Shell", 1, 2, "hello\nworld")
        validateComposerContext(context(listOf(terminal)))
        for (record in
            listOf(
                JsonObject(terminal + ("lineEnd" to JsonPrimitive(0))),
                JsonObject(terminal + ("version" to JsonPrimitive("1"))),
                JsonObject(terminal + ("text" to JsonPrimitive("x".repeat(64_001)))),
                json("""{"version":1,"kind":"mention","contextId":"x","label":"A","payload":{}}"""),
                json(
                    """{"version":1,"kind":"element","contextId":"x","label":"A","pageUrl":"https://a","tagName":"div","htmlPreview":"","styles":""}"""
                ),
            )) assertThrows(IllegalArgumentException::class.java) {
            validateComposerContext(context(listOf(record)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateComposerContext(context(listOf(terminal, terminal)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateComposerContext(context(List(201) { mentionComposerContext("$it.kt") }))
        }
        validateComposerContext(
            context(
                listOf(
                    json(
                        """{"version":1,"kind":"future-kind","contextId":"future","label":"Future","payload":{"a":1}}"""
                    )
                )
            )
        )
    }

    @Test
    fun providerSkillsUseTheRequestedWorkspaceAndExcludeDisabledOrAgentOnlySkills() {
        val provider =
            json(
                """{"skills":[{"name":"global","enabled":true}],"workspaceSnapshots":[{"cwd":"/other","skills":[{"name":"wrong","enabled":true}]},{"cwd":"/project","skills":[{"name":"allowed","displayName":"Allowed","enabled":true},{"name":"off","enabled":false},{"name":"agent","enabled":true,"userInvocable":false},{"name":"user-only","enabled":true,"userInvocationOnly":true}]}]}"""
            )
        assertEquals(
            listOf("allowed", "user-only"),
            parseComposerSkills(provider, "/project").map { it.name },
        )
        assertEquals(listOf("global"), parseComposerSkills(provider, "/unknown").map { it.name })
        assertTrue(parseComposerSkills(json("{}"), "/project").isEmpty())
    }

    private fun context(records: List<JsonObject>) = buildJsonObject {
        put("version", 1)
        put("records", JsonArray(records))
    }

    private fun json(source: String) = Json.parseToJsonElement(source).jsonObject
}
