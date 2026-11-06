package com.example.teethree.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class EnvironmentProvider(
    val id: String,
    val name: String,
    val driver: String,
    val enabled: Boolean,
    val installed: Boolean,
    val status: String,
    val authStatus: String,
    val account: String?,
    val message: String?,
    val canAuthenticate: Boolean,
    val canLogout: Boolean,
    val models: List<T3Model>,
    val documentationUrl: String?,
)

data class EnvironmentAuthMethod(
    val id: String,
    val name: String,
    val description: String?,
    val accountEmail: String? = null,
)

data class EnvironmentCredentialField(val name: String, val label: String, val secret: Boolean)

data class EnvironmentAuthInteraction(
    val type: String,
    val id: String,
    val url: String?,
    val userCode: String?,
    val output: String,
    val requiresConsent: Boolean,
    val acceptsCallback: Boolean,
    val fields: List<EnvironmentCredentialField>,
)

data class EnvironmentAuthState(
    val phase: String,
    val flowId: String?,
    val url: String?,
    val message: String?,
    val methods: List<EnvironmentAuthMethod>,
    val interaction: EnvironmentAuthInteraction?,
)

data class EnvironmentTask(
    val id: String,
    val title: String,
    val prompt: String,
    val enabled: Boolean,
    val projectId: String,
    val threadId: String?,
    val model: ModelSelection,
    val schedule: JsonObject,
    val workspace: JsonObject,
    val modelData: JsonObject,
    val runtimeMode: String,
    val interactionMode: String,
    val creationSource: String,
    val nextRunAt: String?,
    val lastRunStatus: String,
    val lastRunError: String?,
    val blocked: String?,
)

class EnvironmentTools(private val repository: T3Repository) {
    suspend fun providers(fresh: Boolean = false): List<EnvironmentProvider> {
        val result =
            repository
                .callTool(
                    "server.refreshProviders",
                    buildJsonObject {
                        if (fresh) {
                            put("fresh", true)
                            put("refreshModels", true)
                        }
                    },
                )
                .jsonObject
        return result.array("providers").map { parseEnvironmentProvider(it.jsonObject) }
    }

    fun subscribeAuth(
        instanceId: String,
        onError: (Throwable) -> Unit,
        onValue: (EnvironmentAuthState) -> Unit,
    ): AutoCloseable =
        repository.streamTool(
            "provider.auth.subscribe",
            buildJsonObject { put("instanceId", instanceId) },
            onError,
        ) {
            onValue(parseEnvironmentAuthState(it))
        }

    suspend fun startAuth(instanceId: String, methodId: String? = null) =
        authCall(
            "provider.auth.start",
            buildJsonObject {
                put("instanceId", instanceId)
                if (methodId != null) put("methodId", methodId)
            },
        )

    suspend fun respondAuth(
        instanceId: String,
        auth: EnvironmentAuthState,
        response: JsonObject,
    ): EnvironmentAuthState {
        val flow = requireNotNull(auth.flowId) { "This sign-in flow is no longer active." }
        val interaction =
            requireNotNull(auth.interaction) { "This sign-in no longer needs a response." }
        return authCall(
            "provider.auth.respond",
            buildJsonObject {
                put("instanceId", instanceId)
                put("flowId", flow)
                put("interactionId", interaction.id)
                put("response", response)
            },
        )
    }

    suspend fun completeAuth(
        instanceId: String,
        auth: EnvironmentAuthState,
        callbackUrl: String,
    ): EnvironmentAuthState {
        require(callbackUrl.isNotBlank() && callbackUrl.length <= 16_384) {
            "Paste the complete callback URL from the browser."
        }
        return authCall(
            "provider.auth.complete",
            buildJsonObject {
                put("instanceId", instanceId)
                put("flowId", requireNotNull(auth.flowId))
                put("callbackUrl", callbackUrl.trim())
            },
        )
    }

    suspend fun cancelAuth(instanceId: String, auth: EnvironmentAuthState) =
        authCall(
            "provider.auth.cancel",
            buildJsonObject {
                put("instanceId", instanceId)
                put("flowId", requireNotNull(auth.flowId))
            },
        )

    suspend fun logout(instanceId: String) =
        authCall("provider.auth.logout", buildJsonObject { put("instanceId", instanceId) })

    private suspend fun authCall(method: String, payload: JsonObject) =
        parseEnvironmentAuthState(repository.callTool(method, payload).jsonObject)

    fun subscribeTasks(
        onError: (Throwable) -> Unit,
        onValue: (List<EnvironmentTask>) -> Unit,
    ): AutoCloseable =
        repository.streamTool("scheduledTasks.subscribe", JsonObject(emptyMap()), onError) { value
            ->
            onValue(value.array("tasks").map { parseEnvironmentTask(it.jsonObject) })
        }

    suspend fun setTaskEnabled(task: EnvironmentTask, enabled: Boolean) {
        repository.callTool(
            "scheduledTasks.setEnabled",
            buildJsonObject {
                put("id", task.id)
                put("enabled", enabled)
            },
        )
    }

    suspend fun runTask(task: EnvironmentTask) {
        repository.callTool("scheduledTasks.runNow", buildJsonObject { put("id", task.id) })
    }

    suspend fun deleteTask(task: EnvironmentTask) {
        repository.callTool("scheduledTasks.delete", buildJsonObject { put("id", task.id) })
    }

    suspend fun saveTask(
        existing: EnvironmentTask?,
        title: String,
        prompt: String,
        enabled: Boolean,
        projectId: String,
        model: ModelSelection,
        schedule: JsonObject,
        workspace: JsonObject,
        runtimeMode: String,
    ) {
        require(title.isNotBlank() && prompt.isNotBlank()) { "Enter a task name and prompt." }
        require(
            runtimeMode in setOf("approval-required", "auto-accept-edits", "auto", "full-access")
        ) {
            "Choose a valid permission mode."
        }
        repository.callTool(
            "scheduledTasks.upsert",
            buildJsonObject {
                if (existing != null) {
                    put("id", existing.id)
                    put("requireExisting", true)
                }
                put("commandId", newId())
                put("title", title.trim())
                put("prompt", prompt.trim())
                put("enabled", enabled)
                put("projectId", projectId)
                put("schedule", schedule)
                put("threadId", existing?.threadId?.let(::JsonPrimitive) ?: JsonNull)
                put("workspaceStrategy", workspace)
                put(
                    "modelSelection",
                    if (existing?.model == model) existing.modelData else model.wire(),
                )
                put("runtimeMode", runtimeMode)
                put("interactionMode", existing?.interactionMode ?: "default")
                put("creationSource", existing?.creationSource ?: "mobile")
            },
        )
    }
}

internal fun providerBrowserUrlAllowed(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme?.lowercase() in setOf("https", "http") &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null
}
    .getOrDefault(false)

internal fun parseEnvironmentProvider(value: JsonObject) =
    EnvironmentProvider(
        value.string("instanceId"),
        value.string("displayName", value.string("driver")),
        value.string("driver"),
        value.bool("enabled"),
        value.bool("installed"),
        value.string("status"),
        value.obj("auth").string("status"),
        value.obj("auth").nullableString("email"),
        value.nullableString("message") ?: value.nullableString("unavailableReason"),
        value.obj("setup").bool("canAuthenticate"),
        value.obj("auth").bool("canLogout", value.obj("setup").bool("canAuthenticate")),
        value.array("models").map { raw ->
            raw.jsonObject.let {
                T3Model(
                    it.string("slug"),
                    it.string("name"),
                    it.bool("isDefault"),
                    it.obj("capabilities").array("optionDescriptors"),
                )
            }
        },
        value.obj("setup").nullableString("documentationUrl"),
    )

internal fun parseEnvironmentAuthState(value: JsonObject): EnvironmentAuthState {
    val raw = value["interaction"] as? JsonObject
    val interaction =
        raw?.takeIf {
                it.string("type") in setOf("browser", "deviceCode", "terminal", "credentials")
            }
            ?.let {
                EnvironmentAuthInteraction(
                    it.string("type"),
                    it.string("id"),
                    it.nullableString("url"),
                    it.nullableString("userCode"),
                    it.string("output"),
                    it.bool("requiresConsent"),
                    it.bool("acceptsCallback"),
                    it.array("fields").map { field ->
                        field.jsonObject.let { data ->
                            EnvironmentCredentialField(
                                data.string("name"),
                                data.string("label"),
                                data.bool("secret"),
                            )
                        }
                    },
                )
            }
    return EnvironmentAuthState(
        value.string("phase"),
        value.nullableString("flowId"),
        value.nullableString("authorizationUrl"),
        value.nullableString("message"),
        value.array("methods").mapNotNull { rawMethod ->
            rawMethod.jsonObject.let {
                if (it.string("type") !in setOf("agent", "terminal", "credentials")) null
                else
                    EnvironmentAuthMethod(
                        it.string("id"),
                        it.string("name"),
                        it.nullableString("description"),
                        it.nullableString("accountEmail"),
                    )
            }
        },
        interaction,
    )
}

internal fun parseEnvironmentTask(value: JsonObject) =
    EnvironmentTask(
        value.string("id"),
        value.string("title"),
        value.string("prompt"),
        value.bool("enabled"),
        value.string("projectId"),
        value.nullableString("threadId"),
        parseModelSelection(value.obj("modelSelection")),
        value.obj("schedule"),
        value.obj("workspaceStrategy"),
        value.obj("modelSelection"),
        value.string("runtimeMode"),
        value.string("interactionMode"),
        value.string("creationSource", "mobile"),
        value.nullableString("nextRunAt"),
        value.string("lastRunStatus"),
        value.nullableString("lastRunError"),
        value.nullableString("blockedByPolicy"),
    )

internal fun buildEnvironmentSchedule(
    type: String,
    intervalMinutes: String,
    timeOfDay: String,
    weekdays: Set<Int>,
    onceLocal: String,
    zone: String,
): JsonObject = buildJsonObject {
    put("type", type)
    when (type) {
        "interval" -> {
            val minutes = intervalMinutes.replace(',', '.').toBigDecimalOrNull()
            require(minutes != null && minutes >= BigDecimal.ONE) {
                "Use an interval of at least one minute."
            }
            val milliseconds =
                minutes
                    .multiply(BigDecimal(60_000))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact()
            require(milliseconds <= 9_007_199_254_740_991L) { "This interval is too long." }
            put("everyMs", milliseconds)
        }
        "fixed_time" -> {
            val time =
                LocalTime.parse(
                    timeOfDay,
                    DateTimeFormatter.ofPattern("H:mm").withResolverStyle(ResolverStyle.STRICT),
                )
            require(weekdays.isNotEmpty() && weekdays.all { it in 0..6 }) {
                "Choose at least one weekday."
            }
            put("timeOfDay", time.format(DateTimeFormatter.ofPattern("HH:mm")))
            if (weekdays.size < 7)
                put("weekdays", JsonArray(weekdays.sorted().map(::JsonPrimitive)))
        }
        "once" -> {
            val local =
                LocalDateTime.parse(
                    onceLocal,
                    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                        .withResolverStyle(ResolverStyle.STRICT),
                )
            require(local.year in 1..9999) { "Choose a date between years 1 and 9999." }
            val zoneId = ZoneId.of(zone)
            val offsets = zoneId.rules.getValidOffsets(local)
            require(offsets.isNotEmpty()) {
                "This local time does not exist in $zone. Choose another time."
            }
            put("runAt", local.toInstant(offsets.first()).toString())
        }
        else -> throw IllegalArgumentException("Choose an interval, daily, or one-time schedule.")
    }
}
