package com.example.teethree.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class EnvironmentToolsTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test
    fun `only hosted web URLs can open provider authentication`() {
        listOf(
                "https://accounts.example.com/authorize?state=secret",
                "http://localhost:1234/login",
                "HTTPS://example.com",
            )
            .forEach {
                assertTrue(providerBrowserUrlAllowed(it), it)
            }
        listOf(
                "javascript:alert(1)",
                "intent://auth",
                "file:///private/file",
                "mailto:auth@example.com",
                "https:login",
                "https://user:pass@example.com",
                "//example.com",
            )
            .forEach {
                assertFalse(providerBrowserUrlAllowed(it), it)
            }
    }

    @Test
    fun `provider auth interactions preserve secret field metadata and ignore unknown interaction types`() {
        val state =
            parseEnvironmentAuthState(
                json(
                    """{
          "phase":"waiting","flowId":"flow","authorizationUrl":null,"message":"Enter credentials",
          "methods":[{"id":"key","name":"API key","description":null,"type":"credentials","accountEmail":"account@example.com"},{"id":"future","name":"Future","type":"unknown"}],
          "interaction":{"type":"credentials","id":"input","fields":[{"name":"api_key","label":"API key","secret":true}]}
        }"""
                )
            )
        assertEquals("flow", state.flowId)
        assertEquals(listOf("key"), state.methods.map { it.id })
        assertEquals("account@example.com", state.methods.single().accountEmail)
        assertTrue(state.interaction!!.fields.single().secret)
        assertEquals(
            null,
            parseEnvironmentAuthState(
                    json("""{"phase":"waiting","interaction":{"type":"future","id":"future"}}""")
                )
                .interaction,
        )
    }

    @Test
    fun `task parsing retains provider options and workspace configuration for edits`() {
        val task =
            parseEnvironmentTask(
                json(
                    """{
          "id":"task","title":"Review","prompt":"Review the work","enabled":true,"projectId":"project","threadId":"thread",
          "schedule":{"type":"once","runAt":"2026-11-05T08:30:45Z"},
          "modelSelection":{"instanceId":"codex","model":"model","options":{"reasoningEffort":"high"}},
          "workspaceStrategy":{"type":"worktree","baseRef":"release","branch":"review","startFromOrigin":true},
          "runtimeMode":"approval-required","interactionMode":"default","creationSource":"web","lastRunStatus":"never"
        }"""
                )
            )
        assertEquals("thread", task.threadId)
        assertEquals("review", task.workspace.string("branch"))
        assertEquals("high", task.modelData.obj("options").string("reasoningEffort"))
        assertEquals("web", task.creationSource)
    }

    @Test
    fun `interval validation preserves fractional minutes and rejects subminute schedules`() {
        assertEquals(
            90_000L,
            buildEnvironmentSchedule("interval", "1,5", "", emptySet(), "", "").long("everyMs"),
        )
        assertFailsWith<IllegalArgumentException> {
            buildEnvironmentSchedule("interval", "0.5", "", emptySet(), "", "")
        }
        assertFailsWith<IllegalArgumentException> {
            buildEnvironmentSchedule("interval", "NaN", "", emptySet(), "", "")
        }
    }

    @Test
    fun `fixed schedules use environment wall time and validated weekdays without adding a timezone field`() {
        val schedule =
            buildEnvironmentSchedule("fixed_time", "", "9:05", setOf(5, 1, 1), "", "Europe/Prague")
        assertEquals("09:05", schedule.string("timeOfDay"))
        assertEquals("[1,5]", schedule.array("weekdays").toString())
        assertFalse(schedule.containsKey("timeZone"))
        assertFailsWith<IllegalArgumentException> {
            buildEnvironmentSchedule("fixed_time", "", "09:00", emptySet(), "", "")
        }
    }

    @Test
    fun `one time schedules resolve explicit device zone and reject nonexistent daylight saving times`() {
        val schedule =
            buildEnvironmentSchedule(
                "once",
                "",
                "",
                emptySet(),
                "2026-11-05 09:30",
                "Europe/Prague",
            )
        assertEquals("2026-11-05T08:30:00Z", schedule.string("runAt"))
        assertFailsWith<IllegalArgumentException> {
            buildEnvironmentSchedule(
                "once",
                "",
                "",
                emptySet(),
                "2026-03-29 02:30",
                "Europe/Prague",
            )
        }
        assertFailsWith<java.time.format.DateTimeParseException> {
            buildEnvironmentSchedule(
                "once",
                "",
                "",
                emptySet(),
                "2026-02-30 09:30",
                "Europe/Prague",
            )
        }
    }
}
