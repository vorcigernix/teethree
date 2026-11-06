package com.example.teethree.data

import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.Request

class HttpRequestsTest {
    @Test
    fun `authorization cannot enqueue a request after switching the same origin account`() =
        runBlocking {
            val old =
                SavedConnection(
                    "same-host",
                    "https://example.com",
                    "old-token",
                    emptySet(),
                    Long.MAX_VALUE,
                    cloudAccountId = "old-account",
                )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var current: EnvironmentRequestContext? = EnvironmentRequestContext(old, 1)
            val request =
                Request.Builder()
                    .url("https://example.com/api/pull-requests/diff")
                    .tag(EnvironmentRequestContext::class.java, current)
                    .post(okhttp3.RequestBody.create(null, "payload"))
                    .build()
            var enqueued = false
            val result = async {
                val signed =
                    authorizeBoundRequest(request, { current }) { connection, raw ->
                        assertEquals("old-account", connection.cloudAccountId)
                        entered.complete(Unit)
                        release.await()
                        raw.newBuilder().header("Authorization", "Bearer signed").build()
                    }
                requireCurrentRequest(signed, current)
                enqueued = true
            }
            entered.await()
            current = EnvironmentRequestContext(old.copy(cloudAccountId = "new-account"), 2)
            release.complete(Unit)
            assertFailsWith<CancellationException> { result.await() }
            assertFalse(enqueued)
            assertFailsWith<CancellationException> { requireCurrentRequest(request, null) }
            assertFailsWith<CancellationException> {
                requireCurrentRequest(
                    request,
                    EnvironmentRequestContext(old.copy(cloudAccountId = "other-account"), 1),
                )
            }
            assertFailsWith<CancellationException> {
                requireCurrentRequest(
                    request,
                    EnvironmentRequestContext(
                        old.copy(baseUrl = "https://different.example.com"),
                        1,
                    ),
                )
            }
            assertFailsWith<CancellationException> {
                requireCurrentRequest(
                    request,
                    EnvironmentRequestContext(
                        old.copy(
                            selectedTransport =
                                DirectConnectionRoute("https://tailnet.example.com", "tailnet")
                        ),
                        1,
                    ),
                )
            }
            current = EnvironmentRequestContext(old, 1)
            val responseStarted = CompletableDeferred<Unit>()
            val responseReleased = CompletableDeferred<Unit>()
            val accepted = authorizeBoundRequest(request, { current }) { _, raw -> raw }
            var publishedResponse = false
            val response = async {
                responseStarted.complete(Unit)
                responseReleased.await()
                requireCurrentRequest(accepted, current)
                publishedResponse = true
            }
            responseStarted.await()
            current =
                EnvironmentRequestContext(
                    old.copy(
                        selectedTransport =
                            DirectConnectionRoute("https://tailnet.example.com", "tailnet")
                    ),
                    1,
                )
            responseReleased.complete(Unit)
            assertFailsWith<CancellationException> { response.await() }
            assertFalse(publishedResponse)
            Unit
        }
}
