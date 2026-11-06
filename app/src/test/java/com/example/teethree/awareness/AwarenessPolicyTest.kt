package com.example.teethree.awareness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AwarenessPolicyTest {
    @Test
    fun pushesRequireCurrentIdentityFreshnessAndBoundedReplayHistory() {
        val push =
            mapOf(
                "t3_kind" to "agent_activity",
                "device_id" to "device",
                "user_id" to "user",
                "updated_at" to "1000000",
            )
        assertTrue(acceptAgentPush(push, "device", "user", 1_000_000L))
        assertFalse(acceptAgentPush(push, "device", "other", 1_000_000L))
        assertFalse(acceptAgentPush(push, null, null, 1_000_000L))
        assertFalse(acceptAgentPush(push, "device", "user", 1_600_001L))
        assertFalse(acceptAgentPush(push, "device", "user", 399_999L))
        assertFalse(acceptAgentPush(push + ("t3_kind" to "unknown"), "device", "user", 1_000_000L))
        var history = emptyList<String>()
        repeat(65) { history = alertHistory(history, "alert-$it") }
        assertEquals(64, history.size)
        assertFalse("alert-0" in history)
        assertEquals("alert-1", history.first())
        assertEquals(64, alertHistory(history, "alert-1").size)
    }

    @Test
    fun notificationRoutesStayInsideTheAppAndPreserveEncodedIdentifiers() {
        assertEquals(
            AwarenessRoute.Thread("environment", "thread+one"),
            parseAwarenessRoute("teethree://threads/environment/thread%2Bone"),
        )
        assertEquals(
            AwarenessRoute.Thread("env", "thread"),
            parseAwarenessRoute("teethree:/threads/env/thread"),
        )
        assertEquals(AwarenessRoute.Usage, parseAwarenessRoute("teethree://settings/usage"))
        for (value in
            listOf(
                "https://threads/environment/thread",
                "teethree://threads/environment/thread?redirect=x",
                "teethree://threads/environment/thread#fragment",
                "teethree://threads//thread",
                "teethree://threads/environment/%ZZ",
                "teethree://threads/environment/%00",
                "teethree://threads/environment/thread/extra",
            )) assertNull(value, parseAwarenessRoute(value))
        assertEquals("/threads/env/thread%2Bone", normalizedThreadPath("/threads/env/thread+one"))
    }

    @Test
    fun androidRegistrationDisablesUnconfiguredOrRevokedDeliveryAndUsesExistingContract() {
        fun registration(token: String?, allowed: Boolean) =
            relayDeviceRegistration(
                "device",
                "Android",
                37,
                "1.0",
                "com.example.teethree",
                token,
                AwarenessPreferences(),
                allowed,
            )
        val ready = registration("token", true)
        assertEquals("android", ready["platform"].toString().trim('"'))
        assertEquals("true", ready["preferences"]!!.jsonObject["notificationsEnabled"].toString())
        assertEquals(
            "false",
            registration(null, true)["preferences"]!!
                .jsonObject["liveActivitiesEnabled"]
                .toString(),
        )
        assertEquals(
            "false",
            registration("token", false)["preferences"]!!
                .jsonObject["notificationsEnabled"]
                .toString(),
        )
        assertFalse(ready.containsKey("apsEnvironment"))
        assertFalse(ready.containsKey("pushToStartToken"))
        val presentation =
            ActivityPresentation(
                mapOf(
                    "activity_line_0" to "Approval\tReview change\tProject",
                    "activity_line_1" to "Working\tOther task\tProject",
                    "activity_active_count" to "2",
                    "activity_attention_count" to "1",
                    "activity_phase" to "waiting_for_approval",
                ),
                true,
            )
        assertEquals("1 needs you", presentation.summary)
        assertEquals("Approve", presentation.action)
        assertEquals("Approve", presentation.chip)
    }

    @Test
    fun widgetPoolsDistinctAccountsAndUsesFreshestDuplicateAcrossEnvironments() {
        val configs =
            mapOf(
                "one" to config("a@example.com", 20, "2026-11-05T08:00:00Z"),
                "two" to config("A@example.com", 60, "2026-11-05T08:01:00Z"),
                "three" to config("b@example.com", 20, "2026-11-05T08:00:00Z"),
            )
        val snapshot = buildSubscriptionUsageSnapshot(configs, instant("2026-11-05T08:02:00Z"))
        val provider = snapshot.providers.single()
        assertEquals("2 accounts · pooled", provider.detail)
        assertEquals(60, provider.windows.single().remaining)
        assertEquals(instant("2026-11-05T08:15:00Z"), provider.expiresAt)
        assertEquals(instant("2026-11-05T08:00:00Z"), snapshot.checkedAt)
        val expired =
            buildSubscriptionUsageSnapshot(configs, instant("2026-11-05T08:15:00Z"))
                .providers
                .single()
        assertTrue(expired.windows.isEmpty())
        assertTrue(expired.detail.contains("refresh"))
    }

    @Test
    fun widgetTreatsExhaustedWeeklyQuotaAsBlockedSessionAndSupportsHubSources() {
        val config =
            Json.parseToJsonElement(
                    """{"providers":[],"usageLimitSources":[{"id":"hub","accounts":[{"id":"account","driver":"claudeAgent","email":"a@example.com","usageLimits":{"checkedAt":"2026-11-05T08:00:00Z","windows":[{"id":"five_hour","kind":"session","label":"Session","usedPercent":20,"resetsAt":"2026-11-05T10:00:00Z"},{"id":"seven_day","kind":"weekly","label":"Weekly","usedPercent":100,"resetsAt":"2026-11-06T08:00:00Z"}]}}]}]}"""
                )
                .jsonObject
        val provider =
            buildSubscriptionUsageSnapshot(mapOf("env" to config), instant("2026-11-05T08:01:00Z"))
                .providers
                .single()
        assertEquals("Claude", provider.name)
        assertEquals(0, provider.windows.first { it.kind == "session" }.remaining)
        assertEquals(0, provider.windows.first { it.kind == "weekly" }.remaining)
        assertEquals(2, provider.totalWindows)
    }

    private fun config(email: String, used: Int, checked: String) =
        Json.parseToJsonElement(
                """{"providers":[{"instanceId":"codex","driver":"codex","enabled":true,"installed":true,"auth":{"email":"$email"},"usageLimits":{"checkedAt":"$checked","windows":[{"id":"primary","kind":"session","label":"Session","usedPercent":$used,"resetsAt":"2026-11-05T10:00:00Z"}]}}]}"""
            )
            .jsonObject

    private fun instant(value: String) = parseUsageInstant(value)!!
}
