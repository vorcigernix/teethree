package com.example.teethree.data

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun providerManagedChild(thread: T3Thread): Boolean =
    thread.lineage.string("relationshipToParent") == "subagent" &&
        thread.creationSource == "provider"

fun threadIsListed(thread: T3Thread): Boolean =
    thread.lineage.string("relationshipToParent") != "subagent" && !thread.lineage.bool("sideChat")

internal fun activeSubagent(status: String) = status in setOf("pending", "running", "waiting")

internal fun parsedInstant(value: String?): Instant? = value?.let {
    runCatching { Instant.parse(it) }.getOrNull()
}

internal fun hiddenThreadIds(threads: List<T3Thread>, config: JsonObject?): Set<String> {
    val settings = config?.obj("settings") ?: return emptySet()
    if (!settings.bool("hideTaggedThreads")) return emptySet()
    val hiddenTags =
        settings
            .array("hiddenThreadTags")
            .mapNotNull {
                (it as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.content
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            }
            .toSet()
    if (hiddenTags.isEmpty()) return emptySet()
    val byId = threads.associateBy(T3Thread::id)
    val hidden = mutableMapOf<String, Boolean>()
    fun isHidden(id: String): Boolean {
        hidden[id]?.let {
            return it
        }
        val thread = byId[id] ?: return false
        val delegated = thread.delegatedFrom
        if (
            delegated != null &&
                delegated.nullableString("environmentId") != null &&
                delegated.nullableString("environmentId") !=
                    config.obj("environment").nullableString("environmentId")
        ) {
            hidden[id] = true
            return true
        }
        val parent =
            if (!threadIsListed(thread)) thread.lineage.nullableString("parentThreadId")
            else thread.delegatedFrom?.nullableString("threadId")
        if (parent != null) {
            if (parent !in byId) {
                hidden[id] = true
                return true
            }
            hidden[id] = false
            return isHidden(parent).also { hidden[id] = it }
        }
        return thread.tags.any { it in hiddenTags }.also { hidden[id] = it }
    }
    return threads.filter { isHidden(it.id) }.mapTo(hashSetOf(), T3Thread::id)
}

internal fun threadRaisedHand(thread: T3Thread): Boolean =
    thread.hasPendingRuntimeRequest ||
        thread.pendingGuestMessageCount > 0 ||
        (thread.status == "failed" &&
            (thread.snoozedAt == null ||
                (parsedInstant(thread.latestRunCompletedAt)
                    ?.isAfter(parsedInstant(thread.snoozedAt) ?: Instant.MIN) == true)))

internal fun threadSnoozed(thread: T3Thread, now: Instant): Boolean =
    parsedInstant(thread.snoozedUntil)?.isAfter(now) == true && !threadRaisedHand(thread)

internal fun threadWakeTime(thread: T3Thread, now: Instant): String? {
    val wake = parsedInstant(thread.snoozedUntil) ?: return null
    if (threadRaisedHand(thread))
        return if (
            thread.status == "failed" &&
                parsedInstant(thread.latestRunCompletedAt)
                    ?.isAfter(parsedInstant(thread.snoozedAt) ?: Instant.MIN) == true
        )
            thread.latestRunCompletedAt
        else thread.updatedAt
    return thread.snoozedUntil.takeIf { !wake.isAfter(now) }
}

internal fun threadWakeUnseen(thread: T3Thread, now: Instant): Boolean {
    if (thread.settled) return false
    val wake = parsedInstant(threadWakeTime(thread, now)) ?: return false
    return listOfNotNull(
            parsedInstant(thread.lastVisitedAt),
            parsedInstant(thread.latestRunCompletedAt),
        )
        .all { it.isBefore(wake) }
}

internal fun restoreSubagentBodies(previous: JsonObject?, next: JsonObject): JsonObject {
    val fields = next.toMutableMap()
    for (key in listOf("prompt", "result")) if (next.bool("${key}Omitted")) {
        previous?.get(key)?.let { fields[key] = it }
        fields.remove("${key}Omitted")
    }
    return JsonObject(fields)
}

internal fun currentTurnSubagents(runs: JsonArray, agents: JsonArray): JsonArray {
    val runRecords = runs.filterIsInstance<JsonObject>()
    val rolledBack =
        runRecords
            .filter { it.string("status") == "rolled_back" }
            .mapTo(hashSetOf()) { it.string("id") }
    val eligible =
        agents.filterIsInstance<JsonObject>().filter { it.nullableString("runId") !in rolledBack }
    if (eligible.isEmpty()) return JsonArray(emptyList())
    val active = runRecords.lastOrNull {
        it.string("status") in setOf("preparing", "starting", "running", "waiting")
    }
    val latestStarted =
        runRecords
            .filter { it.nullableString("startedAt") != null && it.string("id") !in rolledBack }
            .maxByOrNull { it.long("ordinal") }
    val latestUpdated = eligible.maxByOrNull { it.string("updatedAt") }
    val runId =
        active?.string("id")
            ?: latestStarted
                ?.takeIf { run -> eligible.any { it.nullableString("runId") == run.string("id") } }
                ?.string("id")
            ?: latestUpdated?.nullableString("runId")
    return JsonArray(
        eligible
            .filter {
                it.nullableString("runId") == runId ||
                    activeSubagent(it.string("status")) ||
                    it["workflow"] is JsonObject
            }
            .sortedWith(
                compareBy<JsonObject> { it.nullableString("startedAt") ?: it.string("updatedAt") }
                    .thenBy { it.string("id") }
            )
    )
}

data class SubagentStopTarget(
    val workflow: Boolean,
    val threadId: String,
    val subagentId: String,
    val runId: String?,
    val childThreadId: String?,
)

internal fun subagentStopTarget(agent: JsonObject): SubagentStopTarget? {
    if (!activeSubagent(agent.string("status"))) return null
    val native = agent.obj("nativeTaskRef")
    val workflow = agent["workflow"] is JsonObject
    val allowed =
        if (workflow)
            agent.string("status") == "running" &&
                agent.string("origin") == "provider_native" &&
                agent.string("driver") == "claudeAgent" &&
                native.nullableString("nativeId") != null
        else
            (agent.string("origin") == "app_owned" &&
                agent.nullableString("childThreadId") != null) ||
                (agent.string("origin") == "provider_native" &&
                    agent.string("driver") == "claudeAgent" &&
                    agent.nullableString("runId") != null &&
                    native.string("strength") == "strong" &&
                    native.nullableString("nativeId") != null)
    return if (allowed)
        SubagentStopTarget(
            workflow,
            agent.string("threadId"),
            agent.string("id"),
            agent.nullableString("runId"),
            if (agent.string("origin") == "app_owned") agent.nullableString("childThreadId")
            else null,
        )
    else null
}

data class ThreadSnoozeChoice(val label: String, val until: Instant)

internal fun threadSnoozeChoices(
    config: JsonObject?,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ThreadSnoozeChoice> {
    val settings = config?.obj("settings")
    val presets =
        settings?.get("snoozePresets") as? JsonArray
            ?: JsonArray(
                listOf(
                    buildJsonObject {
                        put("kind", "after")
                        put("minutes", 60)
                    },
                    buildJsonObject {
                        put("kind", "after")
                        put("minutes", 180)
                    },
                    buildJsonObject {
                        put("kind", "at")
                        put("day", "today")
                        put("time", "18:00")
                    },
                    buildJsonObject {
                        put("kind", "at")
                        put("day", "tomorrow")
                        put("time", "09:00")
                    },
                    buildJsonObject {
                        put("kind", "at")
                        put("day", "monday")
                        put("time", "09:00")
                    },
                )
            )
    val local = now.atZone(zone)
    return presets
        .filterIsInstance<JsonObject>()
        .mapNotNull { value ->
            if (value.string("kind") == "after") {
                val minutes = value.long("minutes")
                if (minutes !in 1..43200) null
                else
                    ThreadSnoozeChoice(
                        if (minutes % 60 == 0L)
                            "In ${minutes / 60} ${if (minutes == 60L) "hour" else "hours"}"
                        else "In $minutes minutes",
                        now.plusSeconds(minutes * 60),
                    )
            } else if (value.string("kind") == "at") {
                val time =
                    runCatching { LocalTime.parse(value.string("time")) }.getOrNull()
                        ?: return@mapNotNull null
                val day = value.string("day")
                val daysAhead =
                    when (day) {
                        "today" -> 0L
                        "tomorrow" -> 1L
                        else -> {
                            val target =
                                listOf(
                                        "monday",
                                        "tuesday",
                                        "wednesday",
                                        "thursday",
                                        "friday",
                                        "saturday",
                                        "sunday",
                                    )
                                    .indexOf(day) + 1
                            if (target < 1) return@mapNotNull null
                            ((target - local.dayOfWeek.value + 7) % 7).let {
                                if (it == 0) 7L else it.toLong()
                            }
                        }
                    }
                val wake =
                    local.toLocalDate().plusDays(daysAhead).atTime(time).atZone(zone).toInstant()
                if (!wake.isAfter(now)) null
                else
                    ThreadSnoozeChoice(
                        "${if (daysAhead == 0L) "Today" else if (daysAhead == 1L) "Tomorrow" else day.replaceFirstChar(Char::uppercase)} $time",
                        wake,
                    )
            } else null
        }
        .sortedBy { it.until.epochSecond / 60 }
        .distinctBy { it.until.epochSecond / 60 }
}

class ThreadStatusTools(private val repository: T3Repository) {
    suspend fun family(threadId: String): List<T3Thread> = repository.fetchThreadFamily(threadId)

    suspend fun openThread(threadId: String) {
        if (repository.state.value.threads.none { it.id == threadId }) family(threadId)
    }

    suspend fun nativeWork(threadId: String) =
        repository.callTool(
            "orchestration.getNativeChildWork",
            buildJsonObject { put("threadId", threadId) },
        ) as? JsonObject ?: error("Invalid native child work response.")

    suspend fun workflowScript(threadId: String, path: String) =
        repository.callTool(
            "orchestration.getWorkflowScript",
            buildJsonObject {
                put("threadId", threadId)
                put("scriptPath", path)
            },
        ) as? JsonObject ?: error("Invalid workflow script response.")

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

    suspend fun stop(thread: T3Thread) {
        if (
            repository.state.value.serverConfig
                ?.obj("environment")
                ?.obj("capabilities")
                ?.bool("threadStop") == true
        )
            dispatch(thread.id, "thread.stop")
        else {
            val run =
                thread.activeRunId
                    ?: thread.latestRunId
                    ?: error("This server cannot stop this background work.")
            dispatch(thread.id, "run.interrupt") {
                put("runId", run)
                put("holdQueue", true)
            }
        }
    }

    suspend fun stopAgent(agent: JsonObject) {
        val version = repository.connectionGeneration
        fun ensureCurrent() {
            if (repository.connectionGeneration != version)
                throw CancellationException("Environment changed")
        }
        val target =
            subagentStopTarget(agent)
                ?: error("This agent cannot be stopped from this environment.")
        if (target.workflow) {
            repository.callTool(
                "orchestration.stopWorkflow",
                buildJsonObject {
                    put("threadId", target.threadId)
                    put("subagentId", target.subagentId)
                },
            )
            return
        }
        if (target.childThreadId != null) {
            if (target.runId != null)
                try {
                    dispatch(target.threadId, "subagent.stop") {
                        put("runId", target.runId)
                        put("subagentId", target.subagentId)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException || failure is EnvironmentHttpException)
                        throw failure
                }
            ensureCurrent()
            val child =
                repository.state.value.threads.firstOrNull { it.id == target.childThreadId }
                    ?: family(target.childThreadId).firstOrNull { it.id == target.childThreadId }
                    ?: error("The child thread is unavailable.")
            ensureCurrent()
            stop(child)
        } else {
            val run = target.runId ?: error("The agent has no active run.")
            dispatch(target.threadId, "subagent.stop") {
                put("runId", run)
                put("subagentId", target.subagentId)
            }
        }
    }

    suspend fun forceStop(thread: T3Thread): JsonObject {
        check(
            repository.state.value.serverConfig
                ?.obj("environment")
                ?.obj("capabilities")
                ?.bool("threadForceStop") == true
        ) {
            "This server does not support force stop."
        }
        return repository.callTool(
            "orchestration.forceStopThread",
            buildJsonObject {
                put("threadId", thread.id)
                (thread.activeRunId ?: thread.latestRunId)?.let { put("runId", it) }
            },
        ) as? JsonObject ?: error("Invalid force-stop response.")
    }

    suspend fun settle(thread: T3Thread) =
        dispatch(thread.id, if (thread.settled) "thread.unsettle" else "thread.settle") {
            if (thread.settled) put("reason", "user")
        }

    suspend fun wake(thread: T3Thread) {
        val recovery = thread.limitRecovery
        if (
            recovery != null &&
                recovery.bool("snooze") &&
                recovery.nullableString("runId") == thread.latestRunId &&
                recovery.nullableString("resetAt") == thread.snoozedUntil
        )
            dispatch(thread.id, "thread.metadata.update") {
                put(
                    "limitRecovery",
                    buildJsonObject {
                        put("runId", recovery.string("runId"))
                        put("resetAt", recovery.string("resetAt"))
                        put("snooze", false)
                    },
                )
            }
        else dispatch(thread.id, "thread.unsnooze") { put("reason", "user") }
    }

    suspend fun dismissWake(thread: T3Thread, now: Instant) =
        dispatch(thread.id, "thread.visit") {
            put("visitedAt", threadWakeTime(thread, now) ?: now.toString())
        }

    suspend fun snooze(thread: T3Thread, until: Instant) {
        require(until.isAfter(Instant.now())) { "Choose a future wake time." }
        dispatch(thread.id, "thread.snooze") { put("snoozedUntil", until.toString()) }
    }
}
