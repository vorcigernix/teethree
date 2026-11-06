package com.example.teethree.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class SavedConnection(
    val id: String,
    val baseUrl: String,
    val accessToken: String,
    val scopes: Set<String>,
    val expiresAt: Long,
    val tokenType: String = "Bearer",
    val wsBaseUrl: String? = null,
    val environmentId: String? = null,
    val cloudAccountId: String? = null,
    val label: String? = null,
    val connectionMethod: String = "direct",
    val learnedRoutes: List<DirectConnectionRoute> = emptyList(),
    @kotlinx.serialization.Transient val selectedTransport: DirectConnectionRoute? = null,
)

@Serializable
data class ModelSelection(val instanceId: String, val model: String, val options: JsonArray? = null)

@Serializable
data class T3Project(
    val id: String,
    val title: String,
    val workspaceRoot: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val repositoryKey: String? = null,
    val repositoryRoot: String? = null,
    val repositoryLabel: String? = null,
    val defaultModelSelection: JsonObject? = null,
    val scripts: JsonArray = JsonArray(emptyList()),
    val defaultThreadEnvMode: String? = null,
    val isScratch: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val repositoryName: String? = null,
)

@Serializable
data class T3Thread(
    val id: String,
    val projectId: String,
    val title: String,
    val status: String,
    val modelSelection: ModelSelection,
    val activeRunId: String?,
    val archived: Boolean,
    val preview: String,
    val updatedAt: String,
    val runtimeMode: String = "approval-required",
    val interactionMode: String = "default",
    val worktreePath: String? = null,
    val pinned: Boolean = false,
    val snoozedUntil: String? = null,
    val settled: Boolean = false,
    val needsAttention: Boolean = false,
    val lineage: JsonObject = JsonObject(emptyMap()),
    val creationSource: String? = null,
    val tags: List<String> = emptyList(),
    val delegatedFrom: JsonObject? = null,
    val pendingBackgroundTasks: JsonArray = JsonArray(emptyList()),
    val goal: JsonObject? = null,
    val lastVisitedAt: String? = null,
    val snoozedAt: String? = null,
    val latestRunId: String? = null,
    val latestRunCompletedAt: String? = null,
    val latestRunStopRequested: Boolean = false,
    val limitRecovery: JsonObject? = null,
    val hasPendingRuntimeRequest: Boolean = false,
    val pendingGuestMessageCount: Int = 0,
)

@Serializable
data class T3Model(
    val slug: String,
    val name: String,
    val isDefault: Boolean,
    val optionDescriptors: JsonArray = JsonArray(emptyList()),
)

@Serializable
data class T3Provider(
    val instanceId: String,
    val displayName: String,
    val models: List<T3Model>,
    val available: Boolean,
    val supportsConversationRollback: Boolean = true,
)

@Serializable
data class ApprovalOption(val decision: String, val label: String, val warning: String? = null)

@Serializable
data class TimelineItem(
    val id: String,
    val type: String,
    val text: String,
    val title: String?,
    val status: String,
    val ordinal: Long,
    val streaming: Boolean,
    val requestId: String? = null,
    val options: List<ApprovalOption> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList(),
    val mcpApp: NativeMcpApp? = null,
    val searchEntryId: String? = null,
    val context: JsonObject? = null,
)

@Serializable
data class UserQuestion(
    val id: String,
    val prompt: String,
    val options: List<String>,
    val multiple: Boolean = false,
    val allowCustomAnswer: Boolean = true,
    val initialAnswer: String? = null,
    val required: Boolean = true,
)

@Serializable
data class RuntimeRequest(
    val id: String,
    val kind: String,
    val prompt: String,
    val canRespond: Boolean,
    val options: List<ApprovalOption>,
    val questions: List<UserQuestion>,
)

enum class ConnectionStatus {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
}

data class RepositoryState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val projects: List<T3Project> = emptyList(),
    val threads: List<T3Thread> = emptyList(),
    val providers: List<T3Provider> = emptyList(),
    val selectedThreadId: String? = null,
    val timeline: List<TimelineItem> = emptyList(),
    val pendingRequests: List<RuntimeRequest> = emptyList(),
    val subagents: JsonArray = JsonArray(emptyList()),
    val scopes: Set<String> = emptySet(),
    val error: String? = null,
    val threadLoading: Boolean = false,
    val hasOlderMessages: Boolean = false,
    val historyLoading: Boolean = false,
    val historyRevision: Long = 0L,
    val history: ConversationHistory? = null,
    val shellReady: Boolean = false,
    val serverConfig: JsonObject? = null,
    /** Failed connection rounds since the session was last live. */
    val reconnectAttempts: Int = 0,
    /** When the next automatic attempt starts, while waiting out a backoff. */
    val nextRetryAt: Long? = null,
    /** When the link last went down, or when the first attempt began. Null while live. */
    val offlineSince: Long? = null,
    /** The last moment the session was live, if it ever was in this process. */
    val lastLiveAt: Long? = null,
)
