package com.example.teethree.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedConversation(
    val threadId: String,
    val timeline: List<TimelineItem>,
    /** Shown with the saved chat so a waiting question doesn't pop in and shove it on open. */
    val pendingRequests: List<RuntimeRequest> = emptyList(),
)

@Serializable
data class CachedEnvironment(
    val projects: List<T3Project> = emptyList(),
    val threads: List<T3Thread> = emptyList(),
    val providers: List<T3Provider> = emptyList(),
    val selectedThreadId: String? = null,
    val conversations: List<CachedConversation> = emptyList(),
    /** When this copy last matched the desktop: "Updated 2 h ago" on the next launch. */
    val savedAt: Long? = null,
)

internal fun updateOfflineCache(
    previous: CachedEnvironment?,
    state: RepositoryState,
): CachedEnvironment {
    val known = state.threads.map { it.id }.toSet()
    val selection = state.selectedThreadId
    val cached =
        previous?.conversations.orEmpty().filter {
            it.threadId in known && it.threadId != selection
        }
    val current =
        if (selection != null && !state.threadLoading)
            listOf(
                CachedConversation(
                    selection,
                    state.timeline.takeLast(200).map {
                        it.copy(
                            streaming = false,
                            requestId = null,
                            options = emptyList(),
                            mcpApp = null,
                        )
                    },
                    state.pendingRequests,
                )
            )
        else previous?.conversations.orEmpty().filter { it.threadId == selection }
    var result =
        CachedEnvironment(
            state.projects,
            state.threads,
            state.providers,
            selection,
            (current + cached).take(8),
            if (state.connectionStatus == ConnectionStatus.Connected) System.currentTimeMillis()
            else state.lastLiveAt ?: previous?.savedAt,
        )
    return boundOfflineCache(result)
}

internal fun updateBackgroundOfflineCache(
    previous: CachedEnvironment?,
    state: RepositoryState,
    includeArchived: Boolean,
): CachedEnvironment {
    val incomingIds = state.threads.map { it.id }.toSet()
    val threads =
        state.threads +
            if (includeArchived) emptyList()
            else previous?.threads.orEmpty().filter { it.archived && it.id !in incomingIds }
    val known = threads.map { it.id }.toSet()
    return boundOfflineCache(
        CachedEnvironment(
            projects = state.projects,
            threads = threads,
            providers = state.providers,
            selectedThreadId = previous?.selectedThreadId?.takeIf { it in known },
            conversations = previous?.conversations.orEmpty().filter { it.threadId in known },
            savedAt = previous?.savedAt,
        )
    )
}

private fun boundOfflineCache(value: CachedEnvironment): CachedEnvironment {
    var result = value
    while (
        Json.encodeToString(result).length > 2_000_000 && result.conversations.isNotEmpty()
    ) result = result.copy(conversations = result.conversations.dropLast(1))
    return result
}
