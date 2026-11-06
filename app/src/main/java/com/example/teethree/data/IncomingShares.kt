package com.example.teethree.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.IntentCompat
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class IncomingSharedFile(
    val id: String,
    val storedName: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)

@Serializable
data class IncomingSharedDraft(
    val id: String,
    val createdAt: Long,
    val text: String,
    val files: List<IncomingSharedFile>,
    val warnings: List<String> = emptyList(),
)

data class PreparedIncomingShare(
    val id: String,
    val connectionId: String,
    val text: String,
    val attachments: List<ChatAttachment>,
)

internal fun editIncomingShare(
    current: IncomingSharedDraft,
    edited: IncomingSharedDraft,
): IncomingSharedDraft {
    require(edited.text.length <= 1_000_000) { "Shared drafts support at most 1 MB of text." }
    val keptIds = edited.files.mapTo(mutableSetOf()) { it.id }
    return current.copy(text = edited.text, files = current.files.filter { it.id in keptIds })
}

internal fun safeIncomingShareUri(raw: String, packageName: String): Boolean = runCatching {
    val uri = URI(raw)
    uri.scheme == "content" &&
        !uri.rawAuthority.isNullOrBlank() &&
        uri.userInfo == null &&
        !uri.rawAuthority.equals(packageName, true) &&
        !uri.rawAuthority.startsWith("$packageName.", true) &&
        uri.path.orEmpty().split('/').none { it == ".." }
}
    .getOrDefault(false)

internal fun mergeIncomingShareText(existing: String, shared: String) =
    when {
        shared.isBlank() || existing.trim() == shared.trim() -> existing
        existing.isBlank() -> shared
        else -> "$existing\n\n$shared"
    }

internal fun mergePreparedIncomingShare(
    existing: ComposerDraft,
    prepared: PreparedIncomingShare,
): ComposerDraft {
    if (prepared.id in existing.importedShareIds) return existing
    val text = mergeIncomingShareText(existing.text, prepared.text)
    val attachments = existing.attachments + prepared.attachments
    validateMessageInput(text, attachments.map(ChatAttachment::wire))
    return existing.copy(
        text = text,
        attachments = attachments,
        importedShareIds = existing.importedShareIds + prepared.id,
    )
}

class IncomingShareInbox(context: Context) {
    private val application = context.applicationContext
    private val root = File(application.filesDir, "incoming-shares")
    private val json = Json { ignoreUnknownKeys = true }
    internal val uploadCache: File
        get() = application.cacheDir

    suspend fun stage(intent: Intent): IncomingSharedDraft? {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return null
        val uris = buildList {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE)
                addAll(
                    IntentCompat.getParcelableArrayListExtra(
                            intent,
                            Intent.EXTRA_STREAM,
                            Uri::class.java,
                        )
                        .orEmpty()
                )
            else
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let(::add)
            intent.clipData?.let { clip ->
                repeat(clip.itemCount) { clip.getItemAt(it).uri?.let(::add) }
            }
        }
            .distinctBy(Uri::toString)
        val directText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val clipText =
            if (directText.isNullOrBlank())
                intent.clipData?.let { clip ->
                    (0 until clip.itemCount)
                        .mapNotNull { clip.getItemAt(it).text?.toString() }
                        .joinToString("\n\n")
                }
            else null
        val text =
            listOfNotNull(
                    intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
                    directText,
                    clipText,
                )
                .filter(String::isNotBlank)
                .distinct()
                .joinToString("\n\n")
        require(text.length <= 1_000_000) { "The shared text exceeds the 1 MB limit." }
        return withContext(Dispatchers.IO) {
            lock.withLock {
                check(root.isDirectory || root.mkdirs()) { "Could not create the share inbox." }
                require(
                    root.listFiles().orEmpty().count {
                        it.isDirectory && File(it, "draft.json").isFile
                    } < 20
                ) {
                    "The share inbox is full. Use or discard an earlier share first."
                }
                val existingBytes = root.walkTopDown().filter(File::isFile).sumOf(File::length)
                val id = UUID.randomUUID().toString()
                val directory =
                    File(root, id).apply { check(mkdir()) { "Could not stage the shared files." } }
                val files = mutableListOf<IncomingSharedFile>()
                val warnings = mutableListOf<String>()
                if (uris.size > 100) warnings.add("Only the first 100 shared files were kept.")
                var total = 0L
                try {
                    uris.take(100).forEach { uri ->
                        currentCoroutineContext().ensureActive()
                        if (!safeIncomingShareUri(uri.toString(), application.packageName)) {
                            warnings.add(
                                "A shared file used an unsupported or private URI and was skipped."
                            )
                            return@forEach
                        }
                        var staged: File? = null
                        try {
                            val resolver = application.contentResolver
                            val name =
                                resolver
                                    .query(
                                        uri,
                                        arrayOf(OpenableColumns.DISPLAY_NAME),
                                        null,
                                        null,
                                        null,
                                    )
                                    ?.use { cursor ->
                                        if (cursor.moveToFirst()) cursor.getString(0) else null
                                    }
                                    ?.substringAfterLast('/')
                                    ?.take(255)
                                    ?.ifBlank { null } ?: "shared-file"
                            val mime =
                                resolver.getType(uri)?.takeIf {
                                    Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+").matches(it)
                                } ?: "application/octet-stream"
                            val fileId = UUID.randomUUID().toString()
                            val extension =
                                name
                                    .substringAfterLast('.', "")
                                    .takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
                                    ?.let { ".$it" }
                                    .orEmpty()
                            val output = File(directory, fileId + extension)
                            staged = output
                            var size = 0L
                            checkNotNull(resolver.openInputStream(uri)) {
                                    "The shared file could not be opened."
                                }
                                .use { input ->
                                    output.outputStream().use { sink ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            size += count
                                            require(
                                                size <= 50L * 1024 * 1024 &&
                                                    total + size <= 100L * 1024 * 1024 &&
                                                    existingBytes + total + size <=
                                                        200L * 1024 * 1024
                                            ) {
                                                "Shared files exceed the 50 MiB file, 100 MiB share, or 200 MiB inbox limit."
                                            }
                                            sink.write(buffer, 0, count)
                                        }
                                    }
                                }
                            require(size > 0) { "The shared file is empty." }
                            total += size
                            files.add(IncomingSharedFile(fileId, output.name, name, mime, size))
                            staged = null
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            warnings.add(failure.message ?: "A shared file could not be read.")
                        } finally {
                            staged?.delete()
                        }
                    }
                    require(text.isNotBlank() || files.isNotEmpty()) {
                        warnings.firstOrNull() ?: "The share contained no text or supported files."
                    }
                    IncomingSharedDraft(id, System.currentTimeMillis(), text, files, warnings)
                        .also { write(it) }
                } catch (failure: Throwable) {
                    directory.deleteRecursively()
                    throw failure
                }
            }
        }
    }

    suspend fun list(): List<IncomingSharedDraft> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                root
                    .listFiles()
                    .orEmpty()
                    .filter(File::isDirectory)
                    .mapNotNull { directory ->
                        runCatching {
                                json.decodeFromString<IncomingSharedDraft>(
                                    File(directory, "draft.json").readText()
                                )
                            }
                            .getOrNull()
                    }
                    .sortedByDescending { it.createdAt }
            }
        }

    suspend fun save(draft: IncomingSharedDraft, isCurrent: () -> Boolean = { true }) =
        withContext(Dispatchers.IO) { lock.withLock { if (isCurrent()) write(draft) } }

    suspend fun discard(id: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                directory(id).deleteRecursively()
                Unit
            }
        }

    fun file(draft: IncomingSharedDraft, attachment: IncomingSharedFile): File {
        require(attachment.storedName.matches(Regex("[a-f0-9-]{36}(?:\\.[A-Za-z0-9]{1,12})?"))) {
            "The staged file name is invalid."
        }
        val directory = directory(draft.id)
        return File(directory, attachment.storedName).also {
            require(
                it.canonicalPath.startsWith(directory.canonicalPath + File.separator) &&
                    it.isFile &&
                    it.length() == attachment.sizeBytes
            ) {
                "The staged file is no longer available."
            }
        }
    }

    private fun directory(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            "The share id is invalid."
        }
        return File(root, id)
    }

    private fun write(draft: IncomingSharedDraft) {
        require(draft.text.length <= 1_000_000 && draft.files.size <= 100)
        val directory = directory(draft.id)
        require(directory.isDirectory) { "The share is no longer in the inbox." }
        draft.files.forEach { file(draft, it) }
        val atomic = AtomicFile(File(directory, "draft.json"))
        val output = atomic.startWrite()
        try {
            output.write(json.encodeToString(draft).toByteArray())
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    companion object {
        private val lock = Mutex()
    }
}

class IncomingShareImporter(
    private val repository: T3Repository,
    private val inbox: IncomingShareInbox,
) {
    suspend fun prepare(draft: IncomingSharedDraft): PreparedIncomingShare {
        val environment = repository.connectionId
        check(environment != null) { "Choose an environment before importing files." }
        val uploaded = mutableListOf<ChatAttachment>()
        try {
            draft.files.forEach { file ->
                currentCoroutineContext().ensureActive()
                check(repository.connectionId == environment) {
                    "The active environment changed. Import the share again."
                }
                var prepared: SharedUploadFile? = null
                try {
                    val normalized =
                        withContext(Dispatchers.IO) {
                            prepareSharedImage(
                                    inbox.file(draft, file),
                                    file.mimeType,
                                    file.name,
                                    inbox.uploadCache,
                                )
                                .also { prepared = it }
                        }
                    uploaded.add(
                        AttachmentTransfers(repository)
                            .upload(
                                normalized.file,
                                normalized.name,
                                normalized.mimeType,
                                expectedConnectionId = environment,
                            )
                    )
                } finally {
                    prepared
                        ?.takeIf { it.temporary }
                        ?.let { temporary ->
                            withContext(NonCancellable + Dispatchers.IO) { temporary.file.delete() }
                        }
                }
            }
            return PreparedIncomingShare(draft.id, environment, draft.text, uploaded)
        } catch (failure: Throwable) {
            if (repository.connectionId == environment)
                runCatching {
                    rollback(PreparedIncomingShare(draft.id, environment, draft.text, uploaded))
                }
            throw failure
        }
    }

    suspend fun rollback(prepared: PreparedIncomingShare) =
        withContext(NonCancellable) {
            check(repository.connectionId == prepared.connectionId) {
                "Cannot remove uploads from another environment."
            }
            supervisorScope {
                prepared.attachments
                    .map { attachment ->
                        async {
                            runCatching {
                                repository.callTool(
                                    "attachments.delete",
                                    buildJsonObject { put("attachmentId", attachment.id) },
                                    expectedConnectionId = prepared.connectionId,
                                )
                            }
                        }
                    }
                    .awaitAll()
                    .firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?.let { throw it }
            }
            Unit
        }
}
