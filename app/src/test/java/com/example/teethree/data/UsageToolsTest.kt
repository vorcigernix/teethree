package com.example.teethree.data

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*
import kotlinx.serialization.json.*

class UsageToolsTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    private fun report(
        cost: Double,
        readAt: String,
        status: String = "ok",
        day: String = "2026-11-01",
        model: String = "model",
        sessions: Int = 2,
        filter: JsonObject? = null,
    ): NativeUsageReport =
        parseNativeUsageReport(
            json(
                """{
      "contractVersion":6,"readAt":"$readAt","timeZone":"UTC","pricing":{"status":"cached","knownModels":1},
      "buckets":[{"day":"$day","provider":"codex","model":"$model","sourcePath":"/transcripts","totals":{"uncachedInputTokens":10,"cachedInputTokens":20,"cacheCreationTokens":30,"outputTokens":40,"reasoningTokens":35},"costUsd":$cost,"cacheSavingsUsd":0.2,"costSource":"modelPriced","records":2,"unpricedRecords":0,"sessions":99,"categoryCostUsd":{"input":0.1,"cacheRead":0.2,"cacheWrite":0.3,"output":0.4}}],
      "sources":[{"fingerprint":{"hostId":"machine","provider":"codex","resolvedHomePath":"/transcripts","volumeId":"device:inode"},"status":"$status","distinctSessions":$sessions,"malformedRecords":0}],"modelFilter":${filter ?: JsonNull}}
    """
            )
        )

    @Test
    fun `usage totals count distinct source sessions and never add reasoning twice or duplicate shared directories`() {
        val older = report(1.0, "2026-11-01T12:00:00Z")
        val newer = report(2.0, "2026-11-01T13:00:00Z")
        val total =
            mergeNativeUsage(
                listOf(
                    EnvironmentUsageReport("old", "Older", older),
                    EnvironmentUsageReport("new", "Newer", newer),
                )
            )
        assertEquals(2.0, total.cost)
        assertEquals(100L, total.tokens.total)
        assertEquals(35L, total.tokens.reasoning)
        assertEquals(2L, total.sessions)
        assertEquals(2L, total.records)
        assertEquals(listOf("Older: /transcripts"), total.duplicateSources)
        val differentVolume =
            older.copy(
                sources =
                    older.sources.map {
                        it.copy(fingerprint = it.fingerprint.copy(volume = "different"))
                    }
            )
        assertEquals(
            3.0,
            mergeNativeUsage(
                    listOf(
                        EnvironmentUsageReport("a", "A", differentVolume),
                        EnvironmentUsageReport("b", "B", newer),
                    )
                )
                .cost,
        )
    }

    @Test
    fun `newer partial scans supplement missing cells but never overlap complete scans or different model filters`() {
        val complete = report(1.0, "2026-11-01T12:00:00Z")
        val partial =
            report(3.0, "2026-11-01T13:00:00Z", "partial", model = "new-model", sessions = 3)
        fun merge(value: NativeUsageReport) =
            mergeNativeUsage(
                listOf(
                    EnvironmentUsageReport("complete", "Complete", complete),
                    EnvironmentUsageReport("partial", "Partial", value),
                )
            )
        assertEquals(4.0, merge(partial).cost)
        assertEquals(3L, merge(partial).sessions)
        assertEquals(
            1.0,
            merge(partial.copy(buckets = partial.buckets.map { it.copy(model = "model") })).cost,
        )
        assertEquals(
            1.0,
            merge(partial.copy(modelFilter = json("""{"exclude":[{"model":"old"}]}"""))).cost,
        )
    }

    @Test
    fun `usage windows use calendar days across daylight saving changes and exactly twenty four elapsed hours`() {
        val now = Instant.parse("2026-10-26T00:30:59Z")
        val zone = ZoneId.of("Europe/Prague")
        val week = nativeUsageWindow(7, now, zone)
        assertEquals("2026-10-20", week.string("sinceDay"))
        assertEquals("2026-10-26", week.string("untilDay"))
        val day = nativeUsageWindow(1, now, zone)
        assertEquals("2026-10-25T00:30:00Z", day.string("sinceTime"))
        assertEquals("2026-10-26T00:30:00Z", day.string("untilTime"))
        assertEquals("hour", day.string("resolution"))
        assertEquals("Europe/Prague", day.string("timeZone"))
        assertFailsWith<IllegalArgumentException> { nativeUsageWindow(365, now, zone) }
    }

    private fun provider(
        instance: String,
        workspace: String?,
        checkedAt: String,
        used: Int,
        credit: Int? = null,
    ) =
        json(
            """{
      "instanceId":"$instance","driver":"codex","displayName":"Codex","enabled":true,"installed":true,
      "auth":{"status":"authenticated","email":"A@Example.com"${workspace?.let { ",\"workspaceId\":\"$it\"" }.orEmpty()}},
      "usageLimits":{"checkedAt":"$checkedAt","windows":[{"id":"one","kind":"session","label":"Session","usedPercent":$used}],"externalUsage":{"label":"Manage usage","url":"https://chatgpt.com/codex/settings/usage"}${credit?.let { ",\"resetCredits\":{\"availableCount\":$it}" }.orEmpty()}}}
    """
        )

    @Test
    fun `limits merge identified logins without merging ambiguous workspaces and preserve pinned hub credit routing`() {
        val native = provider("native", "team", "2026-11-01T13:00:00Z", 20, 3)
        val hub =
            json(
                """{"id":"hub","label":"CLI Proxy","kind":"cliproxy","accounts":[{"id":"account.json","driver":"codex","email":"a@example.com","workspaceId":"team","usageLimits":{"checkedAt":"2026-11-01T12:00:00Z","windows":[{"id":"hub","kind":"session","label":"Session","usedPercent":80}],"resetCredits":{"availableCount":2,"nextCreditId":"pinned-credit"}}}]}"""
            )
        val config = buildJsonObject {
            put("providers", JsonArray(listOf(native)))
            put("usageLimitSources", JsonArray(listOf(hub)))
        }
        val limits = collectNativeUsageLimits(mapOf("saved-environment" to config))
        val account = limits.accounts.single()
        assertEquals(20.0, account.windows.single().used)
        assertEquals(3L, account.credits!!.available)
        assertEquals("pinned-credit", account.redeemInput!!.string("creditId"))
        assertEquals("hub", account.redeemInput.string("sourceId"))
        assertEquals("saved-environment", account.redeemEnvironmentId)
        assertEquals(1, limits.externalLinks.size)
        val unknown = provider("unknown", null, "2026-11-01T13:00:00Z", 10)
        val another = provider("another", "other-team", "2026-11-01T13:00:00Z", 10)
        val ambiguous =
            collectNativeUsageLimits(
                mapOf(
                    "saved" to
                        buildJsonObject {
                            put("providers", JsonArray(listOf(native, unknown, another)))
                        }
                )
            )
        assertEquals(3, ambiguous.accounts.size)
    }

    @Test
    fun `spent weekly quota blocks session bars and unknown pricing or contracts never look like free usage`() {
        val account =
            collectNativeUsageLimits(
                    mapOf(
                        "saved" to
                            buildJsonObject {
                                put(
                                    "providers",
                                    JsonArray(
                                        listOf(provider("native", null, "2026-11-01T12:00:00Z", 10))
                                    ),
                                )
                            }
                    )
                )
                .accounts
                .single()
        val weekly =
            NativeLimitWindow("week", "weekly", "Weekly", 100.0, "2026-11-04T12:00:00Z", 10080)
        val blocked =
            account.copy(
                windows =
                    listOf(
                        account.windows
                            .single()
                            .copy(resetsAt = "2026-11-01T18:00:00Z", durationMinutes = 300),
                        weekly,
                    )
            )
        val effective =
            effectiveNativeLimitWindows(blocked, Instant.parse("2026-11-01T13:00:00Z")).first()
        assertEquals(0, effective.remaining)
        assertEquals(weekly.resetsAt, effective.resetsAt)
        assertNull(effective.durationMinutes)
        assertEquals(
            0,
            nativeLimitPools(listOf(blocked), Instant.parse("2026-11-01T13:00:00Z"))
                .first()
                .remaining,
        )
        val raw = report(0.0, "2026-11-01T12:00:00Z")
        val unpriced = raw.copy(buckets = raw.buckets.map { it.copy(unpricedRecords = it.records) })
        assertTrue(
            mergeNativeUsage(listOf(EnvironmentUsageReport("saved", "Saved", unpriced)))
                .models
                .single()
                .costUnknown
        )
        val incompatible =
            mergeNativeUsage(
                listOf(EnvironmentUsageReport("future", "Future", raw.copy(version = 99)))
            )
        assertEquals(0.0, incompatible.cost)
        assertEquals(1, incompatible.warnings.size)
        assertNull(safeUsageLink("javascript:alert(1)"))
        assertNull(safeUsageLink("https://user:secret@example.com/"))
        assertNull(safeUsageLink("file:///tmp/usage"))
        assertEquals("https://example.com/usage", safeUsageLink("https://example.com/usage"))
        assertFailsWith<IOException> {
            parseNativeUsageReport(
                json("""{"contractVersion":6,"buckets":[{"provider":"codex","totals":{}}]}""")
            )
        }
    }
}
