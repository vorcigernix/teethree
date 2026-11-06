package com.example.teethree.data

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextClipboardImporterTest {
    @Test
    fun aLateDraftLimitFailureCanReleaseAllNewUploadsAndDeleteFailuresDoNotBlockOtherCleanup() =
        runBlocking {
            val directory = Files.createTempDirectory("clipboard-rollback").toFile()
            try {
                val destination = MemoryDestination()
                val importer =
                    ContextClipboardImporter(
                        destination,
                        directory,
                        ClipboardAttachmentSource { environment, attachment ->
                            ClipboardSourceBytes(
                                environment,
                                attachment.id,
                                ByteArrayInputStream(byteArrayOf(1)),
                            )
                        },
                    )
                val prepared =
                    importer.prepare(
                        payload(file("first"), file("second")),
                        destination.target,
                        ComposerDraft(),
                    )!!
                val crowded =
                    ComposerDraft(
                        "Edited during import",
                        List(100) {
                            ChatAttachment("existing-$it", "file", "file", "text/plain", 1)
                        },
                    )
                assertTrue(runCatching { mergePreparedContextPaste(crowded, prepared) }.isFailure)
                destination.failedDeletes = setOf(prepared.attachments.first().id)
                assertTrue(runCatching { importer.rollback(prepared) }.isFailure)
                assertEquals(destination.failedDeletes, destination.files.keys)
                destination.failedDeletes = emptySet()
                importer.rollback(prepared)
                assertTrue(destination.files.isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun importCopiesOriginalBytesAndKeepsFailedReferencesVisibleWhilePreservingExistingDraft() =
        runBlocking {
            val directory = Files.createTempDirectory("clipboard-import").toFile()
            try {
                val destination = MemoryDestination()
                val bytes = "Original bytes".toByteArray()
                val importer =
                    ContextClipboardImporter(
                        destination,
                        directory,
                        ClipboardAttachmentSource { environment, attachment ->
                            if (attachment.id == "missing") error("Source unavailable")
                            ClipboardSourceBytes(
                                environment,
                                attachment.id,
                                ByteArrayInputStream(bytes),
                            )
                        },
                    )
                val existing = ComposerDraft("Existing", importedShareIds = setOf("share"))
                val prepared =
                    importer.prepare(
                        payload(file("source-file"), file("missing")),
                        destination.target,
                        existing,
                    )!!
                val merged = mergePreparedContextPaste(existing, prepared)
                assertArrayEquals(bytes, destination.files.values.single())
                assertEquals(1, merged.attachments.size)
                assertTrue(merged.text.startsWith("Existing\n\n"))
                assertEquals(existing.importedShareIds, merged.importedShareIds)
                assertEquals(1, prepared.context.array("records").size)
                assertTrue(prepared.warnings.single().contains("missing.txt"))
                assertTrue(
                    composerEditableText(prepared.text, prepared.context)
                        .contains("t3-context://v1/file/")
                )
                val edited =
                    merged.copy(
                        text =
                            replaceComposerDraftText(merged, "Typed while persistence was pending")
                    )
                val reverted = removePreparedContextPaste(edited, prepared)
                assertTrue(reverted.text.startsWith("Typed while persistence was pending"))
                assertTrue(
                    composerEditableText(reverted.text, reverted.context)
                        .contains("t3-context://v1/file/")
                )
                assertEquals(existing.attachments, reverted.attachments)
                assertEquals(existing.importedShareIds, reverted.importedShareIds)
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                importer.rollback(prepared)
                assertTrue(destination.files.isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun selectingAnotherThreadAfterUploadCancelsImportAndRollsBackOnlyTheOriginalEnvironment() =
        runBlocking {
            val directory = Files.createTempDirectory("clipboard-cancel").toFile()
            try {
                val destination = MemoryDestination()
                destination.afterUpload = {
                    destination.target = destination.target.copy(threadId = "other")
                }
                val importer =
                    ContextClipboardImporter(
                        destination,
                        directory,
                        ClipboardAttachmentSource { environment, attachment ->
                            ClipboardSourceBytes(
                                environment,
                                attachment.id,
                                ByteArrayInputStream(byteArrayOf(1)),
                            )
                        },
                    )
                val failure = runCatching {
                    importer.prepare(
                        payload(file("source-file")),
                        destination.target,
                        ComposerDraft(),
                    )
                }.exceptionOrNull()
                assertTrue(failure is CancellationException)
                assertTrue(destination.files.isEmpty())
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun wrongSourceBindingNeverUploadsAndRollbackCannotDeleteIntoAnotherEnvironment() =
        runBlocking {
            val directory = Files.createTempDirectory("clipboard-binding").toFile()
            try {
                val destination = MemoryDestination()
                val importer =
                    ContextClipboardImporter(
                        destination,
                        directory,
                        ClipboardAttachmentSource { _, attachment ->
                            ClipboardSourceBytes(
                                "unrelated",
                                attachment.id,
                                ByteArrayInputStream(byteArrayOf(1)),
                            )
                        },
                    )
                val prepared =
                    importer.prepare(
                        payload(file("source-file")),
                        destination.target,
                        ComposerDraft(),
                    )!!
                assertTrue(prepared.attachments.isEmpty())
                assertTrue(destination.files.isEmpty())
                assertTrue(prepared.warnings.isNotEmpty())
                val originalTarget = destination.target
                destination.files["foreign-asset"] = byteArrayOf(3)
                destination.target =
                    destination.target.copy(connectionId = "other", environmentId = "other")
                val failure = runCatching {
                    importer.rollback(
                        prepared.copy(
                            target = originalTarget,
                            attachments =
                                listOf(
                                    ChatAttachment("foreign-asset", "file", "file", "text/plain", 1)
                                ),
                        )
                    )
                }.exceptionOrNull()
                assertTrue(failure is CancellationException)
                assertTrue(destination.files.containsKey("foreign-asset"))
            } finally {
                directory.deleteRecursively()
            }
        }

    private class MemoryDestination : ClipboardDestinationTransport {
        var target = ClipboardTarget("connection", 1, "thread", "destination")
        val files = linkedMapOf<String, ByteArray>()
        var afterUpload: () -> Unit = {}
        var failedDeletes: Set<String> = emptySet()

        override fun isSameEnvironment(target: ClipboardTarget) =
            this.target.connectionId == target.connectionId &&
                this.target.environmentId == target.environmentId

        override fun isCurrent(target: ClipboardTarget) = this.target == target

        override suspend fun upload(
            target: ClipboardTarget,
            file: File,
            attachment: ChatAttachment,
        ): ChatAttachment {
            val id = "uploaded-${files.size}"
            files[id] = file.readBytes()
            afterUpload()
            return attachment.copy(id = id, sizeBytes = file.length())
        }

        override suspend fun delete(target: ClipboardTarget, attachment: ChatAttachment) {
            check(isSameEnvironment(target))
            check(attachment.id !in failedDeletes)
            files.remove(attachment.id)
        }
    }

    private fun file(id: String): JsonObject = buildJsonObject {
        put("version", 1)
        put("kind", "file")
        put("contextId", id)
        put("label", "$id.txt")
        put("attachmentId", id)
        put("name", "$id.txt")
        put("mimeType", "text/plain")
        put("sizeBytes", 1)
    }

    private fun payload(vararg records: JsonObject) =
        encodeContextClipboard(
            records.joinToString(" ", transform = ::composerContextReference),
            ClipboardSource("source"),
            contextOf(records.toList()),
        )
}
