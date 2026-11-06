package com.example.teethree.data.auth

import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal data class CloudBootstrap(
    val environmentId: String,
    val endpoint: CloudEndpoint,
    val credential: String,
    val expiresAt: Long,
) {
    override fun toString() = "CloudBootstrap(environmentId=$environmentId)"
}

class T3ConnectClient(
    val config: CloudConfig,
    val account: CloudSessionSource,
    internal val signer: DpopProofSigner,
    scope: CoroutineScope,
    client: OkHttpClient = OkHttpClient(),
    private val loadTokens: suspend () -> List<RelayAccessToken> = { emptyList() },
    private val saveTokens: suspend (List<RelayAccessToken>) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal val http =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    private val tokenLock = Mutex()
    private var loaded = false
    private var tokens = emptyList<RelayAccessToken>()
    private val generation = AtomicLong()
    private val identityLock = Any()
    private var boundIdentity = account.state.value.identity

    init {
        secureEndpoint(config.relayUrl)
        scope.launch {
            var previous = account.state.value.identity
            account.state
                .map { it.identity }
                .distinctUntilChanged()
                .collect { identity ->
                    if (identity != previous) {
                        val removed = previous
                        previous = identity
                        observeIdentity(identity)
                        if (removed != null)
                            tokenLock.withLock {
                                tokens = tokens.filterNot {
                                    it.accountId == removed.accountId &&
                                        it.sessionId == removed.sessionId
                                }
                                saveTokens(tokens)
                            }
                    }
                }
        }
    }

    suspend fun clear() {
        generation.incrementAndGet()
        tokenLock.withLock {
            loaded = true
            tokens = emptyList()
            saveTokens(emptyList())
        }
    }

    suspend fun environments(): List<CloudEnvironment> {
        val response = request("GET", "/v1/environments").requiredObject()
        val rows = response["environments"] as? JsonArray ?: invalidCloudResponse()
        if (rows.size > 10_000) invalidCloudResponse()
        return rows
            .map { raw ->
                val row = raw.requiredObject()
                CloudEnvironment(
                    row.requiredString("environmentId"),
                    row.requiredString("label"),
                    parseCloudEndpoint(row["endpoint"]),
                )
            }
            .distinctBy { it.environmentId }
    }

    suspend fun status(environment: CloudEnvironment): CloudEnvironmentStatus {
        val path = "/v1/environments/${pathSegment(environment.environmentId)}/status"
        val response = request("POST", path).requiredObject()
        if (
            response.requiredString("environmentId") != environment.environmentId ||
                parseCloudEndpoint(response["endpoint"]) != environment.endpoint
        )
            invalidCloudResponse()
        val status = response.requiredString("status")
        if (status !in setOf("online", "offline")) invalidCloudResponse()
        return CloudEnvironmentStatus(
            environment.environmentId,
            status == "online",
            response.optionalString("offlineReason"),
        )
    }

    suspend fun unlink(environmentId: String) {
        request("DELETE", "/v1/client/environment-links/${pathSegment(environmentId)}")
    }

    suspend fun releaseTunnel(environmentId: String) {
        request("DELETE", "/v1/client/environment-links/${pathSegment(environmentId)}/tunnel")
    }

    internal suspend fun bootstrap(environmentId: String): CloudBootstrap {
        val response =
            request(
                    "POST",
                    "/v1/environments/${pathSegment(environmentId)}/connect",
                    buildJsonObject {
                        put("clientKeyThumbprint", signer.thumbprint)
                    },
                )
                .requiredObject()
        if (response.requiredString("environmentId") != environmentId) invalidCloudResponse()
        val expiry =
            runCatching { Instant.parse(response.requiredString("expiresAt")).toEpochMilli() }
                .getOrNull() ?: invalidCloudResponse()
        if (expiry <= now())
            throw IOException(
                "The environment credential expired. Check this device's clock and retry."
            )
        return CloudBootstrap(
            environmentId,
            parseCloudEndpoint(response["endpoint"]),
            response.requiredString("credential"),
            expiry,
        )
    }

    suspend fun request(method: String, path: String, payload: JsonObject? = null): JsonElement {
        val scopes = requiredRelayScopes(method, path)
        val identity = identity()
        val version = generation.get()
        val url = endpointUrl(config.relayUrl, path)
        val body =
            if (method in setOf("GET", "HEAD")) null
            else (payload?.toString().orEmpty()).toRequestBody("application/json".toMediaType())
        if (scopes.isEmpty()) {
            val token = account.token()
            assertIdentity(identity, version)
            assertTokenAccount(token, identity.accountId)
            val response =
                http.cloudJson(
                    Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .method(method, body)
                        .build()
                )
            assertIdentity(identity, version)
            return response
        }
        var retried = false
        while (true) {
            val token = relayToken(identity, version, scopes)
            val proof = signer.proof(method, url.toString(), token.accessToken)
            assertIdentity(identity, version)
            try {
                val response =
                    http.cloudJson(
                        Request.Builder()
                            .url(url)
                            .header("Authorization", "DPoP ${token.accessToken}")
                            .header("DPoP", proof)
                            .method(method, body)
                            .build()
                    )
                assertIdentity(identity, version)
                return response
            } catch (error: CloudHttpException) {
                if (error.status != 401 || error.reason != "invalid_bearer" || retried) throw error
                assertIdentity(identity, version)
                tokenLock.withLock {
                    tokens = tokens.filterNot { it.accessToken == token.accessToken }
                    saveTokens(tokens)
                }
                retried = true
            }
        }
    }

    private suspend fun relayToken(
        identity: CloudIdentity,
        version: Long,
        scopes: Set<String>,
    ): RelayAccessToken = tokenLock.withLock {
        assertIdentity(identity, version)
        if (!loaded) {
            tokens = loadTokens().takeLast(16)
            loaded = true
        }
        tokens
            .firstOrNull {
                it.accountId == identity.accountId &&
                    it.sessionId == identity.sessionId &&
                    it.relayUrl == config.relayUrl &&
                    it.thumbprint == signer.thumbprint &&
                    it.expiresAt > now() + 5_000 &&
                    it.scopes.containsAll(scopes)
            }
            ?.let {
                return@withLock it
            }
        val clerkToken = account.token()
        assertIdentity(identity, version)
        assertTokenAccount(clerkToken, identity.accountId)
        val url = endpointUrl(config.relayUrl, "/v1/client/dpop-token")
        val body =
            FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                .add("subject_token", clerkToken)
                .add("subject_token_type", "urn:ietf:params:oauth:token-type:jwt")
                .add("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
                .add("resource", config.relayUrl)
                .add("scope", scopes.sorted().joinToString(" "))
                .add("client_id", "t3-mobile")
                .build()
        val response =
            http
                .cloudJson(
                    Request.Builder()
                        .url(url)
                        .post(body)
                        .header("DPoP", signer.proof("POST", url.toString()))
                        .build()
                )
                .requiredObject()
        assertIdentity(identity, version)
        if (
            response.requiredString("token_type") != "DPoP" ||
                response.requiredString("issued_token_type") !=
                    "urn:ietf:params:oauth:token-type:access_token"
        )
            invalidCloudResponse()
        val granted = response.requiredString("scope").split(Regex("\\s+")).toSet()
        if (!granted.containsAll(scopes))
            throw IOException("T3 Connect did not grant the required permissions.")
        val token =
            RelayAccessToken(
                identity.accountId,
                identity.sessionId,
                config.relayUrl,
                signer.thumbprint,
                granted,
                response.requiredString("access_token"),
                now() + response.positiveLong("expires_in").coerceAtMost(86_400) * 1_000,
            )
        tokens =
            (tokens.filter {
                    it.expiresAt > now() + 5_000 &&
                        it.accountId == identity.accountId &&
                        it.sessionId == identity.sessionId
                } + token)
                .takeLast(16)
        saveTokens(tokens)
        assertIdentity(identity, version)
        token
    }

    private fun observeIdentity(identity: CloudIdentity?) =
        synchronized(identityLock) {
            if (identity != boundIdentity) {
                boundIdentity = identity
                generation.incrementAndGet()
            }
        }

    internal fun identity(): CloudIdentity {
        val identity = account.state.value.identity
        observeIdentity(identity)
        return identity ?: throw IOException("Sign in to T3 Connect to continue.")
    }

    internal suspend fun awaitIdentity(): CloudIdentity {
        val state = account.state.first { it.initialized || it.error != null }
        if (state.error != null) throw IOException(state.error)
        return identity()
    }

    internal fun assertIdentity(identity: CloudIdentity, version: Long? = null) {
        if (
            account.state.value.identity != identity ||
                (version != null && generation.get() != version)
        )
            throw IOException("The T3 Connect account changed. Try again.")
    }
}

internal fun parseCloudEndpoint(raw: JsonElement?): CloudEndpoint {
    val endpoint = raw?.requiredObject() ?: invalidCloudResponse()
    val http = secureEndpoint(endpoint.requiredString("httpBaseUrl")).toString().trimEnd('/')
    val ws = endpoint.requiredString("wsBaseUrl")
    secureEndpoint(ws, socket = true)
    val kind = endpoint.requiredString("providerKind")
    if (kind !in setOf("cloudflare_tunnel", "manual", "t3_relay")) invalidCloudResponse()
    return CloudEndpoint(http, ws, kind)
}

internal fun pathSegment(value: String): String {
    require(
        value.isNotBlank() &&
            value.length <= 512 &&
            value !in setOf(".", "..") &&
            !value.contains('/') &&
            !value.contains('\\')
    ) {
        "The environment identifier is invalid."
    }
    return okhttp3.HttpUrl.Builder()
        .scheme("https")
        .host("segment.invalid")
        .addPathSegment(value)
        .build()
        .encodedPath
        .removePrefix("/")
}

internal fun requiredRelayScopes(method: String, path: String): Set<String> {
    if (method == "POST" && Regex("/v1/environments/[^/?#]+/connect").matches(path))
        return setOf("environment:connect")
    if (method == "POST" && Regex("/v1/environments/[^/?#]+/status").matches(path))
        return setOf("environment:status")
    if (
        (method == "POST" && path == "/v1/mobile/devices") ||
            (method == "GET" && path == "/v1/mobile/agent-activity") ||
            (method == "DELETE" && Regex("/v1/mobile/devices/[^/?#]+").matches(path))
    )
        return setOf("mobile:registration")
    val bearer =
        when (method) {
            "GET" ->
                path in setOf("/v1/environments", "/v2/client/devices", "/v1/client/t3chat-link")
            "POST" ->
                path in
                    setOf(
                        "/v1/client/environment-link-challenges",
                        "/v1/client/environment-links",
                        "/v1/client/t3chat-link-requests",
                    )
            "DELETE" ->
                path == "/v1/client/t3chat-link" ||
                    Regex("/v1/client/environment-links/[^/?#]+(?:/tunnel)?").matches(path)
            else -> false
        }
    require(bearer) { "This T3 Connect request is not supported." }
    return emptySet()
}

internal fun assertTokenAccount(token: String, accountId: String) {
    val payload = runCatching {
        val parts = token.split('.')
        require(parts.size == 3)
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).requiredObject()
    }
        .getOrNull()
    if (payload?.optionalString("sub") != accountId)
        throw IOException("The T3 Connect account changed. Sign in again.")
}
