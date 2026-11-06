package com.example.teethree.data.auth

import com.example.teethree.data.EnvironmentHttpException
import com.example.teethree.data.SavedConnection
import com.example.teethree.data.effectiveHttpBaseUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

class DirectEnvironmentVerifier(
    client: OkHttpClient,
    private val beforeHttpAccess: suspend (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Key(
        val connectionId: String,
        val descriptorUrl: String,
        val environmentId: String,
    )

    private val http =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    private val lock = Mutex()
    private val verified = LinkedHashMap<Key, Long>()

    suspend fun clear() = lock.withLock { verified.clear() }

    suspend fun verify(connection: SavedConnection, force: Boolean = false) {
        val expected = connection.environmentId?.takeIf(String::isNotBlank) ?: return
        val url = endpointUrl(connection.effectiveHttpBaseUrl, "/.well-known/t3/environment")
        val key = Key(connection.id, url.toString(), expected)
        lock.withLock {
            val checkedAt = verified[key]
            val clock = now()
            if (!force && checkedAt != null && clock >= checkedAt && clock - checkedAt < 60_000)
                return
            verified.remove(key)
            beforeHttpAccess(connection.effectiveHttpBaseUrl)
            val descriptor =
                try {
                    http
                        .cloudJson(
                            Request.Builder()
                                .url(url)
                                .get()
                                .cacheControl(CacheControl.Builder().noCache().noStore().build())
                                .build()
                        )
                        .requiredObject()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: CloudHttpException) {
                    throw EnvironmentHttpException(
                        error.status,
                        "The environment did not provide its identity (HTTP ${error.status}).",
                    )
                } catch (error: Exception) {
                    throw IOException(
                        "The environment identity could not be verified. Check the address and try again."
                    )
                }
            val actual =
                (descriptor["environmentId"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.takeIf(String::isNotBlank)
                    ?: throw IOException("The environment returned an invalid identity response.")
            if (actual != expected)
                throw EnvironmentHttpException(
                    403,
                    "This address belongs to a different environment. Update the saved connection before reconnecting.",
                )
            verified[key] = now()
            while (verified.size > 64) verified.remove(verified.keys.first())
        }
    }

    suspend fun probe(connection: SavedConnection): Boolean {
        val expected = connection.environmentId ?: return false
        return try {
            withTimeout(2_500) {
                beforeHttpAccess(connection.effectiveHttpBaseUrl)
                val url =
                    endpointUrl(connection.effectiveHttpBaseUrl, "/.well-known/t3/environment")
                val descriptor =
                    http
                        .cloudJson(
                            Request.Builder()
                                .url(url)
                                .get()
                                .cacheControl(CacheControl.Builder().noCache().noStore().build())
                                .build()
                        )
                        .requiredObject()
                (descriptor["environmentId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ==
                    expected
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }
    }
}
