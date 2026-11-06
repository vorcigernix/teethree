package com.example.teethree.data

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class OfflineCacheTest {
    @Test
    fun `worktree recovery reuses the server owned original prompt context and attachments`() {
        val projection =
            Json.parseToJsonElement(
                    """{"messages":[{"role":"assistant","text":"ignore"},{"role":"user","text":"original","context":{"parts":[{"id":"context"}]},"attachments":[{"id":"uploaded"}]},{"role":"user","text":"follow up"}]}"""
                )
                .let { it as kotlinx.serialization.json.JsonObject }
        val original = setupRecoveryMessage(projection)
        assertEquals("original", original.string("text"))
        assertEquals(projection.array("messages")[1], original)
        assertEquals(
            "uploaded",
            original
                .array("attachments")
                .single()
                .let { it as kotlinx.serialization.json.JsonObject }
                .string("id"),
        )
        assertFailsWith<IllegalStateException> {
            setupRecoveryMessage(kotlinx.serialization.json.JsonObject(emptyMap()))
        }
    }

    @Test
    fun `repository grouping keeps monorepo paths distinct and unresolved identities separate`() {
        fun project(id: String, path: String, key: String? = "origin") =
            T3Project(
                id,
                id,
                path,
                repositoryKey = key,
                repositoryRoot = "/repo",
                repositoryLabel = "owner/repo",
            )
        val projects =
            listOf(
                project("first", "/repo/apps/web"),
                project("second", "/repo/apps/mobile"),
                project("third", "/repo/apps/web"),
                project("unknown", "/repo/apps/web", null),
            )
        val repository = groupProjects(projects, "repository")
        assertEquals(listOf(3, 1), repository.map { it.projects.size })
        assertEquals("owner/repo", repository.first().title)
        assertEquals(
            listOf(2, 1, 1),
            groupProjects(projects, "repository_path").map { it.projects.size },
        )
        assertEquals(4, groupProjects(projects, "separate").size)
        val outside = project("outside", "/repo-copy/apps/web")
        assertEquals(2, groupProjects(listOf(projects.first(), outside), "repository_path").size)
    }

    @Test
    fun `offline copies retain bounded history and never replay pending approvals or streaming state`() {
        val thread =
            T3Thread(
                "t",
                "p",
                "Task",
                "running",
                ModelSelection("replay", "showcase"),
                "r",
                false,
                "text",
                "2026-11-05",
            )
        val prior =
            CachedEnvironment(conversations = listOf(CachedConversation("removed", emptyList())))
        val timeline =
            (1..230).map {
                TimelineItem(
                    "$it",
                    "message",
                    "text $it",
                    null,
                    "running",
                    it.toLong(),
                    true,
                    "approval",
                    listOf(ApprovalOption("accept", "Allow")),
                )
            }
        val cached =
            updateOfflineCache(
                prior,
                RepositoryState(
                    threads = listOf(thread),
                    selectedThreadId = "t",
                    timeline = timeline,
                ),
            )
        assertEquals("t", cached.selectedThreadId)
        assertEquals(200, cached.conversations.single().timeline.size)
        assertEquals("31", cached.conversations.single().timeline.first().id)
        assertTrue(
            cached.conversations.single().timeline.all {
                !it.streaming && it.requestId == null && it.options.isEmpty()
            }
        )
        assertEquals(cached, Json.decodeFromString<CachedEnvironment>(Json.encodeToString(cached)))
        val archived = cached.copy(threads = listOf(thread.copy(archived = true)))
        val shellOnly = RepositoryState(threads = listOf(thread.copy(id = "other")))
        val background = updateBackgroundOfflineCache(archived, shellOnly, includeArchived = false)
        assertEquals(listOf("other", "t"), background.threads.map { it.id })
        assertEquals(cached.conversations, background.conversations)
        assertEquals("t", background.selectedThreadId)
        val completeArchive =
            updateBackgroundOfflineCache(background, shellOnly, includeArchived = true)
        assertEquals(listOf("other"), completeArchive.threads.map { it.id })
        assertTrue(completeArchive.conversations.isEmpty())
        assertNull(completeArchive.selectedThreadId)
        val switching =
            updateOfflineCache(
                cached,
                RepositoryState(
                    threads = listOf(thread),
                    selectedThreadId = "t",
                    threadLoading = true,
                ),
            )
        assertEquals(cached.conversations, switching.conversations)
        val removed = updateOfflineCache(cached, RepositoryState())
        assertTrue(removed.conversations.isEmpty())
    }
}
