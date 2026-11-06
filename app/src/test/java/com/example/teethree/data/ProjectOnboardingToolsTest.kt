package com.example.teethree.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectOnboardingToolsTest {
    @Test
    fun cloneInputNormalizesShorthandAndRejectsExecutableHelpersOrControlCharacters() {
        assertEquals("https://github.com/owner/repo.git", normalizeProjectCloneUrl(" owner/repo "))
        assertEquals(
            "git@example.com:team/repo.git",
            normalizeProjectCloneUrl("git@example.com:team/repo.git"),
        )
        assertEquals(
            "ssh://git@example.com/team/repo",
            normalizeProjectCloneUrl("ssh://git@example.com/team/repo"),
        )
        for (input in
            listOf(
                "--upload-pack=sh",
                "ext::sh command",
                "https:repository",
                "https://example.com/repo\ncommand",
                "javascript:alert(1)",
            )) assertTrue(input, runCatching { normalizeProjectCloneUrl(input) }.isFailure)
        assertEquals(
            "repo",
            projectCloneDirectoryName("https://github.com/owner/repo.git?ref=main"),
        )
        assertEquals("repo", projectCloneDirectoryName("git@example.com:owner/repo.git"))
        assertEquals("", projectCloneDirectoryName("https://example.com:443"))
        assertEquals("~/repo", cloneDestination("~/", "repo"))
        assertEquals("C:\\Projects\\repo", cloneDestination("C:\\Projects\\", "repo"))
        assertEquals("/Projects/repo/", projectBrowseDirectory("/Projects/repo"))
        assertEquals("C:\\Projects\\", projectBrowseDirectory("C:\\Projects"))
        assertEquals("~/", projectBrowseDirectory("~/"))
    }

    @Test
    fun importPathsRespectHostPathConventionsAndAvoidDuplicateWindowsProjects() {
        for (path in
            listOf(
                "/",
                "/home/user/project",
                "~/project",
                "C:\\Projects\\Repo",
                "\\\\server\\share\\Repo",
            )) assertTrue(path, validProjectPath(path))
        for (path in listOf("relative/path", "C:relative", "", "/folder\u0000name")) assertFalse(
            path,
            validProjectPath(path),
        )
        val projects =
            listOf(
                T3Project("windows", "Windows", "C:\\Projects\\Repo"),
                T3Project("unix", "Unix", "/Projects/Repo"),
                T3Project("slash", "Backslash", "/Projects/a\\b"),
            )
        assertEquals("windows", findProjectAtPath(projects, "c:/projects/repo/")?.id)
        assertEquals("unix", findProjectAtPath(projects, "/Projects/Repo/")?.id)
        assertNull(findProjectAtPath(projects, "/projects/repo"))
        assertNull(findProjectAtPath(projects, "/Projects/a/b"))
    }

    @Test
    fun sourceControlDiscoveryDecodesEffectOptionsAndExposesOnlyOfferedAppSignIn() {
        val host =
            parseSourceControlHost(
                Json.parseToJsonElement(
                        """{"kind":"github","label":"GitHub","status":"available","appSignIn":true,"auth":{"status":"authenticated","account":{"_tag":"Some","value":"person"},"host":{"_tag":"Some","value":"github.com"},"detail":{"_tag":"None"}}}"""
                    )
                    .jsonObject
            )
        assertTrue(host.available)
        assertTrue(host.authenticated)
        assertTrue(host.appSignIn)
        assertEquals("person", host.account)
        assertEquals("github.com", host.host)
        assertNull(host.detail)
        assertNull(sourceControlOption(JsonNull))
        assertEquals("plain", sourceControlOption(JsonPrimitive("plain")))
        assertFalse(
            parseSourceControlHost(
                    Json.parseToJsonElement(
                            """{"kind":"future","status":"missing","auth":{"status":"unknown"}}"""
                        )
                        .jsonObject
                )
                .appSignIn
        )
    }

    @Test
    fun cloneSnapshotsDoNotRegressOnReplayOrShowStaleTransferSpeeds() {
        fun clone(sequence: Int, phase: String, stalled: Boolean) =
            parseProjectClone(
                Json.parseToJsonElement(
                        """{"projectId":"project","remoteUrl":"https://example.com/repo.git","destinationPath":"/Projects/repo","phase":"$phase","stage":"receiving","percent":42,"detail":"8 MiB/s","stalled":$stalled,"sequence":$sequence}"""
                    )
                    .jsonObject
            )
        val active = clone(1, "running", false)
        val stalled = clone(2, "running", true)
        val done = clone(3, "done", false)
        assertTrue(projectCloneSummary(active).contains("8 MiB/s"))
        assertFalse(projectCloneSummary(stalled).contains("8 MiB/s"))
        assertTrue(projectCloneSummary(stalled).contains("Stalled"))
        assertEquals(listOf(done), mergeProjectClones(listOf(done), listOf(active)))
        assertEquals(listOf(done), mergeProjectClones(listOf(active), listOf(done)))
        assertTrue(mergeProjectClones(listOf(done), emptyList()).isEmpty())
    }
}
