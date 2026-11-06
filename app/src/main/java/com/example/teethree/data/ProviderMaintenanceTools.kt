package com.example.teethree.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ProviderMaintenance(
    val id: String,
    val driver: String,
    val name: String,
    val installed: Boolean,
    val version: String?,
    val latestVersion: String?,
    val canInstall: Boolean,
    val canUpdate: Boolean,
    val canInstallVersion: Boolean,
    val updateStatus: String,
    val updateMessage: String?,
    val updateOutput: String?,
    val policyReason: String?,
)

data class ProviderInstallation(
    val driver: String,
    val operationId: String?,
    val phase: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val installedVersion: String?,
    val executablePath: String?,
    val source: String?,
    val canRemove: Boolean,
    val message: String?,
) {
    val active: Boolean
        get() = phase in setOf("downloading", "extracting", "verifying")
}

class ProviderMaintenanceTools(private val repository: T3Repository) {
    fun provider(instanceId: String): ProviderMaintenance? {
        val config = repository.state.value.serverConfig ?: return null
        val provider =
            config.array("providers").filterIsInstance<JsonObject>().firstOrNull {
                it.string("instanceId") == instanceId
            } ?: return null
        return parseProviderMaintenance(provider, config)
    }

    fun observe(
        instanceId: String,
        onError: (Throwable) -> Unit,
        onValue: (ProviderInstallation) -> Unit,
    ): AutoCloseable =
        repository.streamTool(
            "provider.install.subscribe",
            buildJsonObject { put("instanceId", instanceId) },
            onError,
        ) {
            onValue(parseProviderInstallation(it))
        }

    suspend fun install(instanceId: String): ProviderInstallation {
        val provider = provider(instanceId) ?: error("Provider is unavailable.")
        check(provider.policyReason == null) { provider.policyReason.orEmpty() }
        check(provider.canInstall) {
            "This environment does not offer a managed installation for this provider."
        }
        return parseProviderInstallation(
            repository.callTool(
                "provider.install.start",
                buildJsonObject { put("instanceId", instanceId) },
            ) as? JsonObject ?: error("Invalid installation response.")
        )
    }

    suspend fun cancel(instanceId: String, operationId: String): ProviderInstallation {
        require(operationId.isNotBlank())
        return parseProviderInstallation(
            repository.callTool(
                "provider.install.cancel",
                buildJsonObject {
                    put("instanceId", instanceId)
                    put("operationId", operationId)
                },
            ) as? JsonObject ?: error("Invalid installation response.")
        )
    }

    suspend fun remove(instanceId: String): ProviderInstallation {
        val provider = provider(instanceId) ?: error("Provider is unavailable.")
        check(provider.policyReason == null) { provider.policyReason.orEmpty() }
        return parseProviderInstallation(
            repository.callTool(
                "provider.install.remove",
                buildJsonObject { put("instanceId", instanceId) },
            ) as? JsonObject ?: error("Invalid installation response.")
        )
    }

    suspend fun update(instanceId: String, targetVersion: String? = null) {
        val provider = provider(instanceId) ?: error("Provider is unavailable.")
        check(provider.policyReason == null) { provider.policyReason.orEmpty() }
        check(if (targetVersion == null) provider.canUpdate else provider.canInstallVersion) {
            "This environment cannot install that provider version."
        }
        if (targetVersion != null)
            require(targetVersion.isNotBlank() && targetVersion.none(Char::isISOControl)) {
                "Enter a provider version."
            }
        repository.callTool(
            "server.updateProvider",
            buildJsonObject {
                put("provider", provider.driver)
                put("instanceId", instanceId)
                if (targetVersion != null) put("targetVersion", targetVersion.trim())
            },
        )
    }
}

internal fun parseProviderMaintenance(
    provider: JsonObject,
    config: JsonObject,
): ProviderMaintenance {
    val policy = config.obj("managedPolicy")
    val allowedDrivers = policy.obj("limits").obj("providers")["allowedDrivers"]
    val driverAllowed =
        allowedDrivers == null ||
            policy.obj("limits").obj("providers").array("allowedDrivers").any {
                it == JsonPrimitive(provider.string("driver"))
            }
    val policyReason =
        when {
            !driverAllowed ->
                "${policy.string("organization", "Your organization")} does not allow this provider."
            policy.obj("limits").obj("updates").string("mode") == "off" ->
                "${policy.string("organization", "Your organization")} manages provider updates."
            else -> null
        }
    val advisory = provider.obj("versionAdvisory")
    val update = provider.obj("updateState")
    val available = provider.string("availability") != "unavailable"
    val active = update.string("status") in setOf("queued", "running")
    val compatible =
        provider.obj("compatibilityAdvisory").string("latestVersionStatus") !in
            setOf("broken", "unsupported")
    val installed = provider.bool("installed")
    return ProviderMaintenance(
        provider.string("instanceId"),
        provider.string("driver"),
        provider.string("displayName", provider.string("driver")),
        installed,
        provider.nullableString("installedVersion") ?: provider.nullableString("version"),
        advisory.nullableString("latestVersion"),
        provider.obj("setup").bool("canInstall") && policyReason == null,
        installed &&
            available &&
            advisory.string("status") == "behind_latest" &&
            advisory.bool("canUpdate") &&
            advisory.nullableString("latestVersion") != null &&
            compatible &&
            !active &&
            policyReason == null,
        installed &&
            available &&
            advisory.bool("canInstallVersion") &&
            !active &&
            policyReason == null,
        update.string("status", "idle"),
        update.nullableString("message"),
        update.nullableString("output"),
        policyReason,
    )
}

internal fun parseProviderInstallation(value: JsonObject) =
    ProviderInstallation(
        value.string("driver"),
        value.nullableString("operationId"),
        value.string("phase", "idle"),
        value.long("downloadedBytes"),
        (value["totalBytes"] as? JsonPrimitive)?.content?.toLongOrNull()?.takeIf { it >= 0L },
        value.nullableString("installedVersion"),
        value.nullableString("executablePath"),
        value.nullableString("source"),
        value.bool("canRemove"),
        value.nullableString("message"),
    )
