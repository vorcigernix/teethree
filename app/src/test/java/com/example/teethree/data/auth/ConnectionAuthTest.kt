package com.example.teethree.data.auth

import com.example.teethree.data.ConnectionAccessDeniedException
import com.example.teethree.data.DirectConnectionRoute
import com.example.teethree.data.SavedConnection
import com.example.teethree.data.effectiveHttpBaseUrl
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionAuthTest {
    @Test
    fun dpopProofsBindTheMethodUrlTokenAndPublicKeyWithCompactLowSSignatures() {
        val pair =
            KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        var id = 0
        val signer = DpopSigner(pair, now = { 1_800_000_000_000 }, identifier = { "proof-${++id}" })
        val proof =
            signer.proof(
                "post",
                "https://EXAMPLE.com:443/base/token?secret=query#fragment",
                "access-token",
            )
        val parts = proof.split('.')
        val header = decode(parts[0])
        val payload = decode(parts[1])
        assertEquals("ES256", header.requiredString("alg"))
        assertEquals("dpop+jwt", header.requiredString("typ"))
        val jwk = header.getValue("jwk").jsonObject
        assertFalse(jwk.containsKey("d"))
        assertEquals("P-256", jwk.requiredString("crv"))
        assertEquals(32, Base64.getUrlDecoder().decode(jwk.requiredString("x")).size)
        assertEquals(32, Base64.getUrlDecoder().decode(jwk.requiredString("y")).size)
        assertEquals("POST", payload.requiredString("htm"))
        assertEquals("https://example.com/base/token", payload.requiredString("htu"))
        assertEquals(1_800_000_000L, payload.positiveLong("iat"))
        assertEquals(sha256Base64Url("access-token"), payload.requiredString("ath"))
        val compact = Base64.getUrlDecoder().decode(parts[2])
        assertEquals(64, compact.size)
        val verifier =
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(pair.public)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            }
        assertTrue(verifier.verify(derSignature(compact)))
        val canonicalJwk =
            """{"crv":"P-256","kty":"EC","x":"${jwk.requiredString("x")}","y":"${jwk.requiredString("y")}"}"""
        assertEquals(sha256Base64Url(canonicalJwk), signer.thumbprint)
        assertEquals(
            "proof-2",
            decode(signer.proof("GET", "https://example.com/").split('.')[1]).requiredString("jti"),
        )
        val order =
            BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
        val high =
            BigInteger.ONE.toByteArray().let { ByteArray(31) + it } +
                (order - BigInteger.ONE).toByteArray().takeLast(32).toByteArray()
        val normalized = ecdsaDerToJose(derSignature(high))
        assertEquals(BigInteger.ONE, BigInteger(1, normalized.copyOfRange(32, 64)))
        assertFailsWith<IllegalArgumentException> {
            ecdsaDerToJose(byteArrayOf(0x30, 0x02, 0x02, 0x00))
        }
    }

    @Test
    fun cloudConfigurationAndAuthorizationRejectCredentialLeaksAcrossOriginsAndPrefixes() =
        runBlocking {
            val key = publicKey()
            assertNull(CloudConfig.parse("", "", ""))
            for (url in
                listOf(
                    "http://relay.invalid",
                    "https://relay.invalid/path",
                    "https://user:pass@relay.invalid",
                    "https://relay.invalid/?token=secret",
                    "https://relay.invalid/#fragment",
                )) {
                assertFailsWith<Exception> { CloudConfig.parse(key, "relay", url) }
            }
            assertFailsWith<Exception> {
                CloudConfig.parse(
                    "pk_live_" + Base64.getEncoder().encodeToString("host/path$".toByteArray()),
                    "relay",
                    "https://relay.invalid",
                )
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val relay =
                    T3ConnectClient(
                        CloudConfig.parse(key, "relay", "https://relay.invalid")!!,
                        FakeAccount(),
                        signer(),
                        scope,
                    )
                val auth = EnvironmentAuthorization(relay)
                val connection =
                    SavedConnection(
                        "direct",
                        "https://host.invalid/prefix",
                        "private-token",
                        emptySet(),
                        Long.MAX_VALUE,
                    )
                val allowed =
                    auth.authorize(
                        connection,
                        Request.Builder()
                            .url("https://host.invalid/prefix/api/file?cursor=one")
                            .build(),
                    )
                assertEquals("Bearer private-token", allowed.header("Authorization"))
                for (url in
                    listOf(
                        "https://evil.invalid/prefix/api/file",
                        "https://host.invalid:444/prefix/api/file",
                        "https://host.invalid/prefix-other/api/file",
                        "https://host.invalid/prefix/../outside",
                    )) {
                    assertFailsWith<IOException> {
                        auth.authorize(connection, Request.Builder().url(url).build())
                    }
                }
                val dpop =
                    connection.copy(tokenType = "DPoP", wsBaseUrl = "wss://host.invalid/prefix/ws")
                val rpc =
                    auth
                        .socketUrl(dpop, "socket-ticket")
                        .replaceFirst("wss://", "https://")
                        .toHttpUrl()
                val upgrade = rpc.queryParameter("dpop")!!.split('.')
                assertEquals(
                    "https://host.invalid/prefix/ws",
                    decode(upgrade[1]).requiredString("htu"),
                )
                assertEquals(
                    sha256Base64Url("socket-ticket"),
                    decode(upgrade[1]).requiredString("ath"),
                )
                val media = auth.socketUrl(dpop, "media-ticket", "/api/device-hub", "media")
                assertTrue(media.startsWith("wss://host.invalid/prefix/api/device-hub?"))
                assertFalse(media.contains("dpop="))
                assertFailsWith<IllegalArgumentException> {
                    requiredRelayScopes("GET", "https://evil.invalid/v1/environments")
                }
                assertFailsWith<IllegalArgumentException> {
                    requiredRelayScopes("POST", "/v1/admin/users")
                }
            } finally {
                scope.cancel()
            }
            Unit
        }

    @Test
    fun relayRefreshesRejectedDpopTokensOnceAndSignsTheFinalRequestWithoutForwardingClerkTokens() =
        runBlocking {
            fixture().use { fixture ->
                val account = FakeAccount()
                val tokens = AtomicInteger()
                val activity = AtomicInteger()
                fixture.server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.path) {
                                "/v1/client/dpop-token" -> {
                                    val number = tokens.incrementAndGet()
                                    assertNull(request.getHeader("Authorization"))
                                    val proof = decode(request.getHeader("DPoP")!!.split('.')[1])
                                    assertEquals(
                                        fixture.base + "/v1/client/dpop-token",
                                        proof.requiredString("htu"),
                                    )
                                    assertNull(proof["ath"])
                                    val body = request.body.readUtf8()
                                    assertTrue(body.contains("client_id=t3-mobile"))
                                    assertTrue(
                                        body.contains(
                                            "subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Ajwt"
                                        )
                                    )
                                    tokenResponse("relay-$number", "mobile:registration")
                                }
                                "/v1/mobile/agent-activity" -> {
                                    val number = activity.incrementAndGet()
                                    assertEquals(
                                        "DPoP relay-$number",
                                        request.getHeader("Authorization"),
                                    )
                                    val proof = decode(request.getHeader("DPoP")!!.split('.')[1])
                                    assertEquals(
                                        sha256Base64Url("relay-$number"),
                                        proof.requiredString("ath"),
                                    )
                                    if (number == 1)
                                        MockResponse()
                                            .setResponseCode(401)
                                            .setBody(
                                                """{"_tag":"RelayAuthInvalidError","reason":"invalid_bearer","code":"auth_invalid","traceId":"fixture"}"""
                                            )
                                    else MockResponse().setBody("""{"activities":[]}""")
                                }
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
                val client = fixture.relay(account)
                assertEquals(
                    0,
                    (client
                            .request("GET", "/v1/mobile/agent-activity")
                            .requiredObject()["activities"]
                            as kotlinx.serialization.json.JsonArray)
                        .size,
                )
                assertEquals(2, tokens.get())
                assertEquals(2, activity.get())
            }
        }

    @Test
    fun accountChangesRejectLateTokensAndMalformedResponsesNeverExposeTheirBody() = runBlocking {
        fixture().use { fixture ->
            val account = FakeAccount()
            val calls = AtomicInteger()
            fixture.server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        calls.incrementAndGet()
                        account.mutable.value =
                            CloudAccountState(true, true, CloudIdentity("other", "other-session"))
                        return tokenResponse("late-private-token", "mobile:registration")
                    }
                }
            val client = fixture.relay(account)
            val changed =
                assertFailsWith<IOException> { client.request("GET", "/v1/mobile/agent-activity") }
            assertTrue(changed.message!!.contains("account changed"))
            assertEquals(1, calls.get())
            fixture.server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) =
                        MockResponse().setBody("""{"access_token":"malformed-secret",BROKEN""")
                }
            val malformed =
                assertFailsWith<IOException> { client.request("GET", "/v1/environments") }
            assertFalse(malformed.stackTraceToString().contains("malformed-secret"))
            assertEquals("T3 Connect returned an invalid response.", malformed.message)
        }
    }

    @Test
    fun mismatchedEnvironmentDescriptorPreventsSendingTheBootstrapCredential() = runBlocking {
        fixture().use { fixture ->
            val oauthCalls = AtomicInteger()
            val descriptorCalls = AtomicInteger()
            fixture.server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.path) {
                            "/v1/client/dpop-token" ->
                                tokenResponse("relay-token", "environment:connect")
                            "/v1/environments/environment/connect" ->
                                MockResponse()
                                    .setBody(
                                        """{"environmentId":"environment","endpoint":{"httpBaseUrl":"${fixture.base}","wsBaseUrl":"${fixture.base.replaceFirst("https", "wss")}/ws","providerKind":"t3_relay"},"credential":"private-bootstrap","expiresAt":"2099-01-01T00:00:00Z"}"""
                                    )
                            "/.well-known/t3/environment" -> {
                                descriptorCalls.incrementAndGet()
                                assertNull(request.getHeader("Authorization"))
                                assertNull(request.getHeader("DPoP"))
                                MockResponse()
                                    .setBody(
                                        """{"environmentId":"different","label":"Wrong host"}"""
                                    )
                            }
                            "/oauth/token" -> {
                                oauthCalls.incrementAndGet()
                                MockResponse().setBody("{}")
                            }
                            else -> MockResponse().setResponseCode(404)
                        }
                }
            val account = FakeAccount()
            account.mutable.value = CloudAccountState(configured = true)
            val auth = EnvironmentAuthorization(fixture.relay(account))
            val saved =
                SavedConnection(
                    "cloud",
                    fixture.base,
                    "",
                    emptySet(),
                    0,
                    tokenType = "DPoP",
                    environmentId = "environment",
                    cloudAccountId = "account",
                )
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) { runCatching { auth.prepare(saved) } }
            assertEquals(0, fixture.server.requestCount)
            account.mutable.value =
                CloudAccountState(true, true, CloudIdentity("account", "session"))
            assertTrue(pending.await().exceptionOrNull() is IOException)
            assertEquals(0, oauthCalls.get())
            val direct =
                SavedConnection(
                    "paired",
                    fixture.base,
                    "paired-private-token",
                    emptySet(),
                    Long.MAX_VALUE,
                    environmentId = "environment",
                )
            assertFailsWith<IOException> {
                auth.authorize(
                    direct,
                    Request.Builder().url(fixture.base + "/api/auth/session").build(),
                )
            }
            assertEquals(2, descriptorCalls.get())
        }
    }

    @Test
    fun directIdentityVerificationBoundsItsPositiveCacheAndBypassesDiskCachesWithoutClerkConfiguration() =
        runBlocking {
            fixture().use { fixture ->
                val actualId = AtomicReference("environment")
                val hooks = AtomicInteger()
                var denied = false
                fixture.server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            assertTrue(request.path!!.endsWith("/.well-known/t3/environment"))
                            assertNull(request.getHeader("Authorization"))
                            assertNull(request.getHeader("DPoP"))
                            assertNull(request.getHeader("Cookie"))
                            return MockResponse()
                                .setHeader("Cache-Control", "public, max-age=86400")
                                .setBody(
                                    """{"environmentId":"${actualId.get()}","label":"Fixture"}"""
                                )
                        }
                    }
                val directory = Files.createTempDirectory("teethree-direct-identity-test")
                val cache = Cache(directory.toFile(), 1_048_576)
                try {
                    val cachingClient = fixture.http.newBuilder().cache(cache).build()
                    cachingClient
                        .newCall(
                            Request.Builder()
                                .url(fixture.base + "/.well-known/t3/environment")
                                .build()
                        )
                        .execute()
                        .use {
                            assertTrue(it.isSuccessful)
                            it.body!!.string()
                        }
                    var clock = 0L
                    val verifier =
                        DirectEnvironmentVerifier(
                            cachingClient,
                            {
                                hooks.incrementAndGet()
                                if (denied)
                                    throw ConnectionAccessDeniedException(
                                        "Local network access denied."
                                    )
                            },
                            { clock },
                        )
                    val saved =
                        SavedConnection(
                            "paired",
                            fixture.base,
                            "never-forward-this-token",
                            emptySet(),
                            Long.MAX_VALUE,
                            environmentId = "environment",
                        )
                    actualId.set("different")
                    assertFailsWith<IOException> { verifier.verify(saved) }
                    assertEquals(2, fixture.server.requestCount)
                    actualId.set("environment")
                    verifier.verify(saved)
                    assertEquals(3, fixture.server.requestCount)
                    clock = 59_999
                    verifier.verify(saved)
                    assertEquals(3, fixture.server.requestCount)
                    clock = 60_000
                    actualId.set("different")
                    assertFailsWith<IOException> { verifier.verify(saved) }
                    assertEquals(4, fixture.server.requestCount)
                    actualId.set("environment")
                    verifier.verify(saved)
                    verifier.verify(saved)
                    assertEquals(5, fixture.server.requestCount)
                    actualId.set("different")
                    assertFailsWith<IOException> { verifier.verify(saved, force = true) }
                    assertEquals(6, fixture.server.requestCount)
                    actualId.set("environment")
                    verifier.verify(saved)
                    clock = 59_999
                    verifier.verify(saved)
                    assertEquals(8, fixture.server.requestCount)
                    assertFailsWith<IOException> {
                        verifier.verify(saved.copy(environmentId = "another-environment"))
                    }
                    assertEquals(9, fixture.server.requestCount)
                    verifier.verify(saved.copy(baseUrl = fixture.base + "/prefix"))
                    assertEquals(10, fixture.server.requestCount)
                    verifier.verify(saved.copy(environmentId = null))
                    assertEquals(10, fixture.server.requestCount)
                    denied = true
                    assertFailsWith<ConnectionAccessDeniedException> {
                        verifier.verify(saved, force = true)
                    }
                    assertEquals(10, fixture.server.requestCount)
                    denied = false
                    verifier.verify(saved)
                    assertEquals(11, fixture.server.requestCount)
                    verifier.clear()
                    verifier.verify(saved)
                    assertEquals(12, fixture.server.requestCount)
                    assertEquals(12, hooks.get())
                } finally {
                    cache.close()
                    directory.toFile().deleteRecursively()
                }
            }
            Unit
        }

    @Test
    fun learnedCloudTransportKeepsBootstrapAndRenewalOnTheCanonicalEndpoint() = runBlocking {
        fixture().use { fixture ->
            val exchanges = AtomicInteger()
            var clock = 0L
            val paths = Collections.synchronizedList(mutableListOf<String>())
            fixture.server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        paths += request.path!!
                        return when (request.path) {
                            "/v1/client/dpop-token" ->
                                tokenResponse("relay-token", "environment:connect")
                            "/v1/environments/environment/connect" ->
                                MockResponse()
                                    .setBody(
                                        """{"environmentId":"environment","endpoint":{"httpBaseUrl":"${fixture.base}","wsBaseUrl":"${fixture.base.replaceFirst("https", "wss")}/ws","providerKind":"t3_relay"},"credential":"bootstrap","expiresAt":"2099-01-01T00:00:00Z"}"""
                                    )
                            "/.well-known/t3/environment",
                            "/lan/.well-known/t3/environment" -> {
                                assertNull(request.getHeader("Authorization"))
                                assertNull(request.getHeader("DPoP"))
                                MockResponse()
                                    .setBody(
                                        """{"environmentId":"environment","label":"Fixture"}"""
                                    )
                            }
                            "/oauth/token" -> {
                                val number = exchanges.incrementAndGet()
                                assertTrue(
                                    request.body.readUtf8().contains("subject_token=bootstrap")
                                )
                                tokenResponse("environment-$number", "orchestration:read")
                            }
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
            val auth = EnvironmentAuthorization(fixture.relay(FakeAccount()), now = { clock })
            val saved =
                SavedConnection(
                    "cloud",
                    fixture.base,
                    "",
                    emptySet(),
                    0,
                    tokenType = "DPoP",
                    environmentId = "environment",
                    cloudAccountId = "account",
                    selectedTransport = DirectConnectionRoute(fixture.base + "/lan", "lan"),
                )
            val first = auth.prepare(saved, forceDirectVerification = true)
            assertEquals(fixture.base, first.baseUrl)
            assertEquals(fixture.base + "/lan", first.effectiveHttpBaseUrl)
            val url = fixture.base + "/lan/api/auth/session"
            val signed = auth.authorize(first, Request.Builder().url(url).build())
            assertEquals("DPoP environment-1", signed.header("Authorization"))
            assertEquals(url, decode(signed.header("DPoP")!!.split('.')[1]).requiredString("htu"))
            clock = 540_001
            val renewed = auth.prepare(first)
            assertEquals("environment-2", renewed.accessToken)
            assertEquals(first.baseUrl, renewed.baseUrl)
            assertEquals(first.selectedTransport, renewed.selectedTransport)
            assertEquals(2, exchanges.get())
            assertFalse(paths.any { it.startsWith("/lan/oauth") || it.startsWith("/lan/v1/") })
            assertTrue(
                auth
                    .socketUrl(renewed, "ticket")
                    .startsWith(fixture.base.replaceFirst("https", "wss") + "/lan/ws?")
            )
        }
        Unit
    }

    private class FakeAccount : CloudSessionSource {
        val mutable =
            MutableStateFlow(CloudAccountState(true, true, CloudIdentity("account", "session")))
        override val state = mutable

        override suspend fun token(): String =
            "e30.${base64Url("""{"sub":"${state.value.identity!!.accountId}"}""".toByteArray())}.signature"

        override suspend fun signOut() {
            mutable.value = CloudAccountState(true, true)
        }
    }

    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val server = MockWebServer()
        val http: OkHttpClient
        val base: String

        init {
            val certificate =
                HeldCertificate.Builder()
                    .commonName("localhost")
                    .addSubjectAlternativeName("localhost")
                    .build()
            server.useHttps(
                HandshakeCertificates.Builder()
                    .heldCertificate(certificate)
                    .build()
                    .sslSocketFactory(),
                false,
            )
            val trusted =
                HandshakeCertificates.Builder()
                    .addTrustedCertificate(certificate.certificate)
                    .build()
            http =
                OkHttpClient.Builder()
                    .sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
                    .build()
            server.start()
            base = server.url("/").toString().trimEnd('/')
        }

        fun relay(account: CloudSessionSource) =
            T3ConnectClient(
                CloudConfig.parse(publicKey(), "relay", base)!!,
                account,
                signer(),
                scope,
                http,
            )

        override fun close() {
            scope.cancel()
            server.shutdown()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    companion object {
        private fun fixture() = Fixture()

        private fun publicKey() =
            "pk_live_" + Base64.getEncoder().encodeToString("accounts.invalid$".toByteArray())

        private fun signer(): DpopSigner =
            DpopSigner(
                KeyPairGenerator.getInstance("EC")
                    .apply { initialize(ECGenParameterSpec("secp256r1")) }
                    .generateKeyPair()
            )

        private fun decode(value: String) =
            Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(value))).jsonObject

        private fun tokenResponse(token: String, scopes: String) =
            MockResponse()
                .setBody(
                    """{"access_token":"$token","token_type":"DPoP","issued_token_type":"urn:ietf:params:oauth:token-type:access_token","expires_in":600,"scope":"$scopes"}"""
                )

        private fun derSignature(compact: ByteArray): ByteArray {
            fun integer(bytes: ByteArray): ByteArray {
                val value = BigInteger(1, bytes).toByteArray()
                return byteArrayOf(2, value.size.toByte()) + value
            }
            val body = integer(compact.copyOfRange(0, 32)) + integer(compact.copyOfRange(32, 64))
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
    }
}
