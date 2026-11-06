package com.example.teethree.data

import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun browserPdfLocation(raw: String, environment: String): String? = runCatching {
    val target = URI(raw)
    val base = URI(environment)
    fun port(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
    if (
        target.scheme !in setOf("http", "https") ||
            target.userInfo != null ||
            target.path.orEmpty().split('/').any { it == "." || it == ".." } ||
            !target.rawPath.orEmpty().startsWith("/api/assets/") ||
            !target.rawPath.endsWith(".pdf", true)
    )
        return@runCatching null
    val sameOrigin =
        target.scheme == base.scheme && target.host == base.host && port(target) == port(base)
    if (!sameOrigin && target.host !in setOf("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]"))
        return@runCatching null
    target.rawPath +
        target.rawQuery?.let { "?$it" }.orEmpty() +
        target.rawFragment?.let { "#$it" }.orEmpty()
}
    .getOrNull()

internal suspend fun downloadBrowserPdf(
    repository: T3Repository,
    relativeUrl: String,
    cache: File,
): File {
    var acquired: File? = null
    return try {
        withContext(Dispatchers.IO) {
            check(cache.isDirectory || cache.mkdirs()) { "Could not create the PDF cache." }
            val file = File.createTempFile("browser-", ".pdf", cache).also { acquired = it }
            try {
                repository.assetHttp(relativeUrl.substringBefore('#')).use { response ->
                    require(response.isSuccessful) { "The PDF download failed (${response.code})." }
                    checkNotNull(response.body).byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var size = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                require(size <= 50L * 1024 * 1024) {
                                    "The PDF exceeds the 50 MiB preview limit."
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                file
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
        }
    } catch (failure: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { acquired?.delete() }
        throw failure
    }
}
