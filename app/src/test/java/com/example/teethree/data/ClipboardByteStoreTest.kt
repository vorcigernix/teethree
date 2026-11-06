package com.example.teethree.data

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardByteStoreTest {
    @Test
    fun retainedBytesSurviveOriginalRemovalButCannotCrossSourceOrAccountBindings() = runBlocking {
        val root = Files.createTempDirectory("clipboard-owned").toFile()
        try {
            val original = File(root, "original.txt").apply { writeText("Original data") }
            val attachment =
                ChatAttachment("asset", "file", original.name, "text/plain", original.length())
            val store = ClipboardByteStore(File(root, "owned"))
            var released = 0
            val source =
                ClipboardSourceBytes("environment", "asset", original.inputStream()) { released++ }
            store.retain("environment", attachment, "account-a", source)
            source.close()
            assertTrue(released == 1)
            original.delete()
            assertArrayEquals(
                "Original data".toByteArray(),
                store.open("environment", attachment, "account-a")!!.use { it.input.readBytes() },
            )
            assertNull(store.open("environment", attachment, "account-b"))
            assertNull(store.open("different-environment", attachment, "account-a"))
            assertNull(
                store.open("environment", attachment.copy(id = "different-asset"), "account-a")
            )
            val second = attachment.copy(id = "second")
            store.retain(
                "environment",
                second,
                "account-a",
                ClipboardSourceBytes("environment", "second", ByteArrayInputStream(byteArrayOf(2))),
            )
            assertEquals(
                setOf("environment" to "asset", "environment" to "second"),
                store.bindings("account-a"),
            )
            store.keepOnly("account-a", setOf("environment" to "second"))
            assertNull(store.open("environment", attachment, "account-a"))
            assertArrayEquals(
                byteArrayOf(2),
                store.open("environment", second, "account-a")!!.use { it.input.readBytes() },
            )
            store.removeOwner("account-a")
            assertNull(store.open("environment", attachment, "account-a"))
            assertTrue(File(root, "owned").listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun forgedManifestPathsAndMismatchedReturnedSourceAreNeverRead() = runBlocking {
        val root = Files.createTempDirectory("clipboard-guard").toFile()
        try {
            val attachment = ChatAttachment("asset", "file", "file.txt", "text/plain", 1)
            val store = ClipboardByteStore(root)
            store.retain(
                "environment",
                attachment,
                "account",
                ClipboardSourceBytes("environment", "asset", ByteArrayInputStream(byteArrayOf(1))),
            )
            val manifest = root.listFiles()!!.single { it.extension == "json" }
            manifest.writeText(
                manifest
                    .readText()
                    .replace(Regex("\"storedName\":\"[^\"]+\""), "\"storedName\":\"../outside\"")
            )
            assertNull(store.open("environment", attachment, "account"))
            val failure = runCatching {
                store.retain(
                    "environment",
                    attachment,
                    "account",
                    ClipboardSourceBytes("different", "asset", ByteArrayInputStream(byteArrayOf(2))),
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally {
            root.deleteRecursively()
        }
    }
}
