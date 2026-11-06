package com.example.teethree.data

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ThreadControls(private val repository: T3Repository) {
    private suspend fun dispatch(
        threadId: String,
        type: String,
        fields: JsonObjectBuilder.() -> Unit = {},
    ) {
        repository.callTool(
            "orchestration.dispatchCommand",
            buildJsonObject {
                put("type", type)
                put("commandId", newId())
                put("threadId", threadId)
                fields()
            },
        )
    }

    suspend fun rename(threadId: String, title: String) {
        require(title.isNotBlank()) { "Enter a conversation title." }
        dispatch(threadId, "thread.metadata.update") { put("title", title.trim()) }
    }

    private fun requireEditableProviderSettings(threadId: String) {
        val thread =
            repository.state.value.threads.firstOrNull { it.id == threadId }
                ?: error("Conversation is unavailable.")
        check(!providerManagedChild(thread)) {
            "This provider manages the child agent's model and permissions."
        }
    }

    suspend fun setRuntimeMode(threadId: String, mode: String) {
        requireEditableProviderSettings(threadId)
        require(mode in setOf("approval-required", "auto-accept-edits", "auto", "full-access")) {
            "Choose a permission mode."
        }
        dispatch(threadId, "thread.runtime-mode.set") { put("runtimeMode", mode) }
    }

    suspend fun setInteractionMode(threadId: String, mode: String) {
        requireEditableProviderSettings(threadId)
        require(mode in setOf("default", "plan")) { "Choose an agent mode." }
        dispatch(threadId, "thread.interaction-mode.set") { put("interactionMode", mode) }
    }

    suspend fun setModel(threadId: String, model: ModelSelection) {
        requireEditableProviderSettings(threadId)
        dispatch(threadId, "thread.model-selection.set") {
            put(
                "modelSelection",
                model.wire(),
            )
        }
    }

    suspend fun setPinned(threadId: String, pinned: Boolean) =
        dispatch(threadId, if (pinned) "thread.pin" else "thread.unpin")

    suspend fun snooze(threadId: String, until: String) =
        dispatch(threadId, "thread.snooze") { put("snoozedUntil", until) }

    suspend fun unsnooze(threadId: String) =
        dispatch(threadId, "thread.unsnooze") { put("reason", "user") }

    suspend fun delete(threadId: String) = dispatch(threadId, "thread.delete")
}

internal fun messageDispatchMode(
    activeRunId: String?,
    behavior: String,
): kotlinx.serialization.json.JsonObject {
    require(behavior in setOf("queue", "steer")) { "Choose queue or steer for follow-up messages." }
    return kotlinx.serialization.json.buildJsonObject {
        put(
            "type",
            when {
                activeRunId == null -> "start_immediately"
                behavior == "steer" -> "steer_active"
                else -> "queue_after_active"
            },
        )
        if (activeRunId != null && behavior == "steer") put("targetRunId", activeRunId)
    }
}
