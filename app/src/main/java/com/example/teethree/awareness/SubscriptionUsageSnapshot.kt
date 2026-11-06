package com.example.teethree.awareness

import com.example.teethree.data.array
import com.example.teethree.data.bool
import com.example.teethree.data.nullableString
import com.example.teethree.data.obj
import com.example.teethree.data.string
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

@Serializable
data class SubscriptionUsageSnapshot(
    val checkedAt: Long = 0L,
    val providers: List<UsageProvider> = emptyList(),
)

@Serializable
data class UsageProvider(
    val name: String,
    val detail: String,
    val windows: List<UsageWindow>,
    val expiresAt: Long,
    val totalWindows: Int,
)

@Serializable
data class UsageWindow(val kind: String, val label: String, val remaining: Int, val reset: String)

private data class LimitWindow(
    val id: String,
    val kind: String,
    val label: String,
    val used: Double,
    val resetsAt: Long?,
)

private data class LimitAccount(
    val key: String,
    val fallback: String,
    val driver: String,
    val workspace: String?,
    val checkedAt: Long,
    val windows: List<LimitWindow>,
)

private val subscriptionDrivers = listOf("codex", "claudeAgent")

fun buildSubscriptionUsageSnapshot(
    configs: Map<String, JsonObject>,
    now: Long = System.currentTimeMillis(),
): SubscriptionUsageSnapshot {
    val configured = mutableSetOf<String>()
    val reports = mutableListOf<LimitAccount>()
    configs.forEach { (environmentId, config) ->
        config.array("providers").forEach providerLoop@{ raw ->
            val provider = raw as? JsonObject ?: return@providerLoop
            val driver = provider.string("driver")
            val limits = provider["usageLimits"] as? JsonObject
            if (
                driver !in subscriptionDrivers ||
                    !provider.bool("enabled") ||
                    !provider.bool("installed")
            )
                return@providerLoop
            if (limits?.obj("unavailable")?.string("reason") != "unsupported") configured += driver
            if (provider.string("availability") == "unavailable") return@providerLoop
            parseLimitAccount(
                    provider,
                    provider.obj("auth"),
                    "$environmentId:${provider.string("instanceId")}",
                )
                ?.let(reports::add)
        }
        config.array("usageLimitSources").forEach sourceLoop@{ raw ->
            val source = raw as? JsonObject ?: return@sourceLoop
            source.array("accounts").forEach accountLoop@{ account ->
                val value = account as? JsonObject ?: return@accountLoop
                parseLimitAccount(
                        value,
                        value,
                        "${source.string("id")}:${value.string("id")}:${value.string("workspaceId")}",
                    )
                    ?.let(reports::add)
            }
        }
    }
    val workspaces =
        reports
            .filter { it.workspace != null }
            .groupBy(LimitAccount::key)
            .mapValues { (_, values) -> values.mapNotNull(LimitAccount::workspace).toSet() }
    val accounts =
        reports
            .groupBy { report ->
                val workspace = report.workspace ?: workspaces[report.key]?.singleOrNull()
                if (workspace != null) "${report.key}:$workspace"
                else if ((workspaces[report.key]?.size ?: 0) > 1) report.fallback else report.key
            }
            .values
            .map { group -> group.maxBy(LimitAccount::checkedAt) }
    val providers =
        subscriptionDrivers
            .filter { it in configured || accounts.any { account -> account.driver == it } }
            .map { driver ->
                val pool = accounts.filter { it.driver == driver }
                val name = if (driver == "codex") "Codex" else "Claude"
                if (pool.isEmpty())
                    return@map UsageProvider(name, "No limits available", emptyList(), 0L, 0)
                val windows = linkedMapOf<String, MutableList<Pair<LimitAccount, LimitWindow>>>()
                pool.forEach { account ->
                    account.windows.forEach { reported ->
                        val window = usableWindow(account, reported, now)
                        val pooled =
                            if (driver == "codex") window.kind else "${window.kind}:${window.id}"
                        val key =
                            if (windows[pooled]?.any { it.first === account } == true)
                                "${window.kind}:${window.id}"
                            else pooled
                        windows.getOrPut(key, ::mutableListOf) += account to window
                    }
                }
                val expiry =
                    (listOf(pool.minOf { it.checkedAt } + 900_000L) +
                            windows.values.flatten().mapNotNull { it.second.resetsAt })
                        .min()
                val sorted =
                    windows.values
                        .map { members ->
                            val first = members.first().second
                            val remaining =
                                (100 - members.sumOf { it.second.used } / members.size)
                                    .roundToInt()
                                    .coerceIn(0, 100)
                            val reset = members.mapNotNull { it.second.resetsAt }.minOrNull()
                            UsageWindow(
                                first.kind,
                                first.label,
                                remaining,
                                reset?.let { "Next reset ${formatUsageTime(it)}" }
                                    ?: "Reset time unavailable",
                            )
                        }
                        .sortedBy(UsageWindow::remaining)
                val selected =
                    (listOfNotNull(
                            sorted.firstOrNull { it.kind == "session" },
                            sorted.firstOrNull { it.kind == "weekly" },
                        ) + sorted)
                        .distinct()
                        .sortedBy(UsageWindow::remaining)
                if (expiry <= now)
                    UsageProvider(name, "Open teethree to refresh", emptyList(), 0L, 0)
                else
                    UsageProvider(
                        name,
                        if (pool.size > 1) "${pool.size} accounts · pooled"
                        else "Subscription remaining",
                        selected,
                        expiry,
                        windows.size,
                    )
            }
    return SubscriptionUsageSnapshot(accounts.minOfOrNull(LimitAccount::checkedAt) ?: 0L, providers)
}

private fun parseLimitAccount(
    value: JsonObject,
    identity: JsonObject,
    fallback: String,
): LimitAccount? {
    val driver = value.string("driver")
    if (driver !in subscriptionDrivers) return null
    val limits = value["usageLimits"] as? JsonObject ?: return null
    if (limits["unavailable"] is JsonObject) return null
    val checked = parseUsageInstant(limits.string("checkedAt")) ?: return null
    val email = identity.string("email").trim().lowercase()
    val fingerprint = limits.string("credentialFingerprint")
    val key =
        when {
            email.isNotEmpty() -> "$driver:$email"
            fingerprint.isNotEmpty() -> "$driver:credential:$fingerprint"
            else -> fallback
        }
    val windows =
        limits.array("windows").mapNotNull { raw ->
            val window = raw as? JsonObject ?: return@mapNotNull null
            val used =
                (window["usedPercent"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (
                !used.isFinite() ||
                    used !in 0.0..100.0 ||
                    window.string("kind") !in setOf("session", "weekly", "monthly", "other")
            )
                return@mapNotNull null
            LimitWindow(
                window.string("id"),
                window.string("kind"),
                window.string("label"),
                used,
                parseUsageInstant(window.string("resetsAt")),
            )
        }
    if (windows.isEmpty()) return null
    return LimitAccount(
        key,
        fallback,
        driver,
        identity.nullableString("workspaceId"),
        checked,
        windows,
    )
}

private fun usableWindow(account: LimitAccount, reported: LimitWindow, now: Long): LimitWindow {
    if (reported.kind != "session") return reported
    val blocked =
        account.windows.firstOrNull {
            it.used >= 100 &&
                if (account.driver == "claudeAgent") it.id == "seven_day" else it.kind == "weekly"
        } ?: return reported
    val cutoff = maxOf(now, account.checkedAt)
    val sessionReset = reported.resetsAt
    val weeklyReset = blocked.resetsAt
    val reset =
        if (
            sessionReset != null &&
                weeklyReset != null &&
                sessionReset > cutoff &&
                weeklyReset > cutoff
        ) {
            if (sessionReset > weeklyReset && reported.used >= 100) sessionReset else weeklyReset
        } else null
    return reported.copy(used = 100.0, resetsAt = reset)
}

internal fun parseUsageInstant(value: String): Long? = runCatching {
    Instant.parse(value).toEpochMilli()
}
    .getOrNull()

internal fun formatUsageTime(value: Long): String =
    DateTimeFormatter.ofPattern("MMM d, HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(value))
