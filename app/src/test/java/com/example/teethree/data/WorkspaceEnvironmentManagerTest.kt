package com.example.teethree.data

import com.example.teethree.data.auth.CloudIdentity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceEnvironmentManagerTest {
    @Test
    fun backgroundShellsAreLiveWithoutTranscriptsOrEagerArchivesAndCloseOwnedResultsAfterStop() =
        runBlocking {
            ShellFixture().use { fixture ->
                val foreground = T3Repository(this)
                val manager =
                    WorkspaceEnvironmentManager(
                        this,
                        foreground,
                        { scope, archived -> T3Repository(scope, includeArchived = archived) },
                    )
                val release = CompletableDeferred<Unit>()
                try {
                    manager.update(listOf(fixture.connection()), null, null, true)
                    val shell =
                        manager.environments
                            .first { it.singleOrNull()?.phase == WorkspaceEnvironmentPhase.Live }
                            .single()
                    assertEquals("project", shell.projects.single().id)
                    assertEquals("thread", shell.threads.single().id)
                    assertTrue(shell.shellReady)
                    assertTrue("orchestration:read" in shell.scopes)
                    val entered = CompletableDeferred<Unit>()
                    val closed = AtomicInteger()
                    val operation = async {
                        manager.withRepository(shell.connectionId) { repository ->
                            assertNull(repository.state.value.selectedThreadId)
                            assertTrue(repository.state.value.timeline.isEmpty())
                            entered.complete(Unit)
                            release.await()
                            AutoCloseable { closed.incrementAndGet() }
                        }
                    }
                    entered.await()
                    assertFalse(
                        fixture.methods.any {
                            it.contains("subscribeThread") || it.contains("ArchivedShell")
                        }
                    )
                    manager.update(listOf(fixture.connection()), null, null, false)
                    manager.environments.first {
                        it.singleOrNull()?.phase != WorkspaceEnvironmentPhase.Live
                    }
                    release.complete(Unit)
                    assertFailsWith<CancellationException> { operation.await() }
                    assertEquals(1, closed.get())
                    assertTrue(manager.environments.value.single().scopes.isEmpty())
                } finally {
                    release.complete(Unit)
                    manager.close()
                    foreground.disconnect()
                }
            }
        }

    @Test
    fun foregroundSourceReadCannotOutliveReconnectToTheSameSavedConnection() = runBlocking {
        ShellFixture().use { fixture ->
            val foreground = T3Repository(this)
            val manager =
                WorkspaceEnvironmentManager(
                    this,
                    foreground,
                    { scope, archived -> T3Repository(scope, includeArchived = archived) },
                )
            val release = CompletableDeferred<Unit>()
            try {
                val saved = fixture.connection()
                manager.update(listOf(saved), saved.id, null, true)
                foreground.connect(saved)
                foreground.state.first { it.shellReady }
                val entered = CompletableDeferred<Unit>()
                val closed = AtomicInteger()
                val operation = async {
                    manager.withRepository(saved.id) {
                        entered.complete(Unit)
                        release.await()
                        AutoCloseable { closed.incrementAndGet() }
                    }
                }
                entered.await()
                foreground.connect(saved)
                release.complete(Unit)
                assertFailsWith<CancellationException> { operation.await() }
                assertEquals(1, closed.get())
            } finally {
                release.complete(Unit)
                manager.close()
                foreground.disconnect()
            }
        }
    }

    private class ShellFixture : AutoCloseable {
        val methods = CopyOnWriteArrayList<String>()
        private val server = MockWebServer()

        init {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.path == "/api/auth/session" ->
                                MockResponse()
                                    .setBody(
                                        """{"authenticated":true,"permissions":["orchestration:read"]}"""
                                    )
                            request.path == "/api/auth/websocket-ticket/rpc" ->
                                MockResponse().setBody("""{"ticket":"ticket"}""")
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
                                                val method = frame.string("tag")
                                                methods += method
                                                when (method) {
                                                    "subscribeServerConfig" ->
                                                        webSocket.send(
                                                            """{"_tag":"Chunk","requestId":"$id","values":[{"type":"snapshot","config":{"providers":[]}}]}"""
                                                        )
                                                    "orchestration.subscribeShell" ->
                                                        webSocket.send(
                                                            """{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[{"id":"project","title":"Fixture","workspaceRoot":"/fixture"}],"threads":[{"id":"thread","projectId":"project","title":"Fixture thread","status":"idle","modelSelection":{"instanceId":"replay","model":"showcase"},"updatedAt":"2026-11-05T00:00:00Z"}],"archivedThreads":[]}}]}"""
                                                        )
                                                    else ->
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
            server.start()
        }

        fun connection() =
            SavedConnection(
                "fixture",
                server.url("/").toString().trimEnd('/'),
                "token",
                emptySet(),
                Long.MAX_VALUE,
            )

        override fun close() = server.close()
    }

    private fun connection(id: String, environment: String = id, cloud: String? = null) =
        SavedConnection(
            id,
            "https://$id.invalid",
            "token",
            emptySet(),
            Long.MAX_VALUE,
            environmentId = environment,
            cloudAccountId = cloud,
        )

    @Test
    fun failedSourceLeaseReachesWaiterAfterRemoval() = runTest {
        val foreground = T3Repository(backgroundScope)
        val manager =
            WorkspaceEnvironmentManager(
                this,
                foreground,
                { _, _ -> throw java.io.IOException("Unavailable fixture") },
            )
        try {
            manager.update(listOf(connection("failed")), null, null, true)
            val result = async {
                try {
                    manager.withRepository("failed") { error("No repository was opened") }
                    null
                } catch (failure: java.io.IOException) {
                    failure
                }
            }
            runCurrent()
            assertTrue(
                result.isCompleted,
                "A completed lease failure must not leave its read waiting forever.",
            )
            assertEquals("Unavailable fixture", result.await()?.message)
        } finally {
            manager.close()
            foreground.disconnect()
        }
    }

    @Test
    fun `a pinned read of a hidden alias preempts unpinned background work without exceeding two sessions`() =
        runTest {
            val foreground = T3Repository(backgroundScope)
            val started = Channel<String>(Channel.UNLIMITED)
            val gate = CompletableDeferred<Unit>()
            val created = AtomicInteger()
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val archived = mutableListOf<Boolean>()
            val manager =
                WorkspaceEnvironmentManager(
                    this,
                    foreground,
                    { scope, includeArchived ->
                        created.incrementAndGet()
                        archived += includeArchived
                        T3Repository(
                            scope,
                            beforeHttpAccess = { url ->
                                val count = active.incrementAndGet()
                                maximum.updateAndGet { maxOf(it, count) }
                                try {
                                    started.send(url)
                                    gate.await()
                                } finally {
                                    active.decrementAndGet()
                                }
                            },
                            includeArchived = includeArchived,
                        )
                    },
                )
            try {
                manager.update(
                    listOf(
                        connection("a", "same-environment"),
                        connection("b"),
                        connection("alias", "same-environment"),
                    ),
                    null,
                    null,
                    true,
                )
                started.receive()
                started.receive()
                assertEquals(2, created.get())
                val previous = manager.reconciliations.value
                val request = async {
                    manager.withRepository("alias") {
                        error("This fixture never finishes authenticating")
                    }
                }
                manager.reconciliations.first { it > previous }
                runCurrent()
                assertEquals(
                    3,
                    created.get(),
                    "The explicit alias read must take an idle background slot immediately.",
                )
                assertEquals("https://alias.invalid", started.receive())
                assertEquals(2, maximum.get())
                assertTrue(archived.all { !it })
                manager.update(
                    listOf(
                        connection("a", "same-environment"),
                        connection("b"),
                        connection("alias", "same-environment"),
                    ),
                    null,
                    null,
                    false,
                )
                runCurrent()
                assertFailsWith<CancellationException> { request.await() }
            } finally {
                manager.close()
                foreground.disconnect()
            }
        }

    @Test
    fun `an account change rejects a late uninterruptible cache read and a pending source lease`() =
        runTest {
            val foreground = T3Repository(backgroundScope)
            val cacheEntered = CompletableDeferred<Unit>()
            val cached = CompletableDeferred<CachedEnvironment>()
            val callback = AtomicInteger()
            val manager =
                WorkspaceEnvironmentManager(
                    this,
                    foreground,
                    { scope, archived ->
                        T3Repository(
                            scope,
                            beforeHttpAccess = {
                                error("An obsolete account must never send HTTP")
                            },
                            includeArchived = archived,
                        )
                    },
                    loadCache = {
                        cacheEntered.complete(Unit)
                        withContext(NonCancellable) { cached.await() }
                    },
                )
            try {
                val saved = connection("cloud", cloud = "old-account")
                manager.update(
                    listOf(saved),
                    null,
                    CloudIdentity("old-account", "old-session"),
                    true,
                )
                cacheEntered.await()
                val source = async {
                    manager.withRepository(saved.id) { callback.incrementAndGet() }
                }
                runCurrent()
                manager.update(
                    listOf(saved),
                    null,
                    CloudIdentity("other-account", "other-session"),
                    true,
                )
                cached.complete(
                    CachedEnvironment(
                        projects = listOf(T3Project("private", "Old account", "/private"))
                    )
                )
                runCurrent()
                assertFailsWith<CancellationException> { source.await() }
                assertTrue(manager.environments.value.isEmpty())
                assertEquals(0, callback.get())
            } finally {
                manager.close()
                foreground.disconnect()
                cached.complete(CachedEnvironment())
            }
        }
}
