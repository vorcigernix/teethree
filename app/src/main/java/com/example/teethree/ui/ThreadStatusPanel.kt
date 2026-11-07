package com.example.teethree.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.T3Repository
import com.example.teethree.data.T3Thread
import com.example.teethree.data.ThreadStatusTools
import com.example.teethree.data.activeSubagent
import com.example.teethree.data.array
import com.example.teethree.data.bool
import com.example.teethree.data.hiddenThreadIds
import com.example.teethree.data.long
import com.example.teethree.data.nullableString
import com.example.teethree.data.obj
import com.example.teethree.data.parsedInstant
import com.example.teethree.data.providerManagedChild
import com.example.teethree.data.string
import com.example.teethree.data.subagentStopTarget
import com.example.teethree.data.threadSnoozeChoices
import com.example.teethree.data.threadSnoozed
import com.example.teethree.data.threadWakeUnseen
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

@Composable
fun ThreadStatusPanel(
    repository: T3Repository,
    thread: T3Thread,
    onOpenThread: (String) -> Unit,
    open: Boolean,
    onDismiss: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val tools = remember(repository) { ThreadStatusTools(repository) }
    val scope = rememberCoroutineScope()
    var busy by remember(thread.id) { mutableStateOf(false) }
    var error by remember(thread.id) { mutableStateOf<String?>(null) }
    var notice by remember(thread.id) { mutableStateOf<String?>(null) }
    var family by remember(thread.id) { mutableStateOf<List<T3Thread>?>(null) }
    var familyQuery by remember(thread.id) { mutableStateOf("") }
    var document by remember(thread.id) { mutableStateOf<Pair<String, String>?>(null) }
    var nativeWork by remember(thread.id) { mutableStateOf<JsonObject?>(null) }
    var stopping by remember(thread.id) { mutableStateOf<JsonObject?>(null) }
    var forcing by remember(thread.id) { mutableStateOf<T3Thread?>(null) }
    var snoozing by remember(thread.id) { mutableStateOf(false) }
    var now by remember(thread.id) { mutableStateOf(Instant.now()) }
    val canRead =
        state.connectionStatus == ConnectionStatus.Connected && "orchestration:read" in state.scopes
    val canOperate =
        state.connectionStatus == ConnectionStatus.Connected &&
            "orchestration:operate" in state.scopes &&
            !busy
    val capabilities = state.serverConfig?.obj("environment")?.obj("capabilities")
    val agents =
        if (state.selectedThreadId == thread.id) state.subagents.filterIsInstance<JsonObject>()
        else emptyList()
    val tasks = thread.pendingBackgroundTasks.filterIsInstance<JsonObject>()
    val snoozed = threadSnoozed(thread, now)
    val woke = threadWakeUnseen(thread, now)
    val hidden = hiddenThreadIds(state.threads + family.orEmpty(), state.serverConfig)
    val goal = thread.goal
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                block()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "The request failed."
            } finally {
                busy = false
            }
        }
    }
    fun openThread(id: String) {
        action {
            tools.openThread(id)
            onDismiss()
            onOpenThread(id)
        }
    }
    LaunchedEffect(thread.id, thread.snoozedUntil, thread.updatedAt) {
        now = Instant.now()
        parsedInstant(thread.snoozedUntil)?.let { until ->
            val remaining = Duration.between(now, until).toMillis()
            if (remaining > 0) {
                delay(remaining)
                now = Instant.now()
            }
        }
    }
    LaunchedEffect(open) { if (open) now = Instant.now() }
    if (open)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Status and agents") },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        thread.status.replaceFirstChar(Char::uppercase),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (providerManagedChild(thread))
                        Text(
                            "This agent is managed by its provider. Its transcript is read only; approvals and Stop remain available."
                        )
                    thread.lineage.nullableString("parentThreadId")?.let { parent ->
                        TextButton(
                            onClick = { openThread(parent) },
                            enabled = canRead && !busy && parent !in hidden,
                        ) {
                            Text("Open parent conversation")
                        }
                    }
                    if (busy) Text("Updating…", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (snoozed) Text("Snoozed until ${thread.snoozedUntil}")
                    else if (thread.settled) Text("Settled") else if (woke) Text("Woke from snooze")
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        if (snoozed)
                            TextButton(
                                onClick = { action { tools.wake(thread) } },
                                enabled = canOperate,
                            ) {
                                Text("Wake now")
                            }
                        else if (woke)
                            TextButton(
                                onClick = { action { tools.dismissWake(thread, now) } },
                                enabled = canOperate,
                            ) {
                                Text("Dismiss wake")
                            }
                        TextButton(
                            onClick = { action { tools.settle(thread) } },
                            enabled = canOperate && !thread.archived,
                        ) {
                            Text(if (thread.settled) "Un-settle" else "Settle")
                        }
                        if (capabilities?.bool("threadSnooze") == true && !snoozed)
                            TextButton(
                                onClick = {
                                    now = Instant.now()
                                    snoozing = true
                                },
                                enabled =
                                    canOperate &&
                                        !thread.hasPendingRuntimeRequest &&
                                        thread.pendingGuestMessageCount == 0,
                            ) {
                                Text("Snooze…")
                            }
                    }
                    if (thread.activeRunId != null || tasks.isNotEmpty())
                        TextButton(
                            onClick = { action { tools.stop(thread) } },
                            enabled = canOperate,
                        ) {
                            Text(if (tasks.isNotEmpty()) "Stop background work" else "Stop agent")
                        }
                    if (
                        thread.latestRunStopRequested &&
                            capabilities?.bool("threadForceStop") == true
                    )
                        TextButton(onClick = { forcing = thread }, enabled = canOperate) {
                            Text("Force stop…")
                        }
                    goal?.let { value ->
                        HorizontalDivider()
                        val title =
                            when (value.string("status")) {
                                "active" ->
                                    if (thread.activeRunId == null) "Goal set" else "Pursuing goal"
                                "paused" -> "Goal paused"
                                "blocked" -> "Goal blocked"
                                "usage_limited" -> "Goal hit a usage limit"
                                "budget_limited" -> "Goal reached its token budget"
                                "complete" -> "Goal complete"
                                else -> "Goal"
                            }
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(value.string("objective")) }
                        val usage =
                            listOfNotNull(
                                value
                                    .long("tokensUsed")
                                    .takeIf { it > 0 }
                                    ?.let { used ->
                                        value.nullableString("tokenBudget")?.let {
                                            "$used / $it tokens"
                                        } ?: "$used tokens"
                                    },
                                value
                                    .long("timeUsedSeconds")
                                    .takeIf { it >= 60 }
                                    ?.let { "${it / 60} minutes" },
                                value.long("checks").takeIf { it > 0 }?.let { "$it checks" },
                            )
                        if (usage.isNotEmpty())
                            Text(
                                usage.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                    if (tasks.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Background work", style = MaterialTheme.typography.titleSmall)
                        tasks.forEach { task ->
                            Text(
                                "${task.nullableString("description") ?: task.string("taskId")} · ${task.string("kind").replace('_', ' ')}${if (task.bool("awaited")) " · waiting" else ""}"
                            )
                            task
                                .nullableString("childThreadId")
                                ?.takeIf { it !in hidden }
                                ?.let { id ->
                                    TextButton(
                                        onClick = { openThread(id) },
                                        enabled = canRead && !busy,
                                    ) {
                                        Text("Open child conversation")
                                    }
                                }
                        }
                    }
                    if (agents.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Agents and workflows", style = MaterialTheme.typography.titleSmall)
                    }
                    agents.forEach { agent ->
                        AgentStatusDetails(
                            agent,
                            canRead && !busy,
                            canOperate,
                            onOpenThread = ::openThread,
                            onStop = { stopping = agent },
                            onReadScript = { path ->
                                action {
                                    val script = tools.workflowScript(thread.id, path)
                                    document =
                                        script.string("scriptPath") to
                                            (script.string("contents") +
                                                if (script.bool("truncated"))
                                                    "\n[The server truncated this file.]"
                                                else "")
                                }
                            },
                        )
                    }
                    val driver =
                        state.serverConfig
                            ?.array("providers")
                            ?.filterIsInstance<JsonObject>()
                            ?.firstOrNull {
                                it.string("instanceId") == thread.modelSelection.instanceId
                            }
                            ?.string("driver")
                    if (providerManagedChild(thread) && driver == "antigravity")
                        TextButton(
                            onClick = { action { nativeWork = tools.nativeWork(thread.id) } },
                            enabled = canRead && !busy,
                        ) {
                            Text("Inspect provider child work")
                        }
                    nativeWork?.array("children")?.filterIsInstance<JsonObject>()?.forEach { child
                        ->
                        Text(child.string("role"), style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(child.string("prompt")) }
                        child.array("steps").filterIsInstance<JsonObject>().forEach { step ->
                            SelectionContainer {
                                Text(
                                    "${step.string("type")} · ${step.nullableString("status").orEmpty()}\n${step.nullableString("text").orEmpty()}" +
                                        step
                                            .array("toolCalls")
                                            .filterIsInstance<JsonObject>()
                                            .joinToString("\n", prefix = "\n") {
                                                "${it.string("name")}: ${it.string("input")}"
                                            },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (child.bool("truncated"))
                            Text(
                                "Earlier steps were omitted by the provider.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        if (child["steps"] !is kotlinx.serialization.json.JsonArray)
                            Text("This provider has no readable transcript for this child.")
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = { action { family = tools.family(thread.id) } },
                        enabled = canRead && !busy,
                    ) {
                        Text(
                            if (family == null) "Load conversation family"
                            else "Refresh conversation family"
                        )
                    }
                    family?.let { relatives ->
                        OutlinedTextField(
                            familyQuery,
                            { familyQuery = it },
                            label = { Text("Find a family conversation") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        val filtered = relatives.filter {
                            it.id !in hidden &&
                                (familyQuery.isBlank() ||
                                    it.title.contains(familyQuery, true) ||
                                    it.id.contains(familyQuery, true))
                        }
                        filtered.take(100).forEach { child ->
                            TextButton(
                                onClick = { openThread(child.id) },
                                enabled = canRead && !busy && child.id != thread.id,
                            ) {
                                Text(
                                    "${child.title.ifBlank { child.id }} · ${child.lineage.nullableString("relationshipToParent") ?: "root"} · ${child.status}"
                                )
                            }
                        }
                        if (filtered.size > 100)
                            Text(
                                "Showing 100 matches. Narrow the search to find another conversation."
                            )
                        if (filtered.isEmpty()) Text("No visible family conversations match.")
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
    stopping?.let { agent ->
        AlertDialog(
            onDismissRequest = { stopping = null },
            title = {
                Text(if (agent["workflow"] is JsonObject) "Stop workflow?" else "Stop agent?")
            },
            text = {
                Text(
                    if (agent["workflow"] is JsonObject)
                        "Every running agent in this workflow stops."
                    else "Stop this agent's current work?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        stopping = null
                        action { tools.stopAgent(agent) }
                    },
                    enabled = canOperate,
                ) {
                    Text("Stop")
                }
            },
            dismissButton = { TextButton(onClick = { stopping = null }) { Text("Cancel") } },
        )
    }
    forcing?.let { captured ->
        AlertDialog(
            onDismissRequest = { forcing = null },
            title = { Text("Force stop this turn?") },
            text = {
                Text(
                    "The environment will release or kill the stuck agent process. A process shared with other conversations may stay running."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        forcing = null
                        action {
                            val result = tools.forceStop(captured)
                            notice =
                                when (result.string("outcome")) {
                                    "shared" ->
                                        "Turn stopped. The process remains shared with ${result.array("sharedWithThreadIds").size} other conversations."
                                    "releasing" -> "Turn stopped. The agent is still shutting down."
                                    else -> "Turn stopped."
                                }
                        }
                    },
                    enabled = canOperate,
                ) {
                    Text("Force stop")
                }
            },
            dismissButton = { TextButton(onClick = { forcing = null }) { Text("Cancel") } },
        )
    }
    if (snoozing)
        AlertDialog(
            onDismissRequest = { snoozing = false },
            title = { Text("Snooze conversation") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    threadSnoozeChoices(state.serverConfig, now).forEach { choice ->
                        TextButton(
                            onClick = {
                                snoozing = false
                                action { tools.snooze(thread, choice.until) }
                            },
                            enabled = canOperate,
                        ) {
                            Text(choice.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { snoozing = false }) { Text("Cancel") } },
        )
    document?.let { content ->
        AlertDialog(
            onDismissRequest = { document = null },
            title = { Text(content.first.substringAfterLast('/')) },
            text = {
                SelectionContainer {
                    Text(
                        content.second,
                        Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                        fontFamily = T3MonoFontFamily,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { document = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun AgentStatusDetails(
    agent: JsonObject,
    canRead: Boolean,
    canOperate: Boolean,
    onOpenThread: (String) -> Unit,
    onStop: () -> Unit,
    onReadScript: (String) -> Unit,
) {
    var detail by remember(agent.string("id")) { mutableStateOf(false) }
    val workflow = agent["workflow"] as? JsonObject
    Text(
        workflow?.nullableString("name")
            ?: agent.nullableString("title")?.takeIf(String::isNotBlank)
            ?: agent.string("prompt").take(160).ifBlank { "Subagent" },
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        agent.string("status").replaceFirstChar(Char::uppercase),
        style = MaterialTheme.typography.bodySmall,
    )
    agent.nullableString("progress")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        agent.nullableString("childThreadId")?.let { id ->
            TextButton(onClick = { onOpenThread(id) }, enabled = canRead) {
                Text("Open conversation")
            }
        }
        if (subagentStopTarget(agent) != null)
            TextButton(onClick = onStop, enabled = canOperate) {
                Text(if (workflow != null) "Stop workflow" else "Stop agent")
            }
        TextButton(onClick = { detail = !detail }) {
            Text(if (detail) "Hide details" else "Details")
        }
    }
    agent["remoteChild"]?.let {
        Text(
            "Runs on ${agent.obj("remoteChild").string("label")}. Open that environment to view its conversation.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (detail) {
        SelectionContainer {
            Text(agent.string("prompt"), style = MaterialTheme.typography.bodySmall)
        }
        agent.nullableString("result")?.let {
            SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        workflow?.let { value ->
            val phases =
                value.array("phases").filterIsInstance<JsonObject>().associate {
                    it.long("index") to it.string("title")
                }
            value
                .array("agents")
                .filterIsInstance<JsonObject>()
                .sortedWith(
                    compareBy<JsonObject> { it.long("phaseIndex", Long.MAX_VALUE) }
                        .thenBy { it.long("index") }
                )
                .forEach { member ->
                    Text(
                        "${phases[member.long("phaseIndex", -1)]?.let { "$it · " }.orEmpty()}${member.string("label")} · ${member.string("state")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    member.nullableString("model")?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                    member.nullableString("childThreadId")?.let { id ->
                        TextButton(onClick = { onOpenThread(id) }, enabled = canRead) {
                            Text("Open ${member.string("label")}")
                        }
                    }
                    member.nullableString("prompt")?.let {
                        SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    member.nullableString("result")?.let {
                        SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            val metrics =
                listOfNotNull(
                    value.long("totalTokens").takeIf { it > 0 }?.let { "$it tokens" },
                    value.long("toolCalls").takeIf { it > 0 }?.let { "$it tool calls" },
                )
            if (metrics.isNotEmpty())
                Text(metrics.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            value.obj("runHandles").nullableString("scriptPath")?.let { path ->
                TextButton(onClick = { onReadScript(path) }, enabled = canRead) {
                    Text("Read workflow script")
                }
            }
        }
    }
}
