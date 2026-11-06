package com.example.teethree.data

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

internal suspend fun OkHttpClient.awaitResponse(request: Request): Response =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive)
                        continuation.resume(response) { _, value, _ -> value.close() }
                    else response.close()
                }
            }
        )
    }

internal data class EnvironmentRequestContext(val connection: SavedConnection, val generation: Long)

internal fun requireCurrentRequest(request: Request, current: EnvironmentRequestContext?) {
    val bound = request.tag(EnvironmentRequestContext::class.java) ?: return
    if (
        current == null ||
            bound.generation != current.generation ||
            bound.connection.id != current.connection.id ||
            bound.connection.cloudAccountId != current.connection.cloudAccountId ||
            bound.connection.baseUrl != current.connection.baseUrl ||
            bound.connection.effectiveHttpBaseUrl != current.connection.effectiveHttpBaseUrl ||
            bound.connection.effectiveWsBaseUrl != current.connection.effectiveWsBaseUrl
    )
        throw kotlinx.coroutines.CancellationException("Environment changed")
}

internal suspend fun authorizeBoundRequest(
    request: Request,
    current: () -> EnvironmentRequestContext?,
    authorize: suspend (SavedConnection, Request) -> Request,
): Request {
    val context = request.tag(EnvironmentRequestContext::class.java) ?: return request
    requireCurrentRequest(request, current())
    val signed = authorize(context.connection, request)
    requireCurrentRequest(request, current())
    return signed.newBuilder().tag(EnvironmentRequestContext::class.java, context).build()
}
