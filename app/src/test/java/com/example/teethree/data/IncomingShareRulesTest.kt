package com.example.teethree.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingShareRulesTest {
    @Test
    fun sharedDraftEditorRemovesFilesButCannotReplaceTrustedStagedMetadata() {
        val removed = IncomingSharedFile("first", "first.txt", "first.txt", "text/plain", 10)
        val kept = IncomingSharedFile("second", "second.txt", "second.txt", "text/plain", 20)
        val current =
            IncomingSharedDraft(
                "original",
                12,
                "Original",
                listOf(removed, kept),
                listOf("Original warning"),
            )
        val untrusted =
            current.copy(
                id = "replacement",
                createdAt = 99,
                text = "Edited",
                files =
                    listOf(
                        kept.copy(
                            storedName = "../../private",
                            name = "replacement",
                            sizeBytes = 90,
                        ),
                        IncomingSharedFile("injected", "new.txt", "new.txt", "text/plain", 1),
                    ),
                warnings = emptyList(),
            )
        val updated = editIncomingShare(current, untrusted)
        assertEquals(listOf(kept), updated.files)
        assertEquals("Edited", updated.text)
        assertEquals(current.id, updated.id)
        assertEquals(current.createdAt, updated.createdAt)
        assertEquals(current.warnings, updated.warnings)
        assertThrows(IllegalArgumentException::class.java) {
            editIncomingShare(current, untrusted.copy(text = "x".repeat(1_000_001)))
        }
    }

    @Test
    fun incomingShareOnlyReadsExternalContentProviders() {
        assertTrue(
            safeIncomingShareUri(
                "content://com.android.providers.media.documents/document/image%3A42",
                "com.example.teethree",
            )
        )
        listOf(
                "file:///data/user/0/com.example.teethree/files/credentials",
                "https://host/private",
                "content://com.example.teethree.files/attachments/secret",
                "content://COM.EXAMPLE.TEETHREE.files/attachments/secret",
                "content://user@provider/file",
                "content://provider/folder/%2e%2e/private",
            )
            .forEach { value ->
                assertFalse(value, safeIncomingShareUri(value, "com.example.teethree"))
            }
    }

    @Test
    fun importingPreservesExistingDraftAndMakesRepeatedTextMergeIdempotent() {
        assertEquals("Existing\n\nShared", mergeIncomingShareText("Existing", "Shared"))
        assertEquals("Existing", mergeIncomingShareText("Existing", "  "))
        assertEquals("Shared", mergeIncomingShareText("", "Shared"))
        assertEquals("Shared", mergeIncomingShareText("Shared", "Shared"))
        val old = ChatAttachment("old", "file", "old.txt", "text/plain", 10)
        val added = ChatAttachment("new", "file", "new.txt", "text/plain", 20)
        val existing = ComposerDraft("Existing", listOf(old), setOf("previous"))
        val prepared = PreparedIncomingShare("share", "environment", "Shared", listOf(added))
        val merged = mergePreparedIncomingShare(existing, prepared)
        assertEquals("Existing\n\nShared", merged.text)
        assertEquals(listOf(old, added), merged.attachments)
        assertEquals(setOf("previous", "share"), merged.importedShareIds)
        assertSame(merged, mergePreparedIncomingShare(merged, prepared))
    }

    @Test
    fun preparedShareMergeRejectsMessageAndAttachmentLimitsWithoutMarkingConsumed() {
        val existing =
            ComposerDraft(
                "Existing",
                List(100) { ChatAttachment("$it", "file", "$it.txt", "text/plain", 1) },
            )
        val prepared =
            PreparedIncomingShare(
                "share",
                "environment",
                "Shared",
                listOf(ChatAttachment("new", "file", "new.txt", "text/plain", 1)),
            )
        assertThrows(IllegalArgumentException::class.java) {
            mergePreparedIncomingShare(existing, prepared)
        }
        assertTrue(existing.importedShareIds.isEmpty())
        assertEquals(100, existing.attachments.size)
        assertThrows(IllegalArgumentException::class.java) {
            mergePreparedIncomingShare(
                ComposerDraft("x".repeat(120_000)),
                prepared.copy(attachments = emptyList()),
            )
        }
        val images =
            ComposerDraft(
                "Images",
                List(8) {
                    ChatAttachment("image$it", "image", "$it.png", "image/png", 10L * 1024 * 1024)
                },
            )
        assertThrows(IllegalArgumentException::class.java) {
            mergePreparedIncomingShare(
                images,
                prepared.copy(
                    attachments =
                        listOf(ChatAttachment("more", "image", "more.png", "image/png", 1))
                ),
            )
        }
    }

    @Test
    fun browserPdfRewritesLoopbackWithoutAllowingOtherEnvironmentCredentials() {
        assertEquals(
            "/api/assets/a.pdf?token=one#page=3",
            browserPdfLocation(
                "http://127.0.0.1:3773/api/assets/a.pdf?token=one#page=3",
                "https://remote.test",
            ),
        )
        assertEquals(
            null,
            browserPdfLocation(
                "https://other.test/api/assets/a.pdf?token=one",
                "https://remote.test",
            ),
        )
        assertEquals(
            null,
            browserPdfLocation(
                "https://user:secret@remote.test/api/assets/a.pdf",
                "https://remote.test",
            ),
        )
        assertEquals(
            null,
            browserPdfLocation("https://remote.test/api/assets/../admin.pdf", "https://remote.test"),
        )
    }
}
