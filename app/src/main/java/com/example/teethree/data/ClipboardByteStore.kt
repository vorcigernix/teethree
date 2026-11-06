package com.example.teethree.data

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ClipboardSourceBytes(
    val sourceEnvironmentId: String,
    val attachmentId: String,
    val input: InputStream,
    private val release: () -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true))
            try {
                input.close()
            } finally {
                release()
            }
    }
}

fun interface ClipboardAttachmentSource {
    suspend fun open(sourceEnvironmentId: String, attachment: ChatAttachment): ClipboardSourceBytes
}

@Serializable
private data class RetainedClipboardFile(
    val environmentId: String,
    val attachmentId: String,
    val ownerBinding: String,
    val storedName: String,
    val sizeBytes: Long,
)

/** Local callers supply a verified account/connection owner, never a path from clipboard data. */
class ClipboardByteStore(private val root: File) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun retain(
        sourceEnvironmentId: String,
        attachment: ChatAttachment,
        ownerBinding: String,
        bytes: ClipboardSourceBytes,
    ) {
        var owned: File? = null
        try {
            withContext(Dispatchers.IO) {
                lock.withLock {
                    require(ownerBinding.isNotBlank() && sourceEnvironmentId.isNotBlank())
                    require(
                        bytes.sourceEnvironmentId == sourceEnvironmentId &&
                            bytes.attachmentId == attachment.id
                    ) {
                        "The source attachment binding does not match."
                    }
                    check(root.isDirectory || root.mkdirs())
                    val file = File(root, UUID.randomUUID().toString()).also { owned = it }
                    copyClipboardBytes(bytes.input, file, clipboardFileLimit(attachment))
                    val record =
                        RetainedClipboardFile(
                            sourceEnvironmentId,
                            attachment.id,
                            ownerBinding,
                            file.name,
                            file.length(),
                        )
                    val manifest =
                        File(root, key(sourceEnvironmentId, attachment.id, ownerBinding) + ".json")
                    val previous = readRecord(manifest)
                    val pending = File.createTempFile("manifest-", ".pending", root)
                    try {
                        FileOutputStream(pending).use { output ->
                            output.write(json.encodeToString(record).toByteArray())
                            output.fd.sync()
                        }
                        Files.move(
                            pending.toPath(),
                            manifest.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } finally {
                        pending.delete()
                    }
                    owned = null
                    previous
                        ?.storedName
                        ?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
                        ?.let { File(root, it).delete() }
                }
            }
        } finally {
            try {
                bytes.close()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { owned?.delete() }
            }
        }
    }

    suspend fun open(
        sourceEnvironmentId: String,
        attachment: ChatAttachment,
        ownerBinding: String,
    ): ClipboardSourceBytes? {
        var acquired: ClipboardSourceBytes? = null
        return try {
            withContext(Dispatchers.IO) {
                lock.withLock {
                    val manifest =
                        File(root, key(sourceEnvironmentId, attachment.id, ownerBinding) + ".json")
                    val value = readRecord(manifest) ?: return@withLock null
                    if (
                        value.environmentId != sourceEnvironmentId ||
                            value.attachmentId != attachment.id ||
                            value.ownerBinding != ownerBinding ||
                            !value.storedName.matches(Regex("[a-f0-9-]{36}"))
                    )
                        return@withLock null
                    val file = File(root, value.storedName)
                    if (
                        file.canonicalFile.parentFile != root.canonicalFile ||
                            !file.isFile ||
                            file.length() != value.sizeBytes ||
                            file.length() !in 1..clipboardFileLimit(attachment)
                    )
                        return@withLock null
                    ClipboardSourceBytes(sourceEnvironmentId, attachment.id, file.inputStream())
                        .also { acquired = it }
                }
            }
        } catch (failure: Throwable) {
            runCatching { acquired?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    suspend fun removeOwner(ownerBinding: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                root
                    .listFiles()
                    .orEmpty()
                    .filter { it.extension == "json" }
                    .forEach { manifest ->
                        val value = readRecord(manifest)
                        if (value?.ownerBinding == ownerBinding) {
                            if (value.storedName.matches(Regex("[a-f0-9-]{36}")))
                                File(root, value.storedName).delete()
                            manifest.delete()
                        }
                    }
            }
        }

    suspend fun bindings(ownerBinding: String): Set<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                root
                    .listFiles()
                    .orEmpty()
                    .filter { it.extension == "json" }
                    .mapNotNull(::readRecord)
                    .filter { it.ownerBinding == ownerBinding }
                    .mapTo(mutableSetOf()) { it.environmentId to it.attachmentId }
            }
        }

    suspend fun keepOnly(ownerBinding: String, bindings: Set<Pair<String, String>>) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                root
                    .listFiles()
                    .orEmpty()
                    .filter { it.extension == "json" }
                    .forEach { manifest ->
                        val value = readRecord(manifest)
                        if (
                            value?.ownerBinding == ownerBinding &&
                                (value.environmentId to value.attachmentId) !in bindings
                        ) {
                            if (value.storedName.matches(Regex("[a-f0-9-]{36}")))
                                File(root, value.storedName).delete()
                            manifest.delete()
                        }
                    }
            }
        }

    private fun readRecord(file: File): RetainedClipboardFile? = runCatching {
        require(file.length() in 1..16_384)
        json.decodeFromString<RetainedClipboardFile>(file.readText())
    }
        .getOrNull()

    private fun key(environmentId: String, attachmentId: String, owner: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                "${environmentId.length}:$environmentId${attachmentId.length}:$attachmentId$owner"
                    .toByteArray()
            )
            .joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun clipboardFileLimit(attachment: ChatAttachment): Long =
    (if (attachment.type == "image") 10L else 50L) * 1024 * 1024

internal suspend fun copyClipboardBytes(input: InputStream, output: File, maxBytes: Long) {
    var size = 0L
    output.outputStream().use { sink ->
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= maxBytes) { "The copied attachment exceeds its byte limit." }
            sink.write(buffer, 0, count)
        }
    }
    require(size > 0) { "The copied attachment is empty." }
}
