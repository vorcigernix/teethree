package com.example.teethree.data.auth

import com.example.teethree.data.SavedConnection
import com.example.teethree.data.effectiveHttpBaseUrl
import com.example.teethree.data.effectiveWsBaseUrl
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class EnvironmentAuthorization(
    private val relay: T3ConnectClient,
    private val beforeHttpAccess: suspend (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val directVerifier: DirectEnvironmentVerifier =
        DirectEnvironmentVerifier(relay.http, beforeHttpAccess, now),
) {
    private data class Cached(val identity: CloudIdentity, val connection: SavedConnection)

    private val lock = Mutex()
    private val cached = LinkedHashMap<String, Cached>()

    suspend fun clear() {
        lock.withLock { cached.clear() }
        directVerifier.clear()
    }

    suspend fun prepare(
        connection: SavedConnection,
        forceDirectVerification: Boolean = false,
    ): SavedConnection {
        if (connection.cloudAccountId == null) {
            directVerifier.verify(connection, force = forceDirectVerification)
            return connection
        }
        val identity = relay.awaitIdentity()
        if (identity.accountId != connection.cloudAccountId)
            throw IOException("Sign in to the T3 Connect account that owns this environment.")
        val environmentId =
            connection.environmentId
                ?: throw IOException("This T3 Connect environment must be added again.")
        return lock.withLock {
            relay.assertIdentity(identity)
            cached[connection.id]
                ?.takeIf {
                    it.identity == identity && it.connection.expiresAt > now() + 60_000
                }
                ?.let {
                    return@withLock prepareTransport(
                        it.connection,
                        connection,
                        forceDirectVerification,
                    )
                }
            val bootstrap = relay.bootstrap(environmentId)
            relay.assertIdentity(identity)
            beforeHttpAccess(bootstrap.endpoint.httpBaseUrl)
            val descriptor =
                relay.http
                    .cloudJson(
                        Request.Builder()
                            .url(
                                endpointUrl(
                                    bootstrap.endpoint.httpBaseUrl,
                                    "/.well-known/t3/environment",
                                )
                            )
                            .get()
                            .build()
                    )
                    .requiredObject()
            if (descriptor.requiredString("environmentId") != environmentId)
                throw IOException(
                    "T3 Connect reached a different environment. Reconnect from the environment list."
                )
            relay.assertIdentity(identity)
            val target = endpointUrl(bootstrap.endpoint.httpBaseUrl, "/oauth/token")
            val body =
                FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
                    .add("subject_token", bootstrap.credential)
                    .add(
                        "subject_token_type",
                        "urn:t3:params:oauth:token-type:environment-bootstrap",
                    )
                    .add("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
                    .add("client_label", "teethree Android")
                    .add("client_device_type", "mobile")
                    .add("client_os", "Android")
                    .build()
            val response =
                relay.http
                    .cloudJson(
                        Request.Builder()
                            .url(target)
                            .post(body)
                            .header("DPoP", relay.signer.proof("POST", target.toString()))
                            .build()
                    )
                    .requiredObject()
            relay.assertIdentity(identity)
            if (response.requiredString("token_type") != "DPoP") invalidCloudResponse()
            val prepared =
                connection.copy(
                    baseUrl = bootstrap.endpoint.httpBaseUrl,
                    wsBaseUrl = bootstrap.endpoint.wsBaseUrl,
                    accessToken = response.requiredString("access_token"),
                    tokenType = "DPoP",
                    scopes =
                        response
                            .optionalString("scope")
                            .orEmpty()
                            .split(Regex("\\s+"))
                            .filter(String::isNotEmpty)
                            .toSet(),
                    expiresAt =
                        now() +
                            response.positiveLong("expires_in").coerceAtMost(315_360_000) * 1_000,
                    label = descriptor.requiredString("label"),
                    connectionMethod = "relay",
                    selectedTransport = null,
                )
            cached[connection.id] = Cached(identity, prepared)
            while (cached.size > 64) cached.remove(cached.keys.first())
            prepareTransport(prepared, connection, forceDirectVerification)
        }
    }

    private suspend fun prepareTransport(
        canonical: SavedConnection,
        requested: SavedConnection,
        force: Boolean,
    ): SavedConnection {
        val prepared =
            canonical.copy(
                selectedTransport = requested.selectedTransport,
                learnedRoutes = requested.learnedRoutes,
                connectionMethod =
                    if (requested.selectedTransport == null) canonical.connectionMethod
                    else "direct",
            )
        if (prepared.selectedTransport != null) directVerifier.verify(prepared, force)
        return prepared
    }

    suspend fun authorize(connection: SavedConnection, request: Request): Request {
        if (!sameEndpoint(connection.effectiveHttpBaseUrl, request.url))
            throw IOException("Authorization cannot be sent outside the connected environment.")
        val prepared = prepare(connection)
        if (!sameEndpoint(prepared.effectiveHttpBaseUrl, request.url))
            throw IOException("The environment address changed. Reconnect and try again.")
        if (prepared.accessToken.isBlank())
            throw IOException("This environment must be paired again.")
        return request
            .newBuilder()
            .removeHeader("Authorization")
            .removeHeader("DPoP")
            .apply {
                when (prepared.tokenType) {
                    "Bearer" -> header("Authorization", "Bearer ${prepared.accessToken}")
                    "DPoP" -> {
                        header("Authorization", "DPoP ${prepared.accessToken}")
                        header(
                            "DPoP",
                            relay.signer.proof(
                                request.method,
                                request.url.toString(),
                                prepared.accessToken,
                            ),
                        )
                    }
                    else ->
                        throw IOException(
                            "This environment uses an unsupported authorization type."
                        )
                }
            }
            .build()
    }

    fun socketUrl(
        connection: SavedConnection,
        ticket: String,
        path: String = "/ws",
        use: String = "rpc",
    ): String {
        require(use in setOf("rpc", "media") && ticket.isNotBlank()) {
            "Invalid environment socket ticket."
        }
        val base =
            if (path == "/ws" && connection.effectiveWsBaseUrl != null) {
                val url =
                    connection.effectiveWsBaseUrl!!
                        .replaceFirst("wss://", "https://")
                        .replaceFirst("ws://", "http://")
                        .toHttpUrlOrNull()
                        ?: throw IOException("The environment socket address is invalid.")
                require(
                    url.username.isEmpty() &&
                        url.password.isEmpty() &&
                        url.query == null &&
                        url.fragment == null
                ) {
                    "The environment socket address is invalid."
                }
                if (url.encodedPath == "/") url.newBuilder().encodedPath("/ws").build() else url
            } else endpointUrl(connection.effectiveHttpBaseUrl, path)
        val target = base.newBuilder().addQueryParameter("wsTicket", ticket)
        if (use == "rpc") {
            target.addQueryParameter("orchestrationProtocol", "2")
            if (connection.tokenType == "DPoP")
                target.addQueryParameter("dpop", relay.signer.proof("GET", base.toString(), ticket))
        }
        target
            .addQueryParameter("clientSurface", "mobile")
            .addQueryParameter("clientOs", "android")
            .addQueryParameter("clientDeviceType", "phone")
            .addQueryParameter("connectionMethod", connection.connectionMethod)
        return target
            .build()
            .toString()
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
    }

    suspend fun renew(connection: SavedConnection) {
        if (connection.cloudAccountId == null) return
        var expiry = prepare(connection).expiresAt
        while (currentCoroutineContext().isActive) {
            delay((expiry - now() - 60_000).coerceAtLeast(5_000))
            val renewed =
                try {
                    prepare(connection)
                } catch (error: IOException) {
                    if (error is CloudHttpException && error.status in setOf(401, 403, 404))
                        throw error
                    if (now() >= expiry) throw error
                    delay(5_000)
                    continue
                }
            if (connection.selectedTransport == null && renewed.baseUrl != connection.baseUrl)
                throw IOException(
                    "The environment address changed. Reconnect to use its new address."
                )
            expiry = renewed.expiresAt
        }
    }

    suspend fun linkLocal(connection: SavedConnection, liveActivitiesEnabled: Boolean = false) {
        require(connection.tokenType == "Bearer" && connection.cloudAccountId == null) {
            "Link an environment using its directly paired connection."
        }
        require("relay:write" in connection.scopes) {
            "This connection needs relay:write permission to enable T3 Connect."
        }
        val identity = relay.identity()
        beforeHttpAccess(connection.baseUrl)
        val descriptor =
            relay.http
                .cloudJson(
                    Request.Builder()
                        .url(endpointUrl(connection.baseUrl, "/.well-known/t3/environment"))
                        .get()
                        .build()
                )
                .requiredObject()
        val expectedId = connection.environmentId ?: descriptor.requiredString("environmentId")
        if (descriptor.requiredString("environmentId") != expectedId) invalidCloudResponse()
        val capabilities =
            kotlinx.serialization.json.buildJsonObject {
                put("notificationsEnabled", kotlinx.serialization.json.JsonPrimitive(true))
                put(
                    "liveActivitiesEnabled",
                    kotlinx.serialization.json.JsonPrimitive(liveActivitiesEnabled),
                )
                put("managedTunnelsEnabled", kotlinx.serialization.json.JsonPrimitive(true))
            }
        val challenge =
            relay
                .request("POST", "/v1/client/environment-link-challenges", capabilities)
                .requiredObject()
        relay.assertIdentity(identity)
        val origin = connection.baseUrl.toHttpUrlOrNull() ?: invalidCloudResponse()
        val proofPayload =
            kotlinx.serialization.json.buildJsonObject {
                put("challenge", challenge["challenge"] ?: invalidCloudResponse())
                put("relayIssuer", kotlinx.serialization.json.JsonPrimitive(relay.config.relayUrl))
                put(
                    "endpoint",
                    kotlinx.serialization.json.buildJsonObject {
                        put(
                            "httpBaseUrl",
                            kotlinx.serialization.json.JsonPrimitive(connection.baseUrl),
                        )
                        put(
                            "wsBaseUrl",
                            kotlinx.serialization.json.JsonPrimitive(
                                connection.wsBaseUrl
                                    ?: endpointUrl(connection.baseUrl, "/ws")
                                        .toString()
                                        .replaceFirst("http", "ws")
                            ),
                        )
                        put(
                            "providerKind",
                            kotlinx.serialization.json.JsonPrimitive("cloudflare_tunnel"),
                        )
                    },
                )
                put(
                    "origin",
                    kotlinx.serialization.json.buildJsonObject {
                        put("localHttpHost", kotlinx.serialization.json.JsonPrimitive("127.0.0.1"))
                        put("localHttpPort", kotlinx.serialization.json.JsonPrimitive(origin.port))
                    },
                )
            }
        val proof = environmentJson(connection, "/api/connect/link-proof", proofPayload)
        relay.assertIdentity(identity)
        val link =
            relay
                .request(
                    "POST",
                    "/v1/client/environment-links",
                    JsonObject(capabilities + ("proof" to proof)),
                )
                .requiredObject()
        relay.assertIdentity(identity)
        if (
            link.requiredString("environmentId") != expectedId ||
                link.requiredString("cloudUserId") != identity.accountId ||
                parseCloudEndpoint(link["endpoint"]).providerKind != "cloudflare_tunnel" ||
                link.requiredString("relayIssuer") != relay.config.relayUrl
        )
            invalidCloudResponse()
        environmentJson(
            connection,
            "/api/connect/relay-config",
            JsonObject(
                mapOf(
                    "relayUrl" to kotlinx.serialization.json.JsonPrimitive(relay.config.relayUrl),
                    "relayIssuer" to (link["relayIssuer"] ?: invalidCloudResponse()),
                    "cloudUserId" to (link["cloudUserId"] ?: invalidCloudResponse()),
                    "environmentCredential" to
                        (link["environmentCredential"] ?: invalidCloudResponse()),
                    "cloudMintPublicKey" to (link["cloudMintPublicKey"] ?: invalidCloudResponse()),
                    "endpointRuntime" to (link["endpointRuntime"] ?: invalidCloudResponse()),
                )
            ),
        )
        relay.assertIdentity(identity)
    }

    private suspend fun environmentJson(
        connection: SavedConnection,
        path: String,
        payload: JsonObject,
    ): kotlinx.serialization.json.JsonElement {
        val request =
            Request.Builder()
                .url(endpointUrl(connection.baseUrl, path))
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        return relay.http.cloudJson(authorize(connection, request))
    }
}
