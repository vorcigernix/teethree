package com.example.teethree.data

import kotlin.test.*
import kotlinx.serialization.json.*

class WorkspaceProjectGroupingTest {
    private fun environment(id: String, environment: String? = id, vararg projects: T3Project) =
        WorkspaceEnvironmentShell(
            id,
            environment,
            id,
            WorkspaceEnvironmentPhase.Cached,
            projects.toList(),
        )

    private fun project(
        id: String,
        root: String,
        repository: String? = null,
        repositoryRoot: String? = null,
        title: String = "Custom title",
        pinned: Boolean = false,
        archived: Boolean = false,
        scratch: Boolean = false,
        updated: String = "2026-11-01T00:00:00Z",
    ) =
        T3Project(
            id,
            title,
            root,
            pinned,
            archived,
            repository,
            repositoryRoot,
            repository?.substringAfterLast('/'),
            isScratch = scratch,
            updatedAt = updated,
        )

    @Test
    fun physicalIdentityStaysEnvironmentLocal() {
        val sources =
            listOf(
                environment("first", "env-a", project("same-id", "/workspace")),
                environment("second", "env-b", project("same-id", "/workspace")),
                environment("legacy-a", null, project("same-id", "/workspace")),
                environment("legacy-b", null, project("same-id", "/workspace")),
            )
        val groups = groupWorkspaceProjects(sources, "separate")
        assertEquals(4, groups.size)
        assertEquals(4, groups.map { it.key }.distinct().size)
        assertEquals(
            sources.map { it.connectionId }.toSet(),
            groups.map { it.representative.connectionId }.toSet(),
        )
    }

    @Test
    fun repositoryGroupingPreservesPathsLabelsAndEnvironmentRefs() {
        val sources =
            listOf(
                environment(
                    "first",
                    "env-a",
                    project("api", "/repo/api", "origin/repo", "/repo", archived = true),
                ),
                environment(
                    "second",
                    "env-b",
                    project("api", "C:/REPO/api", "origin/repo", "C:/repo", pinned = true),
                ),
                environment("third", "env-c", project("web", "/repo/web", "origin/repo", "/repo")),
            )
        val repository = groupWorkspaceProjects(sources, "repository").single()
        assertEquals("Custom title", repository.label)
        assertTrue(repository.pinned)
        assertFalse(repository.archived)
        assertEquals(3, repository.members.size)
        val paths = groupWorkspaceProjects(sources, "repository_path")
        assertEquals(setOf("origin/repo::api", "origin/repo::web"), paths.map { it.key }.toSet())
        assertTrue(paths.first().pinned)
        assertEquals(2, paths.first().members.size)
        assertEquals(3, groupWorkspaceProjects(sources, "separate").size)
    }

    @Test
    fun newestPhysicalVersionOwnsFlagsAndPreservesPriorIdentity() {
        val sources =
            listOf(
                environment(
                    "first",
                    "env-a",
                    project("old", "/repo", "origin/repo", "/repo", archived = true),
                    project("new-a", "/repo", updated = "2026-11-02T00:00:00Z"),
                    project("new-z", "/repo", pinned = true, updated = "2026-11-02T00:00:00Z"),
                )
            )
        val group = groupWorkspaceProjects(sources, "repository").single()
        assertEquals("origin/repo", group.key)
        assertEquals("new-z", group.representative.projectId)
        assertEquals(setOf("old", "new-a", "new-z"), group.members.map { it.projectId }.toSet())
        assertTrue(group.pinned)
        assertFalse(group.archived)
    }

    @Test
    fun scratchRemainsOneReservedGroupInEveryMode() {
        val sources =
            listOf(
                environment(
                    "first",
                    "env-a",
                    project("scratch-a", "/scratch", scratch = true, archived = true),
                ),
                environment(
                    "second",
                    "env-b",
                    project("scratch-b", "C:/scratch", scratch = true, pinned = true),
                ),
            )
        for (mode in listOf("repository", "repository_path", "separate")) {
            val group = groupWorkspaceProjects(sources, mode).single()
            assertEquals("\u0000no-project", group.key)
            assertEquals("No project", group.label)
            assertEquals(2, group.members.size)
            assertTrue(group.pinned)
            assertFalse(group.archived)
        }
    }

    @Test
    fun pathComparisonsPreservePosixFilenames() {
        assertEquals("c:\\", normalizeWorkspacePath("C:"))
        assertEquals("c:\\workspace", normalizeWorkspacePath("C:/Workspace/"))
        assertEquals("/workspace\\", normalizeWorkspacePath("/workspace\\"))
        assertEquals("//server/path", normalizeWorkspacePath("//server/path/"))
        val source =
            environment(
                "first",
                "env-a",
                project("slash", "/workspace"),
                project("backslash", "/workspace\\"),
            )
        assertEquals(2, groupWorkspaceProjects(listOf(source), "separate").size)
    }

    @Test
    fun projectParserUsesOriginIdentityLabelAndPreservesFreshness() {
        val parsed =
            parseProject(
                buildJsonObject {
                    put("id", "p")
                    put("title", "Project")
                    put("workspaceRoot", "/repo")
                    put("createdAt", "2026-10-31T00:00:00Z")
                    put("updatedAt", "2026-11-01T00:00:00Z")
                    putJsonObject("repositoryIdentity") {
                        put("canonicalKey", "fork/repo")
                        put("displayName", "Fork")
                        put("name", "repo")
                        put("rootPath", "/repo")
                        putJsonObject("origin") { put("canonicalKey", "upstream/repo") }
                    }
                }
            )
        assertEquals("upstream/repo", parsed.repositoryKey)
        assertEquals("upstream/repo", parsed.repositoryLabel)
        assertEquals("repo", parsed.repositoryName)
        assertEquals("2026-11-01T00:00:00Z", parsed.updatedAt)
        assertEquals("2026-10-31T00:00:00Z", parsed.createdAt)
        assertFalse(parsed.isScratch)
    }
}
