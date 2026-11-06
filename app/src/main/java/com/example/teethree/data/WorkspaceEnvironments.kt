package com.example.teethree.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class WorkspaceProjectRef(
    val connectionId: String,
    val environmentId: String?,
    val projectId: String,
)

@Serializable
data class WorkspaceThreadRef(
    val connectionId: String,
    val environmentId: String?,
    val threadId: String,
)

enum class WorkspaceEnvironmentPhase {
    Connecting,
    Live,
    Cached,
    Queued,
    Disconnected,
}

data class WorkspaceEnvironmentShell(
    val connectionId: String,
    val environmentId: String?,
    val label: String,
    val phase: WorkspaceEnvironmentPhase,
    val projects: List<T3Project> = emptyList(),
    val threads: List<T3Thread> = emptyList(),
    val scopes: Set<String> = emptySet(),
    val error: String? = null,
    val serverConfig: JsonObject? = null,
    val shellReady: Boolean = false,
    val aliasConnectionIds: Set<String> = setOf(connectionId),
)

data class WorkspaceProjectGroup(
    val key: String,
    val label: String,
    val representative: WorkspaceProjectRef,
    val members: List<WorkspaceProjectRef>,
    val pinned: Boolean,
    val archived: Boolean,
)
