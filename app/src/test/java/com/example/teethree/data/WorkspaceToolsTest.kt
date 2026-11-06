package com.example.teethree.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class WorkspaceToolsTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test
    fun `directory keeps remote paths and groups folders before files`() {
        val result =
            parseWorkspaceDirectory(
                json(
                    """{
          "entries":[
            {"path":"src/z.kt","kind":"file"},
            {"path":"src/build","kind":"directory","ignored":true},
            {"path":"src/A.kt","kind":"file"}
          ],"truncated":true
        }"""
                )
            )
        assertEquals(listOf("src/build", "src/A.kt", "src/z.kt"), result.entries.map { it.path })
        assertTrue(result.entries.first().directory)
        assertTrue(result.entries.first().ignored)
        assertTrue(result.truncated)
    }

    @Test
    fun `Git file paths stay relative to server repository root rather than project subfolder`() {
        val result =
            parseWorkspaceGitStatus(
                json(
                    """{
          "isRepo":true,"refName":null,"hasPrimaryRemote":true,"aheadCount":2,"behindCount":1,
          "workingTree":{"rootPath":"/remote/repository","files":[{"path":"apps/server/index.ts","insertions":4,"deletions":2}]}
        }"""
                )
            )
        assertEquals("/remote/repository", result.rootPath)
        assertEquals("apps/server/index.ts", result.files.single().path)
        assertEquals(null, result.branch)
        assertEquals(2, result.ahead)
        assertEquals(1, result.behind)
    }

    @Test
    fun `pull request actions require both host capability and viewer permission`() {
        val request =
            parseWorkspacePullRequest(
                json(
                    """{
          "projectId":"project","repository":"owner/repo","host":"github.example","number":7,
          "title":"Change","state":"open","author":null,"headBranch":"feature","url":"https://github.example/owner/repo/pull/7"
        }"""
                )
            )
        val result =
            parseWorkspacePullRequestDetail(
                request,
                json(
                    """{
          "title":"Updated title","state":"open","body":"Details","baseBranch":"main",
          "capabilities":{"comment":true,"diff":true,"review":{"verdicts":["approve","request-changes"]}},
          "viewerPermissions":{"comment":false,"verdicts":["comment","approve"]}
        }"""
                ),
                JsonObject(emptyMap()),
            )
        assertEquals("", request.author)
        assertEquals("github.example", result.request.host)
        assertEquals("Updated title", result.request.title)
        assertFalse(result.canComment)
        assertEquals(setOf("approve"), result.verdicts)
        assertTrue(result.canReadDiff)
    }
}
