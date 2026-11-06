package com.example.teethree.data.auth

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal suspend fun OkHttpClient.cloudJson(request: Request): JsonElement =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive)
                        continuation.resumeWithException(IOException("Could not reach T3 Connect."))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val source = it.body?.source()
                            if (source?.request(1_048_577) == true) invalidCloudResponse()
                            val body = source?.readUtf8().orEmpty()
                            val parsed = runCatching {
                                Json.parseToJsonElement(body.ifBlank { "null" })
                            }.getOrNull()
                            if (!it.isSuccessful) {
                                val fields = parsed as? JsonObject
                                val reason =
                                    fields?.optionalString("reason")?.takeIf { reason ->
                                        reason in
                                            setOf(
                                                "tunnel_released",
                                                "origin_unreachable",
                                                "invalid_bearer",
                                                "invalid_dpop",
                                                "not_authorized",
                                                "missing_bearer",
                                            )
                                    }
                                val proofReason =
                                    fields?.optionalString("dpopFailureReason")?.takeIf { reason ->
                                        reason in
                                            setOf(
                                                "time_window",
                                                "key_mismatch",
                                                "request_mismatch",
                                                "token_mismatch",
                                                "replay",
                                                "invalid_proof",
                                            )
                                    }
                                throw CloudHttpException(it.code, reason, proofReason)
                            }
                            parsed ?: invalidCloudResponse()
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive)
                            continuation.resumeWithException(
                                if (error is CloudHttpException) error
                                else IOException("T3 Connect returned an invalid response.")
                            )
                    }
                }
            }
        )
    }
