package com.example.teethree.data

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentFormatRulesTest {
    @Test
    fun pdfLinksResolveAgainstTheirDocumentWithoutCredentialsOrActiveSchemes() {
        assertEquals(
            "https://host.example/docs/next.pdf#page=2",
            resolvePreviewPdfLink("next.pdf#page=2", "https://host.example/docs/current.pdf"),
        )
        assertEquals("https://other.example/", resolvePreviewPdfLink("https://other.example"))
        for (href in
            listOf(
                "javascript:alert(1)",
                "file:///private/file",
                "content://private/file",
                "https://user:secret@host.example/",
                "",
            )) {
            assertNull(href, resolvePreviewPdfLink(href, "https://host.example/docs/current.pdf"))
        }
        assertNull(resolvePreviewPdfLink("relative.pdf"))
    }

    @Test
    fun imageSamplingBoundsHugeAndPanoramicImagesWithoutUpscalingSmallImages() {
        assertEquals(1, imageDecodeSample(1000, 1000))
        for ((width, height) in
            listOf(
                12000 to 8000,
                20_000_000 to 1,
                1 to 20_000_000,
                Int.MAX_VALUE to Int.MAX_VALUE,
            )) {
            val sample = imageDecodeSample(width, height)
            assertTrue(
                (width.toLong() + sample - 1) / sample *
                    ((height.toLong() + sample - 1) / sample) <= 4_000_000
            )
        }
        assertEquals(2000 to 1000, fittedImageSize(8000, 4000))
        assertEquals(100 to 50, fittedImageSize(100, 50))
        assertThrows(IllegalArgumentException::class.java) { imageDecodeSample(0, 10) }
    }

    @Test
    fun csvHandlesQuotedSeparatorsEscapedQuotesMultilineAndBom() {
        val result =
            parseDelimitedAttachment("\uFEFFname,note\r\n\"A,B\",\"one\n\"\"two\"\"\"\r\n", ',')
        assertEquals(listOf(listOf("name", "note"), listOf("A,B", "one\n\"two\"")), result.rows)
        assertFalse(result.truncated)
        assertEquals(
            listOf(listOf("A", "B\tC")),
            parseDelimitedAttachment("A\t\"B\tC\"", '\t').rows,
        )
        assertTrue(parseDelimitedAttachment("\"unclosed", ',').truncated)
    }

    @Test
    fun tableBoundsRowsColumnsAndSurrogatePairsWithExplicitPartialFlag() {
        val text = ((0..100).joinToString("\n") { (0..30).joinToString(",") { "cell" } })
        val table = parseDelimitedAttachment(text, ',')
        assertEquals(100, table.rows.size)
        assertEquals(30, table.rows.first().size)
        assertTrue(table.truncated)
        val cell = parseDelimitedAttachment("a".repeat(1999) + "\uD83D\uDE00", ',')
        assertEquals(1999, cell.rows.single().single().length)
        assertTrue(cell.truncated)
    }

    @Test
    fun zipMetadataRejectsTraversalAndOversizedDirectoriesBeforeExtraction() = runBlocking {
        val root = Files.createTempDirectory("native-zip-test").toFile()
        try {
            val valid = zip(root, "valid.zip", "folder/hello.txt", "Hello".toByteArray())
            val entries = listAttachmentZip(valid)
            assertEquals("folder/hello.txt", entries.single().name)
            val cache = File(root, "cache")
            val extracted = extractAttachmentZipEntry(valid, entries.single(), cache)
            assertEquals("Hello", extracted.readText())
            assertEquals(cache.canonicalFile, extracted.parentFile.canonicalFile)
            assertFalse(File(cache, "folder").exists())
            val traversal = zip(root, "bad.zip", "../outside.txt", "bad".toByteArray())
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { listAttachmentZip(traversal) }
            }
            val bytes = valid.readBytes()
            bytes[bytes.size - 22 + 10] = 1
            bytes[bytes.size - 22 + 11] = 8
            valid.writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java) {
                checkAttachmentZipDirectory(valid)
            }
            assertFalse(File(root, "outside.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun zipExpansionAndPathGuardsRejectBombsAndWindowsTraversal() = runBlocking {
        val root = Files.createTempDirectory("native-zip-test").toFile()
        try {
            val bomb = zip(root, "bomb.zip", "zeros.txt", ByteArray(100_000))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { listAttachmentZip(bomb) }
            }
            listOf(
                    "/absolute.txt",
                    "C:\\private.txt",
                    "folder\\..\\private.txt",
                    "folder/../private.txt",
                    "nul\u0000.txt",
                )
                .forEach { assertFalse(it, safeArchiveEntryPath(it)) }
            val utf16 =
                File(root, "text.txt").apply {
                    writeBytes(
                        byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
                            "Hello".toByteArray(Charsets.UTF_16LE)
                    )
                }
            assertEquals("Hello", readAttachmentText(utf16).text)
            assertTrue(readAttachmentText(utf16, 4).truncated)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zip(root: File, name: String, path: String, bytes: ByteArray): File =
        File(root, name).also { file ->
            ZipOutputStream(file.outputStream()).use { output ->
                output.putNextEntry(ZipEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
        }
}
