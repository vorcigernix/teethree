package com.example.teethree.data

import java.net.InetAddress
import java.util.Collections
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionRoutesTest {
    private val lan = DirectConnectionRoute("http://192.168.1.42:3773/", "lan")
    private val tailnet = DirectConnectionRoute("http://100.101.102.103:3773/", "tailnet")

    private fun saved(base: String = "https://relay.invalid", cloud: Boolean = true) =
        SavedConnection(
            "saved",
            base,
            "private",
            emptySet(),
            Long.MAX_VALUE,
            environmentId = "environment",
            cloudAccountId = if (cloud) "account" else null,
        )

    private fun endpoints(vararg routes: DirectConnectionRoute) =
        JsonArray(
            routes.map {
                buildJsonObject {
                    put("kind", it.kind)
                    put("httpBaseUrl", it.httpBaseUrl)
                }
            }
        )

    @Test
    fun `learned addresses retain their order replace disappeared origins and preserve credential transport boundaries`() {
        val connection = saved()
        val reported =
            endpoints(
                lan,
                tailnet,
                DirectConnectionRoute("http://127.0.0.1:3773", "lan"),
                DirectConnectionRoute("https://user:password@evil.invalid", "lan"),
                DirectConnectionRoute("https://host.invalid/path?secret=one", "lan"),
            )
        assertEquals(listOf(lan, tailnet), mergeLearnedDirectRoutes(connection, reported))
        val moved = DirectConnectionRoute("http://10.0.0.42:3773/", "lan")
        assertEquals(
            listOf(moved, tailnet),
            mergeLearnedDirectRoutes(
                connection.copy(learnedRoutes = listOf(lan, tailnet)),
                endpoints(moved, tailnet),
            ),
        )
        assertEquals(
            listOf(tailnet, lan),
            mergeLearnedDirectRoutes(
                connection.copy(learnedRoutes = listOf(tailnet, lan)),
                endpoints(lan, tailnet),
            ),
        )
        assertEquals(
            listOf(tailnet),
            mergeLearnedDirectRoutes(saved(cloud = false), endpoints(lan, tailnet)),
        )
        assertTrue(
            mergeLearnedDirectRoutes(saved(cloud = false), endpoints(tailnet.copy(kind = "lan")))
                .isEmpty()
        )
        assertEquals(
            listOf(lan, tailnet),
            mergeLearnedDirectRoutes(saved("http://10.0.0.1:3773", false), endpoints(lan, tailnet)),
        )
        assertEquals(
            listOf(tailnet),
            mergeLearnedDirectRoutes(
                saved("http://localhost:3773", false),
                endpoints(lan, tailnet),
            ),
        )
        assertEquals("vpn", directRouteKind(tailnet.httpBaseUrl))
        assertEquals("public", directRouteKind("https://127.foo.0.0.1/"))
        assertEquals("tailnet", directRouteKind(tailnet.httpBaseUrl, "tailnet"))
        assertEquals("tailnet", directRouteKind("https://desk.tail1234.ts.net/"))
        assertEquals("tailnet", directRouteKind("http://[fd7a:115c:a1e0::1]:3773/"))
        val runtime = connection.copy(selectedTransport = lan, learnedRoutes = listOf(lan, tailnet))
        assertEquals(connection.baseUrl, runtime.baseUrl)
        assertEquals(lan.httpBaseUrl, runtime.effectiveHttpBaseUrl)
        assertEquals("ws://192.168.1.42:3773/ws", runtime.effectiveWsBaseUrl)
        assertEquals(listOf(lan, tailnet, null), orderedConnectionRoutes(runtime))
    }

    @Test
    fun `direct routes share one head start and slow answered routes precede dead ones after relay fails`() =
        runTest {
            val lanAnswer = CompletableDeferred<Boolean>()
            val tailnetAnswer = CompletableDeferred<Boolean>()
            val queue =
                ConnectionRouteAttempts(this, listOf(lan, tailnet, null)) { route ->
                    when (route) {
                        lan -> lanAnswer.await()
                        tailnet -> tailnetAnswer.await()
                        else -> true
                    }
                }
            try {
                val first = async { queue.next() }
                runCurrent()
                assertFalse(first.isCompleted)
                advanceTimeBy(500)
                runCurrent()
                assertNull(first.await()!!.transport)
                assertTrue(queue.hasMore)
                tailnetAnswer.complete(true)
                lanAnswer.complete(false)
                assertEquals(tailnet, queue.next()!!.transport)
                assertEquals(lan, queue.next()!!.transport)
                assertFalse(queue.hasMore)
                assertNull(queue.next())
            } finally {
                queue.close()
            }
        }

    @Test
    fun `probe fanout is bounded and cancelling route work releases only its own probes`() =
        runTest {
            var active = 0
            var maximum = 0
            val gate = CompletableDeferred<Boolean>()
            val routes = (1..12).map { DirectConnectionRoute("https://route-$it.invalid/", "lan") }
            val queue =
                ConnectionRouteAttempts(this, routes) {
                    active += 1
                    maximum = maxOf(maximum, active)
                    try {
                        gate.await()
                    } finally {
                        active -= 1
                    }
                }
            val waiting = async { queue.next() }
            runCurrent()
            assertEquals(4, maximum)
            queue.close()
            runCurrent()
            assertEquals(0, active)
            assertFailsWith<kotlinx.coroutines.CancellationException> { waiting.await() }
            val single =
                ConnectionRouteAttempts(this, listOf(null)) {
                    error("A sole route needs no speculative probe")
                }
            assertNull(single.next()!!.transport)
            assertNull(single.next())
            single.close()
        }

    @Test
    fun `repository falls back from a verified denied LAN to canonical transport without changing credential identity`() =
        runBlocking {
            val server = MockWebServer()
            val authHosts = Collections.synchronizedList(mutableListOf<String>())
            val canonicalDescriptors = java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val host = request.getHeader("Host")!!.substringBefore(':')
                        return when {
                            request.path == "/.well-known/t3/environment" -> {
                                assertNull(request.getHeader("Authorization"))
                                val id =
                                    if (
                                        host == "canonical.invalid" &&
                                            canonicalDescriptors.incrementAndGet() == 1
                                    )
                                        "not-ready"
                                    else "environment"
                                MockResponse()
                                    .setBody("""{"environmentId":"$id","label":"Fixture"}""")
                            }
                            request.path == "/api/auth/session" -> {
                                authHosts += host
                                assertEquals("Bearer private", request.getHeader("Authorization"))
                                if (host == "fixture.home.arpa") MockResponse().setResponseCode(403)
                                else
                                    MockResponse()
                                        .setBody(
                                            """{"authenticated":true,"permissions":["orchestration:read"]}"""
                                        )
                            }
                            request.path == "/api/auth/websocket-ticket/rpc" ->
                                MockResponse().setBody("""{"ticket":"fixture"}""")
                            request.path?.startsWith("/ws?") == true ->
                                MockResponse()
                                    .withWebSocketUpgrade(
                                        object : WebSocketListener() {
                                            override fun onMessage(
                                                webSocket: WebSocket,
                                                text: String,
                                            ) {
                                                val frame = Json.parseToJsonElement(text).jsonObject
                                                if (frame.string("_tag") != "Request") return
                                                val id = frame.string("id")
                                                when (frame.string("tag")) {
                                                    "subscribeServerConfig" ->
                                                        webSocket.send(
                                                            """{"_tag":"Chunk","requestId":"$id","values":[{"type":"snapshot","config":{"environment":{"environmentId":"environment"},"providers":[],"directEndpoints":[{"kind":"lan","httpBaseUrl":"http://fixture.home.arpa:${server.port}/"}]}}]}"""
                                                        )
                                                    "orchestration.subscribeShell" ->
                                                        webSocket.send(
                                                            """{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[],"threads":[],"archivedThreads":[]}}]}"""
                                                        )
                                                    "orchestration.getArchivedShellSnapshot" ->
                                                        webSocket.send(
                                                            """{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{"threads":[]}}}"""
                                                        )
                                                }
                                            }
                                        }
                                    )
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
            server.start()
            val http =
                OkHttpClient.Builder()
                    .proxy(java.net.Proxy.NO_PROXY)
                    .dns(Dns { listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) })
                    .build()
            val repository = T3Repository(this, http)
            try {
                val canonical = "http://canonical.invalid:${server.port}"
                val learned =
                    DirectConnectionRoute("http://fixture.home.arpa:${server.port}/", "lan")
                repository.connect(saved(canonical, false).copy(learnedRoutes = listOf(learned)))
                repository.state.first { it.shellReady }
                val routes = repository.learnedRoutes.first { it != null }!!
                assertEquals(listOf("fixture.home.arpa", "canonical.invalid"), authHosts.toList())
                assertEquals("saved", routes.connectionId)
                assertEquals(repository.connectionGeneration, routes.generation)
                assertEquals("environment", routes.environmentId)
                assertEquals(listOf(learned), routes.routes)
                assertEquals(canonical, repository.httpBaseUrl)
            } finally {
                repository.disconnect()
                server.shutdown()
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
            Unit
        }
}
