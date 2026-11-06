package com.example.teethree.data

import com.example.teethree.restorableThreadId
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolTest {
    @Test
    fun deniedPlatformNetworkAccessIsTerminalAndDoesNotSendHttp() = runBlocking {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val repository =
            T3Repository(
                this,
                beforeHttpAccess = {
                    attempts.incrementAndGet()
                    throw ConnectionAccessDeniedException("Local network permission is required.")
                },
            )
        try {
            assertFailsWith<ConnectionAccessDeniedException> {
                repository.pair("http://127.0.0.1:9", "credential")
            }
            repository.connect(
                SavedConnection(
                    "denied",
                    "http://127.0.0.1:9",
                    "access",
                    emptySet(),
                    Long.MAX_VALUE,
                )
            )
            val blocked =
                repository.state.first {
                    it.connectionStatus == ConnectionStatus.Disconnected && it.error != null
                }
            assertEquals("Local network permission is required.", blocked.error)
            assertEquals(2, attempts.get())
        } finally {
            repository.disconnect()
        }
    }

    @Test
    fun pairingLinksResolveWithoutSavingCredentialsInTheOrigin() {
        assertEquals(
            "https://host.example" to "pair+code",
            parsePairingTarget("https://host.example/path?token=ignored#token=pair%2Bcode", ""),
        )
        assertEquals(
            "http://192.168.1.4:3773" to "code",
            parsePairingTarget(
                "https://app.t3.codes/?host=http%3A%2F%2F192.168.1.4%3A3773#token=code",
                "",
            ),
        )
        assertEquals(
            "https://host.example" to "code",
            parsePairingTarget(
                "t3code://pair?pairingUrl=https%3A%2F%2Fhost.example%2F%23token%3Dcode",
                "",
            ),
        )
        assertEquals(
            "http://[::1]:3773" to "manual",
            parsePairingTarget("http://[::1]:3773", "manual"),
        )
        assertEquals(
            "https://host.example" to "code",
            parsePairingTarget(
                "teethree://pair?pairingUrl=https%3A%2F%2Fhost.example%2F%23token%3Dcode",
                "",
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            parsePairingTarget("file:///etc/passwd", "code")
        }
        assertFailsWith<IllegalArgumentException> {
            parsePairingTarget("https://user:pass@host.example", "code")
        }
        assertFailsWith<IllegalArgumentException> { parsePairingTarget("https://host.example", "") }
        val invalid =
            assertFailsWith<IllegalArgumentException> {
                parsePairingTarget("https://host.example/#token=secret token", "")
            }
        assertEquals("Enter a valid environment URL.", invalid.message)
    }

    @Test
    fun payloadSensitiveToolPermissionsDoNotFallBackToAWeakerGrant() {
        assertEquals(
            setOf("access:read"),
            requiredToolScopes("peerLinks.list", JsonObject(emptyMap())),
        )
        listOf("""{"retryHostId":"host"}""", """{"updateTool":"hub"}""").forEach { raw ->
            assertEquals(
                setOf("orchestration:read", "orchestration:operate"),
                requiredToolScopes("device.list", Json.parseToJsonElement(raw).jsonObject),
            )
        }
        listOf("{}", """{"inspectOnly":true}""").forEach { raw ->
            assertEquals(
                setOf("orchestration:read"),
                requiredToolScopes("device.list", Json.parseToJsonElement(raw).jsonObject),
            )
        }
        assertEquals(
            setOf("orchestration:read"),
            requiredToolScopes("orchestration.getTurnItem", JsonObject(emptyMap())),
        )
        assertEquals(
            setOf("terminal:read"),
            requiredToolScopes("subscribeTerminalMetadata", JsonObject(emptyMap())),
        )
        assertEquals(
            setOf("filesystem:read"),
            requiredToolScopes("projects.searchEntries", JsonObject(emptyMap())),
        )
        assertEquals(
            setOf("orchestration:read"),
            requiredToolScopes("orchestration.getWorkflowScript", JsonObject(emptyMap())),
        )
        assertEquals(
            setOf("filesystem:read"),
            requiredToolScopes(
                "assets.createUrl",
                Json.parseToJsonElement("""{"resource":{"_tag":"workspace-file"}}""").jsonObject,
            ),
        )
        assertEquals(
            setOf("orchestration:read"),
            requiredToolScopes(
                "assets.createUrl",
                Json.parseToJsonElement("""{"resource":{"_tag":"attachment"}}""").jsonObject,
            ),
        )
        assertEquals(
            setOf("source-control:write", "orchestration:read"),
            requiredToolScopes(
                "git.runStackedAction",
                Json.parseToJsonElement("""{"threadId":"thread","action":"create_pr"}""")
                    .jsonObject,
            ),
        )
        assertEquals(
            setOf("settings:write"),
            requiredToolScopes("server.updateSettings", JsonObject(emptyMap())),
        )
        assertEquals(
            setOf("providers:manage"),
            requiredToolScopes(
                "server.updateSettings",
                Json.parseToJsonElement(
                        """{"patch":{},"providerInstanceMutation":{"operation":"upsert"}}"""
                    )
                    .jsonObject,
            ),
        )
        assertEquals(
            setOf("providers:manage", "settings:write"),
            requiredToolScopes(
                "server.updateSettings",
                Json.parseToJsonElement(
                        """{"patch":{"providerInstances":{},"defaultRuntimeMode":"approval-required"}}"""
                    )
                    .jsonObject,
            ),
        )
        assertEquals(
            setOf("orchestration:operate", "settings:write"),
            requiredToolScopes(
                "projects.mutate",
                Json.parseToJsonElement("""{"type":"project.update","scripts":[]}""").jsonObject,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            requiredToolScopes("server.unrecognizedOperation", JsonObject(emptyMap()))
        }
    }

    @Test
    fun messageLimitsAcceptAttachmentsOnlyAndRejectOversizeAggregateImages() {
        val image = buildJsonObject {
            put("type", "image")
            put("mimeType", "image/png")
            put("sizeBytes", 10 * 1024 * 1024)
        }
        validateMessageInput("", listOf(image))
        validateMessageInput("x".repeat(120_000), List(8) { image })
        assertFailsWith<IllegalArgumentException> {
            validateMessageInput("x".repeat(120_001), emptyList())
        }
        assertFailsWith<IllegalArgumentException> { validateMessageInput("x", List(9) { image }) }
        val file = buildJsonObject {
            put("type", "file")
            put("mimeType", "text/plain")
            put("sizeBytes", 1)
        }
        validateMessageInput("", List(100) { file })
        assertFailsWith<IllegalArgumentException> { validateMessageInput("", List(101) { file }) }
    }

    @Test
    fun pairingUsesTheOAuthFormAndStoresOnlyTheIssuedToken() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        val repository = T3Repository(this)
        val environmentId = java.util.concurrent.atomic.AtomicReference("environment-first")
        val brokenToken = java.util.concurrent.atomic.AtomicBoolean(false)
        val descriptorStatus = java.util.concurrent.atomic.AtomicInteger(200)
        val tokenRequests = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/.well-known/t3/environment" ->
                            MockResponse()
                                .setResponseCode(descriptorStatus.get())
                                .setBody(
                                    """{"environmentId":"${environmentId.get()}","label":"Label ${environmentId.get()}","platform":"linux","serverVersion":"1.0","capabilities":{}}"""
                                )
                        "/oauth/token" -> {
                            tokenRequests.incrementAndGet()
                            MockResponse()
                                .setBody(
                                    if (brokenToken.get())
                                        """{"access_token":"sensitive-issued-token","broken": """
                                    else
                                        """{"access_token":"issued-token","token_type":"Bearer","expires_in":3600,"scope":"orchestration:read orchestration:operate"}"""
                                )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
            }
        try {
            val connection = repository.pair(server.url("/").toString(), "one-time-pairing-code")
            environmentId.set("environment-replacement")
            val replacement = repository.pair(server.url("/").toString(), "second-one-time-code")
            assertTrue(
                "A reused address must not reuse another environment's owner (identities: ${connection.environmentId}, ${replacement.environmentId}).",
                connection.id != replacement.id,
            )
            assertEquals("environment-first", connection.environmentId)
            assertEquals("environment-replacement", replacement.environmentId)
            assertEquals("Label environment-first", connection.label)
            assertEquals("Label environment-replacement", replacement.label)
            val descriptor = server.takeRequest()
            assertEquals("/.well-known/t3/environment", descriptor.path)
            assertEquals("GET", descriptor.method)
            assertNull(descriptor.getHeader("Authorization"))
            assertNull(descriptor.getHeader("DPoP"))
            assertTrue(descriptor.body.size == 0L)
            val request = server.takeRequest()
            assertEquals("/oauth/token", request.path)
            assertEquals("POST", request.method)
            assertTrue(
                request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded")
            )
            val form = request.body.readUtf8()
            assertTrue(form.contains("subject_token=one-time-pairing-code"))
            assertTrue(
                form.contains(
                    "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange"
                )
            )
            assertEquals("issued-token", connection.accessToken)
            assertEquals(setOf("orchestration:read", "orchestration:operate"), connection.scopes)
            assertFalse(connection.baseUrl.contains("one-time-pairing-code"))
            assertEquals("/.well-known/t3/environment", server.takeRequest().path)
            assertEquals("/oauth/token", server.takeRequest().path)
            assertEquals(
                replacement.id,
                repository.pair(server.url("/").toString(), "another-code").id,
            )
            assertEquals("/.well-known/t3/environment", server.takeRequest().path)
            assertEquals("/oauth/token", server.takeRequest().path)
            brokenToken.set(true)
            val error =
                assertFailsWith<IOException> {
                    repository.pair(server.url("/").toString(), "secret-pairing-code")
                }
            assertEquals("The environment returned an invalid response.", error.message)
            assertFalse(error.message!!.contains("sensitive-issued-token"))
            assertFalse(error.message!!.contains("secret-pairing-code"))
            assertEquals("/.well-known/t3/environment", server.takeRequest().path)
            assertEquals("/oauth/token", server.takeRequest().path)
            brokenToken.set(false)
            descriptorStatus.set(404)
            val legacy = repository.pair(server.url("/").toString(), "legacy-code")
            val legacyAgain = repository.pair(server.url("/").toString(), "new-legacy-code")
            assertNull(legacy.environmentId)
            assertTrue(
                "A legacy server must not inherit an unverified old owner",
                legacy.id != legacyAgain.id &&
                    legacy.id != connection.id &&
                    legacy.id != replacement.id,
            )
            repeat(2) {
                assertEquals("/.well-known/t3/environment", server.takeRequest().path)
                assertEquals("/oauth/token", server.takeRequest().path)
            }
            descriptorStatus.set(200)
            environmentId.set("")
            val beforeInvalid = tokenRequests.get()
            assertFailsWith<IOException> {
                repository.pair(server.url("/").toString(), "unspent-code")
            }
            assertEquals("/.well-known/t3/environment", server.takeRequest().path)
            assertEquals(
                "An invalid public identity must not spend the one-time code",
                beforeInvalid,
                tokenRequests.get(),
            )
        } finally {
            repository.disconnect()
            server.shutdown()
        }
    }

    @Test
    fun streamChunksAreAcknowledgedAndSubscriptionsAreInterrupted() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        val frames = Channel<JsonObject>(Channel.UNLIMITED)
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = Json.parseToJsonElement(text).jsonObject
                            frames.trySend(frame)
                            if (frame.string("_tag") == "Request") {
                                webSocket.send(
                                    """{"_tag":"Chunk","requestId":"${frame.string("id")}","values":[null,{"kind":"snapshot"}]}"""
                                )
                            }
                        }
                    }
                )
        )
        server.start()
        val client = EffectRpcClient(this, OkHttpClient())
        try {
            client.connect(server.url("/ws").toString())
            val received =
                Channel<Result<kotlinx.serialization.json.JsonElement>>(Channel.UNLIMITED)
            val id =
                client.subscribeValue(
                    "orchestration.subscribeShell",
                    JsonObject(emptyMap()),
                    onError = { received.trySend(Result.failure(it)) },
                ) {
                    received.trySend(Result.success(it))
                }
            val closed = async { client.awaitClosed() }
            val first =
                kotlinx.coroutines.selects.select<kotlinx.serialization.json.JsonElement> {
                    received.onReceive { it.getOrThrow() }
                    closed.onAwait { throw it }
                }
            closed.cancel()
            assertEquals(kotlinx.serialization.json.JsonNull, first)
            assertEquals("snapshot", received.receive().getOrThrow().jsonObject.string("kind"))
            val request = frames.receive()
            assertEquals("Request", request.string("_tag"))
            assertEquals("orchestration.subscribeShell", request.string("tag"))
            assertEquals(emptyList<JsonPrimitive>(), request.array("headers"))
            val ack = frames.receive()
            assertEquals("Ack", ack.string("_tag"))
            assertEquals(id, ack.string("requestId"))
            client.unsubscribe(id)
            val interrupt = frames.receive()
            assertEquals("Interrupt", interrupt.string("_tag"))
            assertEquals(id, interrupt.string("requestId"))
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test
    fun rpcFailuresAndDisconnectsCompletePendingCalls() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        val waiting = CompletableDeferred<Unit>()
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = Json.parseToJsonElement(text).jsonObject
                            if (frame.string("_tag") != "Request") return
                            if (frame.string("tag") == "denied") {
                                webSocket.send(
                                    """{"_tag":"Exit","requestId":"${frame.string("id")}","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"EnvironmentAuthorizationError","requiredScope":"orchestration:operate","message":"Permission required"}}]}}"""
                                )
                            } else waiting.complete(Unit)
                        }
                    }
                )
        )
        server.start()
        val client = EffectRpcClient(this, OkHttpClient())
        try {
            client.connect(server.url("/ws").toString())
            val denied =
                assertFailsWith<EnvironmentHttpException> {
                    client.call("denied", JsonObject(emptyMap()))
                }
            assertEquals(403, denied.status)
            assertEquals("Permission required", denied.message)
            val pending = async {
                runCatching { client.call("waiting", buildJsonObject { put("value", 1) }) }
            }
            waiting.await()
            client.close()
            assertTrue(pending.await().exceptionOrNull() is IOException)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test
    fun ownedConnectionAndCallTimeoutsRemainErrorsWhileParentIsActive() = runTest {
        val unopened =
            EffectRpcClient(backgroundScope, OkHttpClient()) { request, _ -> fakeSocket(request) }
        val connecting = async { runCatching { unopened.connect("http://localhost/ws") } }
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(connecting.await().exceptionOrNull() is IOException)
        unopened.close()
        var allowSends = true
        val opened =
            EffectRpcClient(backgroundScope, OkHttpClient()) { request, listener ->
                fakeSocket(request) { allowSends }
                    .also { socket ->
                        listener.onOpen(
                            socket,
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(101)
                                .message("Switching Protocols")
                                .build(),
                        )
                    }
            }
        try {
            opened.connect("http://localhost/ws")
            val waiting = async {
                runCatching { opened.call("server.probe", JsonObject(emptyMap())) }
            }
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            val failure = waiting.await().exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals("The environment request timed out.", failure?.message)
            val signIn = async {
                runCatching { opened.call("sourceControl.signInWait", JsonObject(emptyMap())) }
            }
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            assertTrue(signIn.isActive)
            signIn.cancel()
            assertFailsWith<kotlinx.coroutines.CancellationException> { signIn.await() }
            val cancelled = async { opened.call("server.probe", JsonObject(emptyMap())) }
            runCurrent()
            cancelled.cancel()
            assertFailsWith<kotlinx.coroutines.CancellationException> { cancelled.await() }
            allowSends = false
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(opened.awaitClosed() is IOException)
        } finally {
            opened.close()
        }
    }

    @Test
    fun threadLifecycleRefreshesArchivesAndValidatesHistoryCursorProgress() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        val shellAcknowledged = CompletableDeferred<Unit>()
        val familyShellAcknowledged = CompletableDeferred<Unit>()
        val shellSocket = CompletableDeferred<WebSocket>()
        var archived = false
        var shellId = ""
        val thread =
            """{"id":"thread","projectId":"project","title":"Fixture","status":"idle","modelSelection":{"instanceId":"replay","model":"showcase"},"activeRunId":null,"updatedAt":"2026-11-04T00:00:00Z"}"""
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path == "/api/auth/session" ->
                            MockResponse()
                                .setBody(
                                    """{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}"""
                                )
                        request.path == "/api/auth/websocket-ticket/rpc" ->
                            MockResponse().setBody("""{"ticket":"ticket"}""")
                        request.path ==
                            "/api/orchestration/threads/thread/history?cursor=one&throughEntryId=older-message" ->
                            MockResponse()
                                .setBody(
                                    """{"items":[],"nextCursor":"two","hasMoreHistory":true}"""
                                )
                        request.path == "/api/orchestration/threads/thread/history?cursor=two" ->
                            MockResponse()
                                .setBody(
                                    """{"items":[],"nextCursor":"two","hasMoreHistory":true}"""
                                )
                        request.path?.startsWith("/ws?") == true ->
                            MockResponse()
                                .withWebSocketUpgrade(
                                    object : WebSocketListener() {
                                        override fun onMessage(webSocket: WebSocket, text: String) {
                                            val frame = Json.parseToJsonElement(text).jsonObject
                                            if (
                                                frame.string("_tag") == "Ack" &&
                                                    frame.string("requestId") == shellId
                                            )
                                                if (!shellAcknowledged.complete(Unit))
                                                    familyShellAcknowledged.complete(Unit)
                                            if (frame.string("_tag") != "Request") return
                                            val id = frame.string("id")
                                            when (frame.string("tag")) {
                                                "subscribeServerConfig" ->
                                                    webSocket.send(
                                                        """{"_tag":"Chunk","requestId":"$id","values":[{"type":"snapshot","config":{"providers":[{"instanceId":"codex","enabled":true,"installed":true,"models":[]},{"instanceId":"cursor","enabled":true,"installed":true,"models":[],"supportsConversationRollback":false}]}}]}"""
                                                    )
                                                "orchestration.subscribeShell" -> {
                                                    shellId = id
                                                    shellSocket.complete(webSocket)
                                                    webSocket.send(
                                                        """{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[{"id":"project","title":"Fixture project","workspaceRoot":"/fixture"},{"id":"second","title":"Other project","workspaceRoot":"/second"}],"threads":[$thread],"archivedThreads":[]}},{"kind":"snapshot","snapshot":{"projects":[{"id":"project","title":"Stale project title","workspaceRoot":"/fixture","repositoryIdentity":{"canonicalKey":"repo-key","rootPath":"/fixture","displayName":"Fixture repository"}}],"threads":[],"archivedThreads":[]},"resolvedRepositoryIdentityRoots":["/fixture"]},{"kind":"snapshot","snapshot":{"projects":[],"threads":[],"archivedThreads":[]},"resolvedRepositoryIdentityRoots":[]}]}"""
                                                    )
                                                }
                                                "orchestration.getThreadFamily" -> {
                                                    val rootThread =
                                                        Json.parseToJsonElement(thread).jsonObject
                                                    val children =
                                                        (0 until 300).map { index ->
                                                            JsonObject(
                                                                rootThread +
                                                                    mapOf(
                                                                        "id" to
                                                                            JsonPrimitive(
                                                                                "child-$index"
                                                                            ),
                                                                        "creationSource" to
                                                                            JsonPrimitive(
                                                                                "provider"
                                                                            ),
                                                                        "lineage" to
                                                                            buildJsonObject {
                                                                                put(
                                                                                    "parentThreadId",
                                                                                    "thread",
                                                                                )
                                                                                put(
                                                                                    "rootThreadId",
                                                                                    "thread",
                                                                                )
                                                                                put(
                                                                                    "relationshipToParent",
                                                                                    "subagent",
                                                                                )
                                                                            },
                                                                    )
                                                            )
                                                        }
                                                    webSocket.send(
                                                        buildJsonObject {
                                                            put("_tag", "Exit")
                                                            put("requestId", id)
                                                            put(
                                                                "exit",
                                                                buildJsonObject {
                                                                    put("_tag", "Success")
                                                                    put(
                                                                        "value",
                                                                        buildJsonObject {
                                                                            put(
                                                                                "snapshotSequence",
                                                                                1,
                                                                            )
                                                                            put(
                                                                                "threads",
                                                                                kotlinx
                                                                                    .serialization
                                                                                    .json
                                                                                    .JsonArray(
                                                                                        listOf(
                                                                                            rootThread
                                                                                        ) + children
                                                                                    ),
                                                                            )
                                                                        },
                                                                    )
                                                                },
                                                            )
                                                        }
                                                            .toString()
                                                    )
                                                }
                                                "orchestration.getArchivedShellSnapshot" ->
                                                    webSocket.send(
                                                        """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"threads":[${if (archived) thread else ""}]}}}"""
                                                    )
                                                "orchestration.subscribeThread" ->
                                                    webSocket.send(
                                                        """{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","projection":{"visibleTurnItems":[{"sourceThreadId":"thread","sourceItemId":"reply","visibility":"local","item":{"id":"reply","type":"assistant_message","text":"Existing reply"}}]},"historyCursor":"one","hasMoreHistory":true}]}"""
                                                    )
                                                "orchestration.dispatchCommand" -> {
                                                    archived =
                                                        frame.obj("payload").string("type") ==
                                                            "thread.archive"
                                                    val event =
                                                        if (archived)
                                                            """{"kind":"thread.removed","location":"active","threadId":"thread"}"""
                                                        else
                                                            """{"kind":"thread.updated","location":"active","thread":$thread}"""
                                                    webSocket.send(
                                                        """{"_tag":"Chunk","requestId":"$shellId","values":[$event]}"""
                                                    )
                                                    webSocket.send(
                                                        """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"sequence":1}}}"""
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
        val repository = T3Repository(this)
        try {
            assertFalse(repository.state.value.shellReady)
            repository.connect(
                SavedConnection(
                    "test",
                    server.url("/").toString().trimEnd('/'),
                    "access",
                    setOf("orchestration:read", "orchestration:operate"),
                    Long.MAX_VALUE,
                )
            )
            shellAcknowledged.await()
            assertTrue(repository.state.value.shellReady)
            assertEquals("thread", repository.state.value.restorableThreadId("thread"))
            assertNull(repository.state.value.restorableThreadId("deleted"))
            assertEquals(listOf("thread"), repository.state.value.threads.map(T3Thread::id))
            assertEquals(
                listOf("project", "second"),
                repository.state.value.projects.map(T3Project::id),
            )
            assertEquals("Fixture project", repository.state.value.projects.first().title)
            assertEquals("repo-key", repository.state.value.projects.first().repositoryKey)
            val summary = Json.parseToJsonElement(thread).jsonObject
            val attentionVariants =
                listOf(
                    """{}""",
                    """{"status":"waiting"}""",
                    """{"pendingRuntimeRequest":null}""",
                    """{"pendingRuntimeRequest":{"kind":"command"}}""",
                    """{"pendingRuntimeRequest":{"kind":"user_input","blocking":false}}""",
                    """{"pendingRuntimeRequest":{"kind":"auth_refresh"}}""",
                    """{"interactionMode":"plan","hasActionableProposedPlan":true}""",
                    """{"interactionMode":"default","hasActionableProposedPlan":true}""",
                )
            assertEquals(
                listOf(false, false, false, true, true, false, true, false),
                attentionVariants.map {
                    parseThread(
                            JsonObject(summary + Json.parseToJsonElement(it).jsonObject),
                            archived = false,
                        )
                        .needsAttention
                },
            )
            assertTrue(
                repository.state.value.providers
                    .first { it.instanceId == "codex" }
                    .supportsConversationRollback
            )
            assertFalse(
                repository.state.value.providers
                    .first { it.instanceId == "cursor" }
                    .supportsConversationRollback
            )
            repository.setArchived("thread", true)
            val archive =
                repository.state.first {
                    it.threads.any { thread -> thread.id == "thread" && thread.archived }
                }
            assertEquals(1, archive.threads.size)
            assertNull(archive.restorableThreadId("thread"))
            repository.setArchived("thread", false)
            val active =
                repository.state.first {
                    it.threads.any { thread -> thread.id == "thread" && !thread.archived }
                }
            assertEquals(1, active.threads.size)
            assertFalse(active.threads.single().archived)
            assertEquals("thread", active.restorableThreadId("thread"))
            val readySnapshot =
                async(Dispatchers.Unconfined) {
                    repository.state.first { it.selectedThreadId == "thread" && !it.threadLoading }
                }
            repository.selectThread("thread")
            val loaded = readySnapshot.await()
            assertEquals("Existing reply", loaded.timeline.single().text)
            repository.loadOlder(throughEntryId = "older-message")
            val paged = repository.state.value
            assertEquals(loaded.historyRevision + 1, paged.historyRevision)
            assertTrue(paged.hasOlderMessages)
            assertFalse(paged.historyLoading)
            assertFailsWith<IOException> { repository.loadOlder() }
            assertEquals(paged.historyRevision, repository.state.value.historyRevision)
            assertFalse(repository.state.value.historyLoading)
            val family = repository.fetchThreadFamily("child-0")
            assertEquals(301, family.size)
            assertTrue(repository.state.value.threads.any { it.id == "child-0" })
            assertEquals(257, repository.state.value.threads.size)
            repository.selectThread("child-0")
            shellSocket
                .await()
                .send(
                    """{"_tag":"Chunk","requestId":"$shellId","values":[{"kind":"snapshot","snapshot":{"projects":[{"id":"project","title":"Fixture project","workspaceRoot":"/fixture"}],"threads":[$thread],"archivedThreads":[]}}]}"""
                )
            familyShellAcknowledged.await()
            assertEquals("child-0", repository.state.value.selectedThreadId)
            assertTrue(repository.state.value.threads.any { it.id == "child-0" })
            assertEquals(257, repository.state.value.threads.size)
            repository.disconnect()
            assertTrue(repository.state.value.threads.none { it.id.startsWith("child-") })
        } finally {
            repository.disconnect()
            server.shutdown()
        }
    }

    @Test
    fun failedLaunchRecoversCreatedThreadAndAcceptedMessageOwnership() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        val accepted = java.util.concurrent.atomic.AtomicBoolean(false)
        val launchedId = java.util.concurrent.atomic.AtomicReference("")
        var messageId = ""
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path == "/api/auth/session" ->
                            MockResponse()
                                .setBody(
                                    """{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}"""
                                )
                        request.path == "/api/auth/websocket-ticket/rpc" ->
                            MockResponse().setBody("""{"ticket":"ticket"}""")
                        request.path?.startsWith("/ws?") == true ->
                            MockResponse()
                                .withWebSocketUpgrade(
                                    object : WebSocketListener() {
                                        override fun onMessage(webSocket: WebSocket, text: String) {
                                            val frame = Json.parseToJsonElement(text).jsonObject
                                            if (frame.string("_tag") != "Request") return
                                            val id = frame.string("id")
                                            when (frame.string("tag")) {
                                                "subscribeServerConfig" ->
                                                    webSocket.send(
                                                        """{"_tag":"Chunk","requestId":"$id","values":[{"type":"snapshot","config":{"providers":[]}}]}"""
                                                    )
                                                "orchestration.subscribeShell" ->
                                                    webSocket.send(
                                                        """{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[],"threads":[],"archivedThreads":[]}}]}"""
                                                    )
                                                "orchestration.getArchivedShellSnapshot" ->
                                                    webSocket.send(
                                                        """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"threads":[]}}}"""
                                                    )
                                                "orchestration.launchThread" -> {
                                                    launchedId.set(
                                                        frame.obj("payload").string("threadId")
                                                    )
                                                    messageId =
                                                        frame
                                                            .obj("payload")
                                                            .obj("initialMessage")
                                                            .string("messageId")
                                                    webSocket.send(
                                                        """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"message":"Launch response failed"}}]}}"""
                                                    )
                                                }
                                                "orchestration.getThreadProjection" -> {
                                                    val messages =
                                                        if (accepted.get())
                                                            """{"id":"$messageId"}"""
                                                        else ""
                                                    webSocket.send(
                                                        """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"thread":{"id":"${launchedId.get()}"},"messages":[$messages]}}}"""
                                                    )
                                                }
                                                "orchestration.subscribeThread" ->
                                                    webSocket.send(
                                                        """{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","projection":{"visibleTurnItems":[]}}]}"""
                                                    )
                                            }
                                        }
                                    }
                                )
                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
        val repository = T3Repository(this)
        try {
            repository.connect(
                SavedConnection(
                    "test",
                    server.url("/").toString().trimEnd('/'),
                    "access",
                    setOf("orchestration:read", "orchestration:operate"),
                    Long.MAX_VALUE,
                )
            )
            repository.state.first { it.connectionStatus == ConnectionStatus.Connected }
            for (alreadyAccepted in listOf(false, true)) {
                accepted.set(alreadyAccepted)
                val failure =
                    assertFailsWith<ThreadLaunchPartialFailure> {
                        repository.launchThread(
                            "project",
                            "Fixture",
                            ModelSelection("replay", "showcase"),
                            "go",
                        )
                    }
                assertEquals(launchedId.get(), failure.threadId)
                assertEquals(alreadyAccepted, failure.messageAccepted)
                assertEquals(failure.threadId, repository.state.value.selectedThreadId)
            }
        } finally {
            repository.disconnect()
            server.shutdown()
        }
    }

    private fun fakeSocket(request: Request, sendSucceeds: () -> Boolean = { true }): WebSocket =
        object : WebSocket {
            override fun request() = request

            override fun queueSize() = 0L

            override fun send(text: String) = sendSucceeds()

            override fun send(bytes: ByteString) = sendSucceeds()

            override fun close(code: Int, reason: String?) = true

            override fun cancel() = Unit
        }
}
