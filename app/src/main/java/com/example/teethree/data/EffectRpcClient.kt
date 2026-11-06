package com.example.teethree.data

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class EffectRpcClient(
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val openSocket: (Request, WebSocketListener) -> WebSocket = http::newWebSocket,
) {
    private data class Pending(
        val result: CompletableDeferred<JsonElement>? = null,
        val onValue: ((JsonElement) -> Unit)? = null,
        val onError: ((Throwable) -> Unit)? = null,
    )

    private val requests = ConcurrentHashMap<String, Pending>()
    private val nextId = AtomicLong()
    private val opened = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Throwable>()
    private val incoming = Channel<String>(64)
    private var socket: WebSocket? = null
    private var reader: Job? = null
    private var pinger: Job? = null
    @Volatile private var lastFrameAt = System.nanoTime()

    suspend fun connect(url: String) {
        reader = scope.launch {
            try {
                for (text in incoming) {
                    val decoded = Json.parseToJsonElement(text)
                    val frames = if (decoded is JsonArray) decoded else listOf(decoded)
                    for (frame in frames) receive(frame.jsonObject)
                }
            } catch (error: Exception) {
                if (error !is CancellationException)
                    fail(IOException("Invalid server response", error))
            }
        }
        socket =
            openSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        lastFrameAt = System.nanoTime()
                        opened.complete(Unit)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        lastFrameAt = System.nanoTime()
                        if (!incoming.trySend(text).isSuccess)
                            fail(IOException("Server response queue overflow"))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        fail(IOException("Server connection closed ($code)"))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                        fail(IOException("Server connection closed ($code)"))
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        val status = response?.code
                        fail(
                            if (status != null && status in 400..499)
                                EnvironmentHttpException(
                                    status,
                                    "The environment rejected this client (HTTP $status).",
                                )
                            else IOException("Could not connect to the environment", t)
                        )
                    }
                },
            )
        try {
            withTimeout(20_000) { opened.await() }
        } catch (error: TimeoutCancellationException) {
            close()
            currentCoroutineContext().ensureActive()
            throw IOException("The environment connection timed out.", error)
        } catch (error: Exception) {
            close()
            throw error
        }
        pinger = scope.launch {
            while (isActive) {
                delay(5_000)
                if (System.nanoTime() - lastFrameAt > 15_000_000_000L) {
                    fail(IOException("Environment stopped responding"))
                    break
                }
                try {
                    send(buildJsonObject { put("_tag", "Ping") })
                } catch (error: IOException) {
                    fail(error)
                    break
                }
            }
        }
    }

    suspend fun call(method: String, payload: JsonObject): JsonElement {
        val result = CompletableDeferred<JsonElement>()
        val id = register(method, payload, Pending(result = result))
        try {
            if (method == "sourceControl.signInWait") return result.await()
            return withTimeout(30_000) { result.await() }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IOException("The environment request timed out.", error)
        } finally {
            if (requests.remove(id) != null) interrupt(id)
        }
    }

    fun subscribe(
        method: String,
        payload: JsonObject,
        onError: ((Throwable) -> Unit)? = null,
        onValue: (JsonObject) -> Unit,
    ): String = subscribeValue(method, payload, onError) { onValue(it.jsonObject) }

    fun subscribeValue(
        method: String,
        payload: JsonObject,
        onError: ((Throwable) -> Unit)? = null,
        onValue: (JsonElement) -> Unit,
    ): String = register(method, payload, Pending(onValue = onValue, onError = onError))

    fun unsubscribe(id: String) {
        if (requests.remove(id) != null) interrupt(id)
    }

    suspend fun awaitClosed(): Throwable = closed.await()

    fun close() {
        fail(IOException("Disconnected"))
    }

    private fun register(method: String, payload: JsonObject, pending: Pending): String {
        val id =
            synchronized(requests) {
                check(!closed.isCompleted && opened.isCompleted) { "Environment is disconnected" }
                check(requests.size < 64) { "Too many requests are in progress" }
                nextId.incrementAndGet().toString().also { requests[it] = pending }
            }
        try {
            send(
                buildJsonObject {
                    put("_tag", "Request")
                    put("id", id)
                    put("tag", method)
                    put("payload", payload)
                    put("headers", JsonArray(emptyList()))
                }
            )
        } catch (error: Exception) {
            requests.remove(id)
            throw error
        }
        return id
    }

    private fun receive(frame: JsonObject) {
        val id = frame.string("requestId")
        when (frame.string("_tag")) {
            "Chunk" -> {
                val callback = requests[id]?.onValue
                if (callback != null) {
                    for (value in frame.array("values")) callback(value)
                    send(
                        buildJsonObject {
                            put("_tag", "Ack")
                            put("requestId", id)
                        }
                    )
                }
            }
            "Exit" -> {
                val request = requests.remove(id) ?: return
                val exit = frame.obj("exit")
                if (exit.string("_tag") == "Success") {
                    request.result?.complete(exit["value"] ?: JsonNull)
                    if (request.onValue != null) {
                        val error = IOException("Environment subscription ended")
                        request.onError?.invoke(error) ?: fail(error)
                    }
                } else {
                    val authorization =
                        exit.array("cause").any { raw ->
                            (raw as? JsonObject)?.obj("error")?.string("_tag") ==
                                "EnvironmentAuthorizationError"
                        }
                    val error =
                        if (authorization) EnvironmentHttpException(403, rpcFailureMessage(exit))
                        else IOException(rpcFailureMessage(exit))
                    request.result?.completeExceptionally(error)
                    if (request.onValue != null) request.onError?.invoke(error) ?: fail(error)
                }
            }
            "Defect",
            "ClientProtocolError" -> fail(IOException("Environment protocol error"))
            "Ping" -> send(buildJsonObject { put("_tag", "Pong") })
        }
    }

    private fun interrupt(id: String) {
        if (closed.isCompleted) return
        runCatching {
            send(
                buildJsonObject {
                    put("_tag", "Interrupt")
                    put("requestId", id)
                }
            )
        }
    }

    private fun send(frame: JsonObject) {
        if (socket?.send(frame.toString()) != true) throw IOException("Environment is disconnected")
    }

    private fun fail(error: Throwable) {
        if (!closed.complete(error)) return
        opened.completeExceptionally(error)
        socket?.cancel()
        incoming.close()
        pinger?.cancel()
        reader?.cancel()
        val pending = synchronized(requests) { requests.values.toList().also { requests.clear() } }
        for (request in pending) {
            request.result?.completeExceptionally(error)
            request.onError?.invoke(error)
        }
    }
}

internal fun rpcFailureMessage(exit: JsonObject): String {
    val failures = exit.array("cause")
    return failures.firstNotNullOfOrNull { cause ->
        val error = (cause as? JsonObject)?.get("error") as? JsonObject
        error?.nullableString("message")
            ?: error?.nullableString("requiredScope")?.let {
                "This connection requires the $it permission."
            }
    } ?: "The environment rejected this request."
}

internal fun JsonObject.string(key: String, fallback: String = ""): String =
    (this[key] as? JsonPrimitive)?.content?.takeUnless { it == "null" } ?: fallback

internal fun JsonObject.nullableString(key: String): String? =
    string(key).takeIf { it.isNotEmpty() }

internal fun JsonObject.obj(key: String): JsonObject =
    this[key] as? JsonObject ?: JsonObject(emptyMap())

internal fun JsonObject.array(key: String): JsonArray =
    this[key] as? JsonArray ?: JsonArray(emptyList())

internal fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean =
    string(key).toBooleanStrictOrNull() ?: fallback

internal fun JsonObject.long(key: String, fallback: Long = 0L): Long =
    string(key).toLongOrNull() ?: fallback
