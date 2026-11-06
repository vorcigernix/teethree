package com.example.teethree.awareness

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AwarenessPreferences(
    val notificationsEnabled: Boolean = true,
    val ongoingEnabled: Boolean = true,
)

sealed interface AwarenessRoute {
    data class Thread(val environmentId: String, val threadId: String) : AwarenessRoute

    data object Usage : AwarenessRoute
}

fun parseAwarenessRoute(value: String): AwarenessRoute? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (
        uri.scheme !in setOf("teethree", "t3code-native") ||
            uri.rawQuery != null ||
            uri.rawFragment != null ||
            uri.rawUserInfo != null ||
            uri.port != -1
    )
        return null
    val path = if (uri.host != null) "/${uri.host}${uri.rawPath.orEmpty()}" else uri.rawPath
    if (path == "/settings/usage") return AwarenessRoute.Usage
    return parseThreadPath(path.orEmpty())
}

internal fun parseThreadPath(value: String): AwarenessRoute.Thread? {
    if (value.trim() != value || '?' in value || '#' in value) return null
    val pieces = value.split('/')
    if (pieces.size != 4 || pieces[0].isNotEmpty() || pieces[1] != "threads") return null
    val decoded =
        pieces.drop(2).map { raw ->
            runCatching { URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name()) }
                .getOrNull() ?: return null
        }
    if (decoded.any { it.isBlank() || it.any { char -> char.isISOControl() } }) return null
    return AwarenessRoute.Thread(decoded[0], decoded[1])
}

internal fun normalizedThreadPath(value: String?): String? {
    val route = value?.let(::parseThreadPath) ?: return null
    fun encode(part: String) = URLEncoder.encode(part, Charsets.UTF_8.name()).replace("+", "%20")
    return "/threads/${encode(route.environmentId)}/${encode(route.threadId)}"
}

internal fun acceptAgentPush(
    data: Map<String, String>,
    deviceId: String?,
    userId: String?,
    now: Long,
): Boolean {
    val updated = data["updated_at"]?.toLongOrNull() ?: return false
    return data["t3_kind"] == "agent_activity" &&
        !deviceId.isNullOrBlank() &&
        !userId.isNullOrBlank() &&
        data["device_id"] == deviceId &&
        data["user_id"] == userId &&
        updated in (now - 600_000L)..(now + 600_000L)
}

internal fun alertHistory(seen: List<String>, next: String) =
    (seen.filterNot { it == next }.takeLast(63) + next)

fun relayDeviceRegistration(
    deviceId: String,
    label: String,
    apiLevel: Int,
    appVersion: String,
    bundleId: String,
    pushToken: String?,
    preferences: AwarenessPreferences,
    permissionGranted: Boolean,
): JsonObject = buildJsonObject {
    put("deviceId", deviceId)
    put("label", label)
    put("platform", "android")
    put("androidApiLevel", apiLevel)
    put("appVersion", appVersion)
    put("bundleId", bundleId)
    if (!pushToken.isNullOrBlank()) put("pushToken", pushToken)
    put(
        "preferences",
        buildJsonObject {
            put(
                "notificationsEnabled",
                preferences.notificationsEnabled && permissionGranted && !pushToken.isNullOrBlank(),
            )
            put(
                "liveActivitiesEnabled",
                preferences.ongoingEnabled && permissionGranted && !pushToken.isNullOrBlank(),
            )
            put("notifyOnApproval", true)
            put("notifyOnInput", true)
            put("notifyOnCompletion", true)
            put("notifyOnFailure", true)
        },
    )
}
