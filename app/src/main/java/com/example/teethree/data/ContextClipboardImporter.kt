package com.example.teethree.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ClipboardTarget(
    val connectionId: String,
    val connectionGeneration: Long,
    val threadId: String?,
    val environmentId: String,
)

data class PreparedContextPaste(
    val target: ClipboardTarget,
    val text: String,
    val context: JsonObject,
    val attachments: List<ChatAttachment>,
    val warnings: List<String>,
    val id: String = UUID.randomUUID().toString(),
)

internal interface ClipboardDestinationTransport {
    fun isSameEnvironment(target: ClipboardTarget): Boolean

    fun isCurrent(target: ClipboardTarget): Boolean

    suspend fun upload(
        target: ClipboardTarget,
        file: File,
        attachment: ChatAttachment,
    ): ChatAttachment

    suspend fun delete(target: ClipboardTarget, attachment: ChatAttachment)
}

private class RepositoryClipboardDestination(private val repository: T3Repository) :
    ClipboardDestinationTransport {
    override fun isSameEnvironment(target: ClipboardTarget): Boolean =
        repository.connectionId == target.connectionId &&
            repository.state.value.serverConfig?.obj("environment")?.string("environmentId") ==
                target.environmentId

    override fun isCurrent(target: ClipboardTarget): Boolean =
        isSameEnvironment(target) &&
            repository.connectionGeneration == target.connectionGeneration &&
            (target.threadId == null || repository.state.value.selectedThreadId == target.threadId)

    override suspend fun upload(
        target: ClipboardTarget,
        file: File,
        attachment: ChatAttachment,
    ): ChatAttachment {
        check(isCurrent(target)) { "The clipboard destination changed." }
        return AttachmentTransfers(repository)
            .upload(
                file,
                attachment.name,
                attachment.mimeType,
                expectedConnectionId = target.connectionId,
            )
    }

    override suspend fun delete(target: ClipboardTarget, attachment: ChatAttachment) {
        check(isSameEnvironment(target)) { "The clipboard destination changed." }
        repository.callTool(
            "attachments.delete",
            buildJsonObject { put("attachmentId", attachment.id) },
            expectedConnectionId = target.connectionId,
        )
    }
}

fun mergePreparedContextPaste(
    existing: ComposerDraft,
    prepared: PreparedContextPaste,
): ComposerDraft {
    validateComposerContext(existing.context)
    validateComposerContext(prepared.context)
    val attachments = existing.attachments + prepared.attachments
    val prose =
        mergeIncomingShareText(
            composerEditableText(existing.text, existing.context),
            composerEditableText(prepared.text, prepared.context),
        )
    val base =
        existing.copy(text = replaceComposerDraftText(existing, prose), attachments = attachments)
    val merged =
        appendComposerContext(base, prepared.context.array("records").map { it.jsonObject })
    return merged.copy(context = merged.context?.takeIf { it.array("records").isNotEmpty() })
}

fun removePreparedContextPaste(
    current: ComposerDraft,
    prepared: PreparedContextPaste,
): ComposerDraft {
    val ids = prepared.context.array("records").map { it.jsonObject.string("contextId") }.toSet()
    val assets = prepared.attachments.mapTo(mutableSetOf()) { it.id }
    val kept =
        current.context?.array("records").orEmpty().filter {
            (it as? JsonObject)?.string("contextId") !in ids
        }
    return current.copy(
        attachments = current.attachments.filterNot { it.id in assets },
        context =
            if (kept.isEmpty()) null
            else
                buildJsonObject {
                    put("version", 1)
                    put("records", JsonArray(kept))
                },
    )
}

class ContextClipboardImporter
internal constructor(
    private val destination: ClipboardDestinationTransport,
    private val directory: File,
    private val source: ClipboardAttachmentSource,
) {
    constructor(
        repository: T3Repository,
        directory: File,
        source: ClipboardAttachmentSource,
    ) : this(RepositoryClipboardDestination(repository), directory, source)

    suspend fun prepare(
        payload: ContextClipboardPayload,
        target: ClipboardTarget,
        existing: ComposerDraft,
        linkedEnvironmentIds: Set<String> = emptySet(),
    ): PreparedContextPaste? {
        ensureTarget(target)
        val imported =
            withContext(Dispatchers.Default) {
                reidentifyContextClipboard(payload, target.environmentId, linkedEnvironmentIds)
            } ?: return null
        require(imported.text.length <= 120_000) { "The pasted text exceeds the message limit." }
        val incoming = imported.context.array("records").map { it.jsonObject }
        require(composerContextItems(existing.context).size + incoming.size <= 200) {
            "Remove some context items before pasting more."
        }
        validateComposerContext(existing.context)
        val records = mutableListOf<JsonObject>()
        val uploaded = mutableListOf<ChatAttachment>()
        val warnings = imported.warnings.toMutableList()
        try {
            for (record in incoming) {
                currentCoroutineContext().ensureActive()
                ensureTarget(target)
                if (record.string("kind") !in setOf("image", "file")) {
                    records.add(record)
                    continue
                }
                val attachment =
                    ChatAttachment(
                        record.string("attachmentId"),
                        record.string("kind"),
                        record.string("name"),
                        record.string("mimeType"),
                        record.long("sizeBytes"),
                    )
                var file: File? = null
                try {
                    require(existing.attachments.size + uploaded.size < 100) {
                        "The attachment limit has been reached."
                    }
                    require(attachment.sizeBytes <= clipboardFileLimit(attachment)) {
                        "The copied attachment exceeds its byte limit."
                    }
                    val bytes = source.open(imported.source.environmentId, attachment)
                    try {
                        require(
                            bytes.sourceEnvironmentId == imported.source.environmentId &&
                                bytes.attachmentId == attachment.id
                        ) {
                            "The source returned a different attachment."
                        }
                        withContext(Dispatchers.IO) {
                            check(directory.isDirectory || directory.mkdirs())
                            val owned =
                                File.createTempFile("context-paste-", ".upload", directory).also {
                                    file = it
                                }
                            copyClipboardBytes(bytes.input, owned, clipboardFileLimit(attachment))
                        }
                    } finally {
                        bytes.close()
                    }
                    ensureTarget(target)
                    val local = requireNotNull(file)
                    if (attachment.type == "image")
                        require(
                            existing.attachments
                                .filter { it.type == "image" }
                                .sumOf { it.sizeBytes } +
                                uploaded.filter { it.type == "image" }.sumOf { it.sizeBytes } +
                                local.length() <= 80L * 1024 * 1024
                        ) {
                            "The pasted images exceed the message limit."
                        }
                    val transferred =
                        destination.upload(target, local, attachment).also(uploaded::add)
                    ensureTarget(target)
                    records.add(
                        JsonObject(
                            record +
                                mapOf(
                                    "attachmentId" to JsonPrimitive(transferred.id),
                                    "sizeBytes" to JsonPrimitive(transferred.sizeBytes),
                                )
                        )
                    )
                } catch (failure: Exception) {
                    if (failure is CancellationException || !destination.isCurrent(target))
                        throw failure
                    warnings.add(
                        "${attachment.name} could not be copied. Its reference remains unavailable; reconnect to its source and try again."
                    )
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { file?.delete() }
                }
            }
            currentCoroutineContext().ensureActive()
            ensureTarget(target)
            val prepared =
                PreparedContextPaste(
                    target,
                    imported.text,
                    contextOf(records),
                    uploaded.toList(),
                    warnings,
                )
            mergePreparedContextPaste(existing, prepared)
            return prepared
        } catch (failure: Throwable) {
            if (uploaded.isNotEmpty())
                runCatching {
                        rollback(
                            PreparedContextPaste(
                                target,
                                imported.text,
                                contextOf(records),
                                uploaded.toList(),
                                warnings,
                            )
                        )
                    }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            throw failure
        }
    }

    suspend fun rollback(prepared: PreparedContextPaste) =
        withContext(NonCancellable) {
            if (!destination.isSameEnvironment(prepared.target))
                throw CancellationException(
                    "Cannot remove clipboard uploads from another environment."
                )
            supervisorScope {
                prepared.attachments
                    .map { attachment ->
                        async { runCatching { destination.delete(prepared.target, attachment) } }
                    }
                    .awaitAll()
                    .firstNotNullOfOrNull { it.exceptionOrNull() }
                    ?.let { throw it }
            }
            Unit
        }

    private fun ensureTarget(target: ClipboardTarget) {
        if (!destination.isCurrent(target))
            throw CancellationException(
                "The environment or conversation changed while pasting context."
            )
    }
}
