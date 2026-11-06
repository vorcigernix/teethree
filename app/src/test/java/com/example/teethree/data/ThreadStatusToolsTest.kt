package com.example.teethree.data

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ThreadStatusToolsTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    private val base =
        T3Thread(
            "root",
            "project",
            "Root",
            "idle",
            ModelSelection("provider", "model"),
            null,
            false,
            "",
            "2026-11-05T08:00:00Z",
        )

    private fun child(id: String, parent: String, relationship: String = "subagent") =
        base.copy(
            id = id,
            lineage =
                json(
                    """{"parentThreadId":"$parent","relationshipToParent":"$relationship","rootThreadId":"root"}"""
                ),
        )

    @Test
    fun providerChildrenAreReadOnlyAndSideChatsRemainOutsidePrimaryLists() {
        val providerChild =
            parseThread(
                json(
                    """{"id":"child","projectId":"project","creationSource":"provider","lineage":{"parentThreadId":"root","relationshipToParent":"subagent","rootThreadId":"root"},"modelSelection":{"instanceId":"provider","model":"model"},"tags":["private"],"goal":{"objective":"Ship","status":"active"},"pendingBackgroundTasks":[{"taskId":"cmd","kind":"command"}],"lastVisitedAt":"2026-11-05T08:00:00Z","latestRunId":"run","latestRunStopRequested":true}"""
                ),
                false,
            )
        assertTrue(providerManagedChild(providerChild))
        assertFalse(threadIsListed(providerChild))
        assertEquals(listOf("private"), providerChild.tags)
        assertEquals("Ship", providerChild.goal!!.string("objective"))
        assertEquals(1, providerChild.pendingBackgroundTasks.size)
        assertEquals("run", providerChild.latestRunId)
        assertTrue(providerChild.latestRunStopRequested)
        assertFalse(providerManagedChild(providerChild.copy(creationSource = "user")))
        assertTrue(threadIsListed(child("fork", "root", "fork")))
        assertFalse(threadIsListed(base.copy(lineage = json("""{"sideChat":true}"""))))
    }

    @Test
    fun hiddenTagsFollowOnlyVisibilityParentsAndTerminateCycles() {
        val config =
            json(
                """{"settings":{"hideTaggedThreads":true,"hiddenThreadTags":[" private ","",17]}}"""
            )
        val hiddenRoot = base.copy(tags = listOf("private"))
        val hiddenChild = child("child", "root")
        val fork = child("fork", "root", "fork")
        val missing = child("missing", "unloaded")
        val visibleRoot = base.copy(id = "visible")
        val childOwnTag = child("own", "visible").copy(tags = listOf("private"))
        val cycleA = child("a", "b")
        val cycleB = child("b", "a")
        val hidden =
            hiddenThreadIds(
                listOf(
                    hiddenRoot,
                    hiddenChild,
                    fork,
                    missing,
                    visibleRoot,
                    childOwnTag,
                    cycleA,
                    cycleB,
                ),
                config,
            )
        assertEquals(setOf("root", "child", "missing"), hidden)
        assertTrue(
            hiddenThreadIds(
                    listOf(hiddenRoot, missing),
                    json(
                        """{"settings":{"hideTaggedThreads":false,"hiddenThreadTags":["private"]}}"""
                    ),
                )
                .isEmpty()
        )
        assertTrue(
            hiddenThreadIds(
                    listOf(hiddenRoot, missing),
                    json("""{"settings":{"hideTaggedThreads":true,"hiddenThreadTags":[]}}"""),
                )
                .isEmpty()
        )
        val delegated = base.copy(id = "delegated", delegatedFrom = json("""{"threadId":"root"}"""))
        assertTrue("delegated" in hiddenThreadIds(listOf(hiddenRoot, delegated), config))
    }

    @Test
    fun snoozeExpiresAndFreshRequestsWakeWithoutResurfacingDismissedActivity() {
        val now = Instant.parse("2026-11-05T09:00:00Z")
        val snoozed =
            base.copy(snoozedAt = "2026-11-05T08:00:00Z", snoozedUntil = "2026-11-05T10:00:00Z")
        assertTrue(threadSnoozed(snoozed, now))
        assertFalse(threadWakeUnseen(snoozed, now))
        val after = Instant.parse("2026-11-05T10:01:00Z")
        assertFalse(threadSnoozed(snoozed, after))
        assertTrue(threadWakeUnseen(snoozed, after))
        assertFalse(threadWakeUnseen(snoozed.copy(lastVisitedAt = "2026-11-05T10:00:00Z"), after))
        assertFalse(
            threadWakeUnseen(snoozed.copy(latestRunCompletedAt = "2026-11-05T10:01:00Z"), after)
        )
        assertFalse(threadWakeUnseen(snoozed.copy(settled = true), after))
        val request =
            snoozed.copy(hasPendingRuntimeRequest = true, updatedAt = "2026-11-05T08:30:00Z")
        assertFalse(threadSnoozed(request, now))
        assertEquals(request.updatedAt, threadWakeTime(request, after))
        assertFalse(threadWakeUnseen(request.copy(lastVisitedAt = "2026-11-05T08:31:00Z"), after))
        assertTrue(
            threadSnoozed(
                snoozed.copy(status = "failed", latestRunCompletedAt = "2026-11-05T07:00:00Z"),
                now,
            )
        )
        assertFalse(
            threadSnoozed(
                snoozed.copy(status = "failed", latestRunCompletedAt = "2026-11-05T08:15:00Z"),
                now,
            )
        )
    }

    @Test
    fun selectedTurnAgentsRespectRunOrdinalsRollbackAndPersistentWorkflows() {
        val runs =
            Json.parseToJsonElement(
                    """[{"id":"new","ordinal":5,"status":"completed","startedAt":"2026-11-05T09:00:00Z"},{"id":"old","ordinal":1,"status":"completed","startedAt":"2026-11-05T08:00:00Z"},{"id":"rolled","ordinal":6,"status":"rolled_back","startedAt":"2026-11-05T10:00:00Z"}]"""
                )
                .jsonArray
        val agents =
            Json.parseToJsonElement(
                    """[{"id":"old","runId":"old","status":"completed","updatedAt":"2026-11-05T11:00:00Z"},{"id":"new","runId":"new","status":"completed","updatedAt":"2026-11-05T09:00:00Z"},{"id":"workflow","runId":"old","status":"completed","updatedAt":"2026-11-05T08:00:00Z","workflow":{"agents":[],"phases":[]}},{"id":"live","runId":"old","status":"running","updatedAt":"2026-11-05T08:00:00Z"},{"id":"rollback","runId":"rolled","status":"running","updatedAt":"2026-11-05T10:00:00Z"}]"""
                )
                .jsonArray
        assertEquals(
            setOf("new", "workflow", "live"),
            currentTurnSubagents(runs, agents)
                .filterIsInstance<JsonObject>()
                .map { it.string("id") }
                .toSet(),
        )
        val updated =
            restoreSubagentBodies(
                json("""{"prompt":"Keep prompt","result":"Keep result"}"""),
                json(
                    """{"id":"agent","prompt":"","result":null,"promptOmitted":true,"resultOmitted":true}"""
                ),
            )
        assertEquals("Keep prompt", updated.string("prompt"))
        assertEquals("Keep result", updated.string("result"))
        assertFalse(updated.containsKey("promptOmitted"))
        assertEquals("", restoreSubagentBodies(updated, json("""{"prompt":""}""")).string("prompt"))
    }

    @Test
    fun subagentStopTargetsRequireSupportedNativeIdentityOrAnOwnedChild() {
        val native =
            json(
                """{"id":"agent","threadId":"root","runId":"run","origin":"provider_native","driver":"claudeAgent","status":"running","nativeTaskRef":{"nativeId":"task","strength":"strong"}}"""
            )
        assertFalse(subagentStopTarget(native)!!.workflow)
        assertNull(
            subagentStopTarget(
                JsonObject(
                    native + ("nativeTaskRef" to json("""{"nativeId":"task","strength":"weak"}"""))
                )
            )
        )
        assertNull(
            subagentStopTarget(JsonObject(native + ("status" to JsonPrimitive("completed"))))
        )
        val workflow = JsonObject(native + ("workflow" to json("""{"phases":[],"agents":[]}""")))
        assertTrue(subagentStopTarget(workflow)!!.workflow)
        assertNull(
            subagentStopTarget(JsonObject(workflow + ("status" to JsonPrimitive("waiting"))))
        )
        assertNull(subagentStopTarget(JsonObject(workflow + ("driver" to JsonPrimitive("codex")))))
        val owned =
            json(
                """{"id":"owned","threadId":"root","runId":"run","origin":"app_owned","driver":"codex","status":"pending","childThreadId":"child"}"""
            )
        assertEquals("child", subagentStopTarget(owned)!!.childThreadId)
        assertNull(subagentStopTarget(JsonObject(owned - "childThreadId")))
    }

    @Test
    fun snoozeCalendarPresetsRespectDaylightSavingsAndDeduplicateWakeMinutes() {
        val config =
            json(
                """{"settings":{"snoozePresets":[{"kind":"at","day":"tomorrow","time":"09:00"},{"kind":"after","minutes":1440},{"kind":"after","minutes":1440},{"kind":"at","day":"today","time":"09:00"}]}}"""
            )
        val now = Instant.parse("2026-03-28T12:00:00Z")
        val choices = threadSnoozeChoices(config, now, ZoneId.of("Europe/Prague"))
        assertEquals(
            listOf(Instant.parse("2026-03-29T07:00:00Z"), now.plusSeconds(86400)),
            choices.map { it.until },
        )
        val monday =
            json("""{"settings":{"snoozePresets":[{"kind":"at","day":"monday","time":"09:00"}]}}""")
        assertEquals(
            Instant.parse("2026-11-09T08:00:00Z"),
            threadSnoozeChoices(
                    monday,
                    Instant.parse("2026-11-02T07:00:00Z"),
                    ZoneId.of("Europe/Prague"),
                )
                .single()
                .until,
        )
    }
}
