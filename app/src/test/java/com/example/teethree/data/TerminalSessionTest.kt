package com.example.teethree.data

import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {
    @Test
    fun `slow renderer receives every output chunk and resets across attachment generations`() {
        val output = TerminalOutputJournal()
        output.append("old history", reset = true)
        val first = requireNotNull(output.since(-1))
        assertTrue(first.reset)
        output.append("one")
        output.append("two")
        val next = requireNotNull(output.since(first.version))
        assertFalse(next.reset)
        assertEquals("onetwo", next.bytes.toString(Charsets.UTF_8))
        output.append("new history", reset = true)
        output.append("three")
        val reattached = requireNotNull(output.since(next.version))
        assertTrue(reattached.reset)
        assertEquals("new historythree", reattached.bytes.toString(Charsets.UTF_8))
        assertNull(output.since(reattached.version))
    }

    @Test
    fun `bounded journal recovers a renderer that misses the retained chunk window`() {
        val output = TerminalOutputJournal()
        output.append("", reset = true)
        val initial = output.version
        repeat(300) { output.append("$it ") }
        val recovered = requireNotNull(output.since(initial))
        assertTrue(recovered.reset)
        assertEquals(
            (0 until 300).joinToString("") { "$it " },
            recovered.bytes.toString(Charsets.UTF_8),
        )
        output.append("x".repeat(600_000))
        assertEquals(512 * 1024, requireNotNull(output.since(-1)).bytes.size)
    }

    @Test
    fun `terminal input chunks preserve unicode and paste strips executable control bytes`() {
        val text = "a".repeat(65_535) + "😀" + "b".repeat(70_000)
        val chunks = terminalWriteChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 65_536 && !it.last().isHighSurrogate() })
        assertEquals(
            "first\rsecond\rthird  [31m\tend",
            encodeTerminalPaste("first\r\nsecond\nthird\u0003\u001b[31m\tend"),
        )
    }

    @Test
    fun projectActionUsesFreshWorktreeTerminalAndDoesNotReplayAfterReattachment() = runTest {
        val project = T3Project("project", "Project", "/project")
        val thread =
            T3Thread(
                "thread",
                project.id,
                "Thread",
                "idle",
                ModelSelection("replay", "model"),
                null,
                false,
                "",
                "now",
                worktreePath = "/worktree",
            )
        val action = ProjectAction("script", "Build", "build\ncheck")
        val launch = projectActionLaunch(project, thread, action)
        assertFalse(launch.terminalId == projectActionLaunch(project, thread, action).terminalId)
        assertEquals("/worktree", launch.cwd)
        assertEquals("/project", launch.env.string("T3CODE_PROJECT_ROOT"))
        assertEquals("/worktree", launch.env.string("T3CODE_WORKTREE_PATH"))
        assertEquals(" build\ncheck\r", launch.initialInput)
        val frames = mutableListOf<JsonObject>()
        val environment =
            MutableStateFlow(
                RepositoryState(
                    connectionStatus = ConnectionStatus.Connected,
                    scopes = setOf("terminal:operate", "terminal:read"),
                )
            )
        val transport =
            EffectRpcClient(backgroundScope, OkHttpClient()) { request, listener ->
                actionSocket(request, listener, frames)
            }
        transport.connect("http://localhost/ws")
        val session =
            TerminalSession(
                backgroundScope,
                environment,
                { transport },
                thread.id,
                launch.terminalId,
                project.workspaceRoot,
                thread.worktreePath,
            )
        try {
            assertEquals(launch.terminalId, session.launchProjectAction(launch))
            session.start()
            runCurrent()
            environment.value =
                environment.value.copy(connectionStatus = ConnectionStatus.Disconnected)
            runCurrent()
            environment.value =
                environment.value.copy(connectionStatus = ConnectionStatus.Connected)
            runCurrent()
            val requests = frames.filter { it.string("_tag") == "Request" }
            val open = requests.single { it.string("tag") == "terminal.open" }.obj("payload")
            assertEquals(launch.terminalId, open.string("terminalId"))
            assertEquals(launch.env, open.obj("env"))
            assertEquals("/worktree", open.string("cwd"))
            val writes = requests.filter { it.string("tag") == "terminal.write" }
            assertEquals(1, writes.size)
            assertEquals(launch.initialInput, writes.single().obj("payload").string("data"))
            val attachments = requests.filter { it.string("tag") == "terminal.attach" }
            assertEquals(2, attachments.size)
            assertTrue(attachments.last().obj("payload").bool("resume"))
            assertTrue(
                attachments.all {
                    !it.obj("payload").containsKey("initialInput") &&
                        it.obj("payload").obj("env") == launch.env
                }
            )
        } finally {
            session.close()
            transport.close()
        }
    }

    @Test
    fun projectActionNeverWritesAfterItsEnvironmentConnectionChanges() = runTest {
        val frames = mutableListOf<JsonObject>()
        val environment =
            MutableStateFlow(
                RepositoryState(
                    connectionStatus = ConnectionStatus.Connected,
                    scopes = setOf("terminal:operate"),
                )
            )
        var current: EffectRpcClient? = null
        val transport =
            EffectRpcClient(backgroundScope, OkHttpClient()) { request, listener ->
                actionSocket(request, listener, frames) { method ->
                    if (method == "terminal.open") current = null
                }
            }
        current = transport
        transport.connect("http://localhost/ws")
        val session =
            TerminalSession(
                backgroundScope,
                environment,
                { current },
                "thread",
                "unused",
                "/project",
                null,
            )
        val launch =
            ProjectActionLaunch(
                "thread",
                "action-fixture",
                "/project",
                null,
                projectTerminalEnv("/project", null),
                " run\r",
            )
        try {
            assertFailsWith<CancellationException> { session.launchProjectAction(launch) }
            assertFalse(frames.any { it.string("tag") == "terminal.write" })
            assertFalse(launch.env.containsKey("T3CODE_WORKTREE_PATH"))
        } finally {
            session.close()
            transport.close()
        }
    }

    private fun actionSocket(
        request: Request,
        listener: WebSocketListener,
        frames: MutableList<JsonObject>,
        beforeReply: (String) -> Unit = {},
    ): WebSocket {
        val socket =
            object : WebSocket {
                override fun request() = request

                override fun queueSize() = 0L

                override fun send(bytes: ByteString) = true

                override fun close(code: Int, reason: String?) = true

                override fun cancel() = Unit

                override fun send(text: String): Boolean {
                    val frame = Json.parseToJsonElement(text).jsonObject
                    frames += frame
                    if (frame.string("_tag") != "Request") return true
                    val id = frame.string("id")
                    val method = frame.string("tag")
                    beforeReply(method)
                    val value =
                        when (method) {
                            "terminal.open" -> buildJsonObject { put("status", "running") }
                            "terminal.attach" ->
                                buildJsonObject {
                                    put("type", "snapshot")
                                    put(
                                        "snapshot",
                                        buildJsonObject {
                                            put("status", "running")
                                            put("history", "")
                                            put("sequence", 0)
                                        },
                                    )
                                }
                            "subscribeTerminalMetadata" ->
                                buildJsonObject {
                                    put("type", "snapshot")
                                    put("terminals", JsonArray(emptyList()))
                                }
                            else -> JsonNull
                        }
                    val response =
                        if (method == "terminal.attach" || method == "subscribeTerminalMetadata")
                            buildJsonObject {
                                put("_tag", "Chunk")
                                put("requestId", id)
                                put("values", JsonArray(listOf(value)))
                            }
                        else
                            buildJsonObject {
                                put("_tag", "Exit")
                                put("requestId", id)
                                put(
                                    "exit",
                                    buildJsonObject {
                                        put("_tag", "Success")
                                        put("value", value)
                                    },
                                )
                            }
                    listener.onMessage(this, response.toString())
                    return true
                }
            }
        listener.onOpen(
            socket,
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build(),
        )
        return socket
    }
}
