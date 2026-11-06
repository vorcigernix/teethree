package com.example.teethree.data.auth

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CloudConfig(val publishableKey: String, val jwtTemplate: String, val relayUrl: String) {
    companion object {
        fun parse(publishableKey: String, jwtTemplate: String, relayUrl: String): CloudConfig? {
            if (listOf(publishableKey, jwtTemplate, relayUrl).any(String::isBlank)) return null
            val key = publishableKey.trim()
            if (!key.startsWith("pk_live_") && !key.startsWith("pk_test_")) invalidCloudResponse()
            val frontend =
                runCatching {
                    String(Base64.getDecoder().decode(key.substringAfter('_').substringAfter('_')))
                        .removeSuffix("$")
                }
                    .getOrNull() ?: invalidCloudResponse()
            val frontendUrl = "https://$frontend".toHttpUrlOrNull()
            if (
                frontend.isBlank() ||
                    frontend.contains('/') ||
                    frontendUrl == null ||
                    frontendUrl.host != frontend.lowercase() ||
                    frontendUrl.port != 443 ||
                    frontendUrl.username.isNotEmpty() ||
                    frontendUrl.password.isNotEmpty() ||
                    frontendUrl.query != null ||
                    frontendUrl.fragment != null
            )
                throw IOException("The T3 Connect sign-in configuration is invalid.")
            val relay = secureEndpoint(relayUrl)
            if (relay.encodedPath.any { it != '/' })
                throw IOException("The T3 Connect relay configuration is invalid.")
            return CloudConfig(
                key,
                jwtTemplate.trim(),
                relay.newBuilder().encodedPath("/").build().toString().trimEnd('/'),
            )
        }
    }
}

data class CloudIdentity(val accountId: String, val sessionId: String)

data class CloudAccountState(
    val configured: Boolean = false,
    val initialized: Boolean = false,
    val identity: CloudIdentity? = null,
    val pending: Boolean = false,
    val error: String? = null,
)

interface CloudSessionSource {
    val state: StateFlow<CloudAccountState>

    suspend fun token(): String

    suspend fun signOut()
}

data class CloudEndpoint(val httpBaseUrl: String, val wsBaseUrl: String, val providerKind: String)

data class CloudEnvironment(
    val environmentId: String,
    val label: String,
    val endpoint: CloudEndpoint,
)

data class CloudEnvironmentStatus(
    val environmentId: String,
    val online: Boolean,
    val reason: String? = null,
)

@Serializable
data class RelayAccessToken(
    val accountId: String,
    val sessionId: String,
    val relayUrl: String,
    val thumbprint: String,
    val scopes: Set<String>,
    val accessToken: String,
    val expiresAt: Long,
) {
    override fun toString() = "RelayAccessToken(accountId=$accountId, expiresAt=$expiresAt)"
}

class CloudHttpException(
    val status: Int,
    val reason: String? = null,
    val dpopFailureReason: String? = null,
) :
    IOException(
        when {
            dpopFailureReason == "time_window" ->
                "This device's clock is out of sync. Enable automatic date and time, then retry."
            reason == "tunnel_released" ->
                "This computer's T3 Connect tunnel was released. Start T3 Code on that computer to reconnect."
            reason == "origin_unreachable" ->
                "The tunnel is online, but T3 Code on the computer is not responding."
            status == 401 -> "T3 Connect sign-in expired. Sign in again."
            status == 403 -> "Your T3 Connect account cannot access this environment."
            status == 404 -> "This T3 Connect environment is no longer available."
            status == 409 -> "T3 Connect could not complete this operation. Refresh and try again."
            status == 429 -> "T3 Connect received too many requests. Try again shortly."
            status in 500..599 -> "T3 Connect is temporarily unavailable. Try again shortly."
            else -> "T3 Connect returned HTTP $status."
        }
    )

internal fun invalidCloudResponse(): Nothing =
    throw IOException("T3 Connect returned an invalid response.")

internal fun JsonObject.requiredString(key: String): String =
    ((get(key) as? JsonPrimitive)?.contentOrNull)?.takeIf(String::isNotBlank)
        ?: invalidCloudResponse()

internal fun JsonObject.optionalString(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

internal fun JsonObject.positiveLong(key: String): Long =
    (get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: invalidCloudResponse()

internal fun JsonElement.requiredObject(): JsonObject =
    this as? JsonObject ?: invalidCloudResponse()

internal fun secureEndpoint(value: String, socket: Boolean = false): HttpUrl {
    val raw =
        if (socket && value.startsWith("wss://")) value.replaceFirst("wss://", "https://")
        else value
    val url = raw.toHttpUrlOrNull() ?: throw IOException("The T3 Connect address is invalid.")
    if (
        url.scheme != "https" ||
            url.username.isNotEmpty() ||
            url.password.isNotEmpty() ||
            url.query != null ||
            url.fragment != null ||
            (socket && !value.startsWith("wss://"))
    )
        throw IOException("T3 Connect requires a secure address without embedded credentials.")
    return url
}

internal fun endpointUrl(base: String, path: String): HttpUrl {
    val url = base.toHttpUrlOrNull() ?: throw IOException("The environment address is invalid.")
    require(
        url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.query == null &&
            url.fragment == null
    ) {
        "The environment address is invalid."
    }
    require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\')) {
        "The environment request path is invalid."
    }
    val parsed =
        ("https://relative.invalid$path").toHttpUrlOrNull()
            ?: throw IOException("The environment request path is invalid.")
    require(parsed.host == "relative.invalid" && parsed.fragment == null) {
        "Invalid environment path."
    }
    val prefix = url.encodedPath.trimEnd('/')
    return url.newBuilder()
        .encodedPath(prefix + parsed.encodedPath)
        .encodedQuery(parsed.encodedQuery)
        .build()
}

internal fun sameEndpoint(base: String, target: HttpUrl): Boolean {
    val url = base.toHttpUrlOrNull() ?: return false
    val prefix = url.encodedPath.trimEnd('/')
    return target.scheme == url.scheme &&
        target.host == url.host &&
        target.port == url.port &&
        target.username.isEmpty() &&
        target.password.isEmpty() &&
        (prefix.isEmpty() ||
            target.encodedPath == prefix ||
            target.encodedPath.startsWith("$prefix/"))
}
