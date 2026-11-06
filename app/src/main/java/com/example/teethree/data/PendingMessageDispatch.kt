package com.example.teethree.data

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PendingMessageDispatch(
    val connectionId: String,
    val threadId: String,
    val commandId: String,
    val messageId: String,
    val text: String,
    val attachments: List<JsonObject>,
    val context: JsonObject?,
    val dispatchMode: JsonObject,
) {
    internal fun matches(
        connectionId: String,
        threadId: String,
        text: String,
        attachments: List<JsonObject>,
        context: JsonObject?,
    ): Boolean =
        this.connectionId == connectionId &&
            this.threadId == threadId &&
            this.text == text &&
            this.attachments == attachments &&
            this.context == context

    internal fun wire(): JsonObject {
        require(
            listOf(connectionId, threadId, commandId, messageId).all {
                it.isNotBlank() && it == it.trim()
            }
        ) {
            "The saved send attempt has invalid identifiers."
        }
        validateMessageInput(text, attachments)
        validateComposerContext(context)
        require(
            dispatchMode.string("type") in
                setOf("start_immediately", "queue_after_active", "steer_active")
        )
        if (dispatchMode.string("type") == "steer_active") {
            require(dispatchMode.string("targetRunId").let { it.isNotBlank() && it == it.trim() })
            require(dispatchMode.keys == setOf("type", "targetRunId"))
        } else require(dispatchMode.keys == setOf("type"))
        return buildJsonObject {
            put("type", "message.dispatch")
            put("commandId", commandId)
            put("messageId", messageId)
            put("createdBy", "user")
            put("creationSource", "mobile")
            put("threadId", threadId)
            put("text", text)
            put("attachments", JsonArray(attachments))
            put("dispatchMode", dispatchMode)
            context?.let { put("context", it) }
        }
    }
}

fun prepareDraftDispatch(
    draft: ComposerDraft,
    connectionId: String,
    threadId: String,
    activeRunId: String?,
    followUpBehavior: String,
): ComposerDraft {
    val attachments = draft.attachments.map(ChatAttachment::wire)
    val context = referencedComposerContext(draft.text, draft.context)
    validateMessageInput(draft.text, attachments)
    require(connectionId.isNotBlank() && threadId.isNotBlank())
    draft.pendingDispatch
        ?.takeIf { it.matches(connectionId, threadId, draft.text, attachments, context) }
        ?.let {
            it.wire()
            return draft
        }
    return draft.copy(
        pendingDispatch =
            PendingMessageDispatch(
                connectionId,
                threadId,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                draft.text,
                attachments,
                context,
                messageDispatchMode(activeRunId, followUpBehavior),
            )
    )
}
