package com.example.teethree.data

import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.*

const val NATIVE_USAGE_CONTRACT_VERSION = 6
private val usageProviders =
    setOf("claude", "codex", "grok", "cursor", "opencode", "antigravity", "pi", "muse")

data class UsageTokens(
    val input: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
) {
    val total: Long
        get() = input + cacheRead + cacheWrite + output
}

data class NativeUsageBucket(
    val day: String,
    val hour: String?,
    val provider: String,
    val model: String,
    val sourcePath: String?,
    val tokens: UsageTokens,
    val cost: Double,
    val cacheSavings: Double,
    val costSource: String,
    val records: Long,
    val unpricedRecords: Long,
    val categoryCosts: Map<String, Double>,
    val fastCost: Double,
    val ultrafastCost: Double,
    val speedPremium: Double,
)

data class UsageFingerprint(
    val host: String,
    val provider: String,
    val home: String,
    val volume: String,
)

data class NativeUsageSource(
    val fingerprint: UsageFingerprint,
    val status: String,
    val sessions: Long,
    val message: String?,
    val malformed: Long,
    val action: String?,
    val refreshing: Boolean,
)

data class NativeUsageReport(
    val version: Int,
    val readAt: String,
    val timeZone: String,
    val buckets: List<NativeUsageBucket>,
    val sources: List<NativeUsageSource>,
    val pricing: JsonObject,
    val modelFilter: JsonObject?,
) {
    val refreshing: Boolean
        get() = sources.any { it.refreshing }
}

data class EnvironmentUsageReport(
    val environmentId: String,
    val label: String,
    val report: NativeUsageReport,
)

data class UsageBreakdown(
    val key: String,
    val provider: String,
    val cost: Double,
    val tokens: UsageTokens,
    val records: Long,
    val unpricedRecords: Long,
) {
    val costUnknown: Boolean
        get() = records > 0 && unpricedRecords >= records
}

data class UsagePeriod(val key: String, val cost: Double, val tokens: Long)

data class NativeUsageTotals(
    val cost: Double,
    val tokens: UsageTokens,
    val records: Long,
    val sessions: Long,
    val providers: List<UsageBreakdown>,
    val models: List<UsageBreakdown>,
    val daily: List<UsagePeriod>,
    val hourly: List<UsagePeriod>,
    val cacheSavings: Double,
    val categoryCosts: Map<String, Double>,
    val speedCosts: Map<String, Double>,
    val reportedRecords: Long,
    val unpricedRecords: Long,
    val duplicateSources: List<String>,
    val warnings: List<String>,
)

data class NativeLimitWindow(
    val id: String,
    val kind: String,
    val label: String,
    val used: Double,
    val resetsAt: String?,
    val durationMinutes: Long?,
) {
    val remaining: Int
        get() = kotlin.math.round(100 - used).toInt().coerceIn(0, 100)
}

data class NativeResetCredits(
    val available: Long,
    val nextExpiresAt: String?,
    val nextCreditId: String?,
)

data class NativeLimitAccount(
    val key: String,
    val driver: String,
    val name: String,
    val email: String?,
    val plan: String?,
    val environments: Set<String>,
    val sourceLabel: String?,
    val checkedAt: String,
    val windows: List<NativeLimitWindow>,
    val credits: NativeResetCredits?,
    val redeemEnvironmentId: String?,
    val redeemInput: JsonObject?,
    val externalUrl: String?,
)

data class NativeLimitPool(
    val driver: String,
    val label: String,
    val remaining: Int,
    val accountCount: Int,
    val id: String,
)

data class NativeUsageLimits(
    val accounts: List<NativeLimitAccount>,
    val notices: List<String>,
    val externalLinks: Map<String, String>,
)

class UsageTools(private val repository: T3Repository) {
    suspend fun summary(window: JsonObject, awaitRefresh: Boolean = false): NativeUsageReport {
        val modelFilter =
            repository.state.value.serverConfig?.obj("settings")?.get("usageModelFilter")
                as? JsonObject
        val payload = buildJsonObject {
            window.forEach { (key, value) -> put(key, value) }
            if (awaitRefresh) put("awaitRefresh", true)
            if (modelFilter != null) put("modelFilter", modelFilter)
        }
        return parseNativeUsageReport(
            repository.callTool("server.getUsageSummary", payload).jsonObject
        )
    }

    suspend fun refreshRates() {
        repository.callTool("server.refreshUsageRates", JsonObject(emptyMap()))
    }

    suspend fun refreshLimits(): JsonObject =
        repository.callTool("server.refreshProviders", JsonObject(emptyMap())).jsonObject

    suspend fun consumeResetCredit(account: NativeLimitAccount): String {
        require(account.redeemEnvironmentId == repository.connectionId) {
            "Open the account's environment before using its reset credit."
        }
        require((account.credits?.available ?: 0) > 0) { "No reset credits are available." }
        val result =
            repository
                .callTool("provider.consumeResetCredit", requireNotNull(account.redeemInput))
                .jsonObject
        return result.nullableString("warning")
            ?: when (result.string("outcome")) {
                "reset" -> "Reset applied. Your rate-limit windows have cleared."
                "nothingToReset" -> "Nothing to reset right now."
                "noCredit" -> "No reset credit left."
                "alreadyRedeemed" -> "That credit was already redeemed."
                else ->
                    throw IOException(
                        "The environment returned an unknown reset-credit result. Refresh your account."
                    )
            }
    }

    suspend fun enableCursorKeychain() {
        repository.callTool(
            "server.updateSettings",
            buildJsonObject {
                put("patch", buildJsonObject { put("cursorKeychainUsageEnabled", true) })
            },
        )
    }
}

internal fun nativeUsageWindow(
    days: Int,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): JsonObject {
    require(days in setOf(1, 7, 30, 90))
    val until = if (days == 1) now.truncatedTo(ChronoUnit.MINUTES) else now
    val since = if (days == 1) until.minus(24, ChronoUnit.HOURS) else null
    val untilDay = until.atZone(zone).toLocalDate()
    return buildJsonObject {
        put(
            "sinceDay",
            (since?.atZone(zone)?.toLocalDate() ?: untilDay.minusDays(days - 1L)).toString(),
        )
        put("untilDay", untilDay.toString())
        put("timeZone", zone.id)
        put("resolution", if (days == 1) "hour" else "day")
        if (since != null) {
            put("sinceTime", since.toString())
            put("untilTime", until.toString())
        }
    }
}

private fun JsonObject.usageCount(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
        ?: throw IOException("The environment returned invalid usage counts.")

private fun JsonObject.usageNumber(key: String, optional: Boolean = false): Double =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        ?: if (optional && !containsKey(key)) 0.0
        else throw IOException("The environment returned invalid usage costs.")

private fun usageInstant(value: String): Instant = runCatching {
    Instant.parse(value)
}
    .getOrDefault(Instant.EPOCH)

internal fun parseNativeUsageReport(value: JsonObject): NativeUsageReport {
    val version =
        (value["contractVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw IOException("The environment returned an invalid usage format.")
    if (version !in 4..NATIVE_USAGE_CONTRACT_VERSION)
        return NativeUsageReport(
            version,
            value.string("readAt"),
            value.string("timeZone"),
            emptyList(),
            emptyList(),
            value.obj("pricing"),
            null,
        )
    val buckets =
        value.array("buckets").mapNotNull { raw ->
            val bucket = raw as? JsonObject ?: return@mapNotNull null
            if (bucket.string("provider") !in usageProviders) return@mapNotNull null
            val tokens = bucket.obj("totals")
            NativeUsageBucket(
                bucket.string("day"),
                bucket.nullableString("hourStart"),
                bucket.string("provider"),
                bucket.string("model"),
                bucket.nullableString("sourcePath"),
                UsageTokens(
                    tokens.usageCount("uncachedInputTokens"),
                    tokens.usageCount("cachedInputTokens"),
                    tokens.usageCount("cacheCreationTokens"),
                    tokens.usageCount("outputTokens"),
                    tokens.usageCount("reasoningTokens"),
                ),
                bucket.usageNumber("costUsd"),
                bucket.usageNumber("cacheSavingsUsd"),
                bucket.string("costSource"),
                bucket.usageCount("records"),
                bucket.usageCount("unpricedRecords"),
                (bucket["categoryCostUsd"] as? JsonObject)
                    ?.let { costs ->
                        listOf("input", "cacheRead", "cacheWrite", "output").associateWith {
                            costs.usageNumber(it)
                        }
                    }
                    .orEmpty(),
                bucket.usageNumber("fastCostUsd", true),
                bucket.usageNumber("ultrafastCostUsd", true),
                bucket.usageNumber("speedPremiumUsd", true),
            )
        }
    val sources =
        value.array("sources").mapNotNull { raw ->
            val source = raw as? JsonObject ?: return@mapNotNull null
            val fingerprint = source.obj("fingerprint")
            if (
                fingerprint.string("provider") !in usageProviders ||
                    source.string("status") !in setOf("ok", "missing", "partial", "failed")
            )
                return@mapNotNull null
            NativeUsageSource(
                UsageFingerprint(
                    fingerprint.string("hostId"),
                    fingerprint.string("provider"),
                    fingerprint.string("resolvedHomePath"),
                    fingerprint.string("volumeId"),
                ),
                source.string("status"),
                source.usageCount("distinctSessions"),
                source.nullableString("message"),
                source.usageCount("malformedRecords"),
                source.nullableString("action"),
                source.bool("refreshing"),
            )
        }
    return NativeUsageReport(
        version,
        value.string("readAt"),
        value.string("timeZone"),
        buckets,
        sources,
        value.obj("pricing"),
        value["modelFilter"] as? JsonObject,
    )
}

private fun sourceBuckets(report: NativeUsageReport, source: NativeUsageSource) =
    report.buckets.filter {
        it.provider == source.fingerprint.provider &&
            (it.sourcePath == source.fingerprint.home ||
                (it.sourcePath == null &&
                    report.sources.count { candidate ->
                        candidate.fingerprint.provider == source.fingerprint.provider
                    } == 1))
    }

internal fun mergeNativeUsage(environments: List<EnvironmentUsageReport>): NativeUsageTotals {
    val compatible = environments.filter { it.report.version in 4..NATIVE_USAGE_CONTRACT_VERSION }
    val warnings =
        environments
            .filter { it.report.version !in 4..NATIVE_USAGE_CONTRACT_VERSION }
            .map {
                "${it.label}: ${if (it.report.version < 4) "update this environment's server" else "update this app"} to include its usage."
            }
            .toMutableList()
    val ordered =
        compatible.sortedWith(
            compareByDescending<EnvironmentUsageReport> { usageInstant(it.report.readAt) }
                .thenBy { it.environmentId }
        )
    val owners = linkedMapOf<UsageFingerprint, Pair<EnvironmentUsageReport, NativeUsageSource>>()
    val sessions = mutableMapOf<UsageFingerprint, Long>()
    val duplicates = linkedSetOf<String>()
    for (status in listOf("ok", "partial", "failed")) for (environment in ordered) for (source in
        environment.report.sources.filter { it.status == status }) {
        if (owners.putIfAbsent(source.fingerprint, environment to source) == null)
            sessions[source.fingerprint] = source.sessions
        else duplicates += "${environment.label}: ${source.fingerprint.home}"
    }
    val supplemental = mutableMapOf<String, MutableSet<NativeUsageBucket>>()
    val seen = mutableMapOf<UsageFingerprint, MutableSet<List<String?>>>()
    fun cell(bucket: NativeUsageBucket) =
        listOf(bucket.day, bucket.hour, bucket.provider, bucket.model)
    for (environment in ordered) for (source in
        environment.report.sources.filter { it.status == "partial" }) {
        val owner = owners[source.fingerprint] ?: continue
        if (
            owner.second.status != "ok" ||
                usageInstant(environment.report.readAt) <=
                    usageInstant(owner.first.report.readAt) ||
                environment.report.modelFilter != owner.first.report.modelFilter
        )
            continue
        val cells =
            seen.getOrPut(source.fingerprint) {
                sourceBuckets(owner.first.report, owner.second).map(::cell).toMutableSet()
            }
        val extra = sourceBuckets(environment.report, source).filter { cells.add(cell(it)) }
        if (extra.isNotEmpty()) {
            supplemental.getOrPut(environment.environmentId, ::mutableSetOf).addAll(extra)
            sessions[source.fingerprint] = maxOf(sessions[source.fingerprint] ?: 0, source.sessions)
        }
    }
    val buckets = mutableListOf<NativeUsageBucket>()
    compatible.forEach { environment ->
        val owned = owners.filterValues { it.first.environmentId == environment.environmentId }.keys
        val providers = owned.map { it.provider }.toSet()
        buckets +=
            environment.report.buckets.filter { bucket ->
                bucket in supplemental[environment.environmentId].orEmpty() ||
                    (if (bucket.sourcePath == null) bucket.provider in providers
                    else
                        owned.any {
                            it.provider == bucket.provider && it.home == bucket.sourcePath
                        })
            }
        environment.report.sources.forEach { source ->
            if (
                source.action == null &&
                    (source.status in setOf("partial", "failed") ||
                        source.fingerprint.provider == "cursor")
            )
                source.message?.let(warnings::add)
        }
    }
    val malformed =
        compatible
            .flatMap { it.report.sources }
            .groupBy { it.fingerprint }
            .values
            .sumOf { group -> group.maxOf { it.malformed } }
    if (malformed > 0)
        warnings +=
            "$malformed malformed or inconsistent usage records were skipped; usage may be incomplete."
    fun sumTokens(values: List<NativeUsageBucket>) =
        UsageTokens(
            values.sumOf { it.tokens.input },
            values.sumOf { it.tokens.cacheRead },
            values.sumOf { it.tokens.cacheWrite },
            values.sumOf { it.tokens.output },
            values.sumOf { it.tokens.reasoning },
        )
    fun breakdown(values: List<NativeUsageBucket>, key: String) =
        UsageBreakdown(
            key,
            values.first().provider,
            values.sumOf { it.cost },
            sumTokens(values),
            values.sumOf { it.records },
            values.sumOf { it.unpricedRecords },
        )
    val cost = buckets.sumOf { it.cost }
    val category =
        listOf("input", "cacheRead", "cacheWrite", "output").associateWith { key ->
            buckets.sumOf { it.categoryCosts[key] ?: 0.0 }
        }
    return NativeUsageTotals(
        cost,
        sumTokens(buckets),
        buckets.sumOf { it.records },
        sessions.values.sum(),
        buckets
            .groupBy { it.provider }
            .map { (key, values) -> breakdown(values, key) }
            .sortedByDescending { it.cost },
        buckets
            .groupBy { it.provider to it.model }
            .map { (key, values) -> breakdown(values, key.second) }
            .sortedWith(
                compareByDescending<UsageBreakdown> { it.cost }.thenByDescending { it.tokens.total }
            ),
        buckets
            .groupBy { it.day }
            .map { (key, values) ->
                UsagePeriod(key, values.sumOf { it.cost }, values.sumOf { it.tokens.total })
            }
            .sortedBy { it.key },
        buckets
            .filter { it.hour != null }
            .groupBy { it.hour!! }
            .map { (key, values) ->
                UsagePeriod(key, values.sumOf { it.cost }, values.sumOf { it.tokens.total })
            }
            .sortedBy { it.key },
        buckets.sumOf { it.cacheSavings },
        category + ("unsplit" to maxOf(0.0, cost - category.values.sum())),
        mapOf(
            "fast" to buckets.sumOf { it.fastCost },
            "ultrafast" to buckets.sumOf { it.ultrafastCost },
            "premium" to buckets.sumOf { it.speedPremium },
            "standard" to maxOf(0.0, cost - buckets.sumOf { it.fastCost + it.ultrafastCost }),
        ),
        buckets.filter { it.costSource == "providerReported" }.sumOf { it.records },
        buckets.sumOf { it.unpricedRecords },
        duplicates.toList(),
        warnings.distinct(),
    )
}

internal fun safeUsageLink(value: String?): String? = value?.takeIf { candidate ->
    runCatching {
            URI(candidate).let {
                it.scheme?.lowercase() in setOf("https", "http") &&
                    !it.host.isNullOrBlank() &&
                    it.rawUserInfo == null
            }
        }
        .getOrDefault(false)
}

internal fun effectiveNativeLimitWindows(
    account: NativeLimitAccount,
    now: Instant = Instant.now(),
): List<NativeLimitWindow> =
    account.windows.map { window ->
        val weekly =
            if (window.kind == "session")
                account.windows.firstOrNull {
                    it.used >= 100 &&
                        (if (account.driver == "claudeAgent") it.id == "seven_day"
                        else account.driver == "codex" && it.kind == "weekly")
                }
            else null
        if (weekly == null) window
        else {
            val sessionReset = window.resetsAt?.let(::usageInstant)
            val weeklyReset = weekly.resetsAt?.let(::usageInstant)
            val cutoff = maxOf(now, usageInstant(account.checkedAt))
            val reset =
                if (
                    sessionReset != null &&
                        weeklyReset != null &&
                        sessionReset > cutoff &&
                        weeklyReset > cutoff
                ) {
                    if (sessionReset > weeklyReset && window.used >= 100) window.resetsAt
                    else weekly.resetsAt
                } else null
            window.copy(used = 100.0, resetsAt = reset, durationMinutes = null)
        }
    }

internal fun nativeLimitPace(window: NativeLimitWindow, now: Instant = Instant.now()): String? {
    val duration = window.durationMinutes?.takeIf { it > 0 }?.toDouble()?.times(60) ?: return null
    val reset =
        window.resetsAt?.let(::usageInstant)?.takeUnless { it == Instant.EPOCH } ?: return null
    val elapsed =
        ((duration - (reset.toEpochMilli() - now.toEpochMilli()) / 1000.0) / duration).coerceIn(
            0.0,
            1.0,
        )
    val gap = window.used - elapsed * 100
    return when {
        gap > 5 -> "Ahead of pace"
        gap < -5 -> "Under pace"
        else -> "On pace"
    }
}

internal fun nativeLimitPools(
    accounts: List<NativeLimitAccount>,
    now: Instant = Instant.now(),
): List<NativeLimitPool> =
    accounts
        .groupBy { it.driver }
        .flatMap { (driver, members) ->
            val pools =
                linkedMapOf<String, MutableList<Pair<NativeLimitAccount, NativeLimitWindow>>>()
            members.forEach { account ->
                effectiveNativeLimitWindows(account, now).forEach { window ->
                    val pooled =
                        if (driver == "codex") window.kind else "${window.kind}:${window.id}"
                    val key =
                        if (pools[pooled]?.any { it.first.key == account.key } == true)
                            "${window.kind}:${window.id}"
                        else pooled
                    pools.getOrPut(key, ::mutableListOf) += account to window
                }
            }
            pools.map { (key, windows) ->
                NativeLimitPool(
                    driver,
                    windows.first().second.label,
                    kotlin.math
                        .round(100 - windows.sumOf { it.second.used } / windows.size)
                        .toInt(),
                    windows.map { it.first.key }.distinct().size,
                    key,
                )
            }
        }

internal fun collectNativeUsageLimits(configs: Map<String, JsonObject>): NativeUsageLimits {
    data class Report(
        val environment: String,
        val raw: JsonObject,
        val identity: JsonObject,
        val source: JsonObject?,
        val provider: Boolean,
    ) {
        val limits
            get() = raw.obj("usageLimits")

        val base: String?
            get() =
                identity
                    .nullableString("email")
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { "${raw.string("driver")}:$it" }
                    ?: limits.nullableString("credentialFingerprint")?.let {
                        "${raw.string("driver")}:credential:$it"
                    }

        val workspace
            get() = identity.nullableString("workspaceId")
    }
    val reports = mutableListOf<Report>()
    val notices = mutableListOf<String>()
    val links = linkedMapOf<String, String>()
    configs.forEach { (environment, config) ->
        config
            .array("providers")
            .mapNotNull { it as? JsonObject }
            .filter { it.bool("enabled") }
            .forEach { provider ->
                reports += Report(environment, provider, provider.obj("auth"), null, true)
            }
        config
            .array("usageLimitSources")
            .mapNotNull { it as? JsonObject }
            .forEach { source ->
                source.nullableString("error")?.let { notices += "${source.string("label")}: $it" }
                if (source.array("accounts").isEmpty() && source.nullableString("error") == null)
                    notices += "${source.string("label")}: No accounts reported."
                source
                    .array("accounts")
                    .mapNotNull { it as? JsonObject }
                    .forEach { account ->
                        reports += Report(environment, account, account, source, false)
                    }
            }
    }
    val workspaces =
        reports
            .filter { it.base != null && it.workspace != null }
            .groupBy { it.base }
            .mapValues { (_, values) -> values.mapNotNull { it.workspace }.toSet() }
    val grouped = linkedMapOf<String, MutableList<NativeLimitAccount>>()
    reports.forEach { report ->
        val raw = report.raw
        if (
            report.provider &&
                (!raw.bool("installed") ||
                    raw.string("availability") == "unavailable" ||
                    raw["usageLimits"] !is JsonObject)
        )
            return@forEach
        val limits = report.limits
        if (report.provider && report.identity.string("status") == "authenticated")
            safeUsageLink(limits.obj("externalUsage").nullableString("url"))?.let {
                links[it] = limits.obj("externalUsage").string("label", "Manage usage")
            }
        val unavailable = limits["unavailable"] as? JsonObject
        val name =
            raw.nullableString("displayName")
                ?: if (report.provider) raw.string("driver")
                else raw.string("id").removeSuffix(".json")
        val windows =
            limits.array("windows").mapNotNull { entry ->
                val window = entry as? JsonObject ?: return@mapNotNull null
                val used =
                    (window["usedPercent"] as? JsonPrimitive)?.doubleOrNull?.takeIf {
                        it.isFinite() && it in 0.0..100.0
                    } ?: return@mapNotNull null
                if (window.string("kind") !in setOf("session", "weekly", "monthly", "other"))
                    return@mapNotNull null
                NativeLimitWindow(
                    window.string("id"),
                    window.string("kind"),
                    window.string("label"),
                    used,
                    window.nullableString("resetsAt"),
                    (window["windowDurationMins"] as? JsonPrimitive)?.longOrNull?.takeIf {
                        it >= 0
                    },
                )
            }
        if (unavailable != null || windows.isEmpty()) {
            if (unavailable?.string("reason") != "unsupported")
                notices +=
                    "$name: ${unavailable?.nullableString("message") ?: if (unavailable != null) "Could not read limits." else "No limits reported."}"
            return@forEach
        }
        val fallback =
            if (report.provider) "${report.environment}:${raw.string("instanceId")}"
            else "${report.source?.string("id")}:${raw.string("id")}:${report.workspace.orEmpty()}"
        val workspace = report.workspace ?: workspaces[report.base]?.singleOrNull()
        val key =
            report.base?.let { base ->
                if (workspace != null) "$base:$workspace"
                else if ((workspaces[base]?.size ?: 0) > 1) null else base
            } ?: fallback
        val credits =
            (limits["resetCredits"] as? JsonObject)?.let {
                NativeResetCredits(
                    it.long("availableCount").coerceAtLeast(0),
                    it.nullableString("nextExpiresAt"),
                    it.nullableString("nextCreditId"),
                )
            }
        val redeem =
            if (report.provider) buildJsonObject { put("instanceId", raw.string("instanceId")) }
            else
                credits?.nextCreditId?.let { credit ->
                    buildJsonObject {
                        put("sourceId", report.source!!.string("id"))
                        put("accountId", raw.string("id"))
                        put("creditId", credit)
                    }
                }
        grouped.getOrPut(key, ::mutableListOf) +=
            NativeLimitAccount(
                key,
                raw.string("driver"),
                name,
                report.identity.nullableString("email"),
                if (report.provider) report.identity.nullableString("label")
                else raw.nullableString("plan"),
                if (report.provider) setOf(report.environment) else emptySet(),
                report.source?.string("label"),
                limits.string("checkedAt"),
                windows,
                credits,
                if (redeem != null) report.environment else null,
                redeem,
                safeUsageLink(limits.obj("externalUsage").nullableString("url")),
            )
    }
    val accounts = grouped.map { (_, values) ->
        val ordered = values.sortedByDescending { usageInstant(it.checkedAt) }
        val newest = ordered.first()
        val credits = ordered.firstOrNull { it.credits != null }
        val redeem =
            ordered.firstOrNull { it.redeemInput?.containsKey("sourceId") == true }
                ?: credits?.takeIf { it.redeemInput != null }
                ?: ordered.firstOrNull { it.redeemInput != null }
        newest.copy(
            name = values.firstOrNull { it.name != it.driver }?.name ?: newest.name,
            email = values.firstNotNullOfOrNull { it.email },
            plan = ordered.firstNotNullOfOrNull { it.plan },
            environments = values.flatMap { it.environments }.toSet(),
            credits = credits?.credits,
            redeemEnvironmentId = redeem?.redeemEnvironmentId,
            redeemInput = redeem?.redeemInput,
        )
    }
    return NativeUsageLimits(accounts, notices.distinct(), links)
}
