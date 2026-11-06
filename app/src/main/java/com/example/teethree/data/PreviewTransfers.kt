package com.example.teethree.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

internal fun browserMediaLocation(
    origin: String,
    raw: String,
    tab: BrowserPreviewTab,
    upload: Boolean,
): Pair<String, Map<String, String>> {
    val base = origin.toHttpUrl()
    val target = checkNotNull(base.resolve(raw)) { "The browser returned an invalid transfer URL." }
    require(
        target.scheme == base.scheme &&
            target.host == base.host &&
            target.port == base.port &&
            target.username.isEmpty() &&
            target.password.isEmpty()
    ) {
        "Browser transfers must stay in the connected environment."
    }
    val path = if (upload) "/api/preview-stream/upload" else "/api/preview-stream/download"
    require(
        target.encodedPath == path &&
            target.queryParameter("threadId") == tab.threadId &&
            target.queryParameter("tabId") == tab.id
    ) {
        "The transfer does not belong to this browser tab."
    }
    val names = setOf("threadId", "tabId", if (upload) "chooser" else "id")
    val query = names.associateWith { key ->
        val values = target.queryParameterValues(key)
        require(values.size == 1 && !values.first().isNullOrBlank()) {
            "The browser returned an invalid transfer target."
        }
        checkNotNull(values.first())
    }
    return path to query
}

class PreviewTransfers(
    private val repository: T3Repository,
    private val resolver: ContentResolver,
) {
    suspend fun upload(tab: BrowserPreviewTab, chooser: JsonObject, files: List<Uri>) =
        withContext(Dispatchers.IO) {
            require(files.size <= 32 && (chooser.bool("multiple") || files.size <= 1)) {
                "The page does not accept this many files."
            }
            val location =
                browserMediaLocation(
                    checkNotNull(repository.httpBaseUrl),
                    chooser.string("uploadUrl"),
                    tab,
                    true,
                )
            val task = currentCoroutineContext()
            var total = 0L
            val body =
                if (files.isEmpty()) {
                    val boundary = UUID.randomUUID().toString()
                    "--$boundary--\r\n"
                        .toRequestBody("multipart/form-data; boundary=$boundary".toMediaType())
                } else
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .apply {
                            files.forEach { uri ->
                                val name =
                                    resolver
                                        .query(
                                            uri,
                                            arrayOf(OpenableColumns.DISPLAY_NAME),
                                            null,
                                            null,
                                            null,
                                        )
                                        ?.use { cursor ->
                                            if (cursor.moveToFirst()) cursor.getString(0) else null
                                        }
                                        ?.substringAfterLast('/')
                                        ?.take(200)
                                        ?.ifBlank { null } ?: "upload"
                                val mime =
                                    (resolver.getType(uri) ?: "application/octet-stream")
                                        .toMediaType()
                                addFormDataPart(
                                    "file",
                                    name,
                                    object : RequestBody() {
                                        override fun contentType() = mime

                                        override fun isOneShot() = true

                                        override fun writeTo(sink: BufferedSink) {
                                            checkNotNull(resolver.openInputStream(uri)) {
                                                    "The selected file could not be opened."
                                                }
                                                .use { input ->
                                                    val buffer = ByteArray(8192)
                                                    var size = 0L
                                                    while (true) {
                                                        task.ensureActive()
                                                        val count = input.read(buffer)
                                                        if (count < 0) break
                                                        size += count
                                                        total += count
                                                        require(
                                                            size <= 50L * 1024 * 1024 &&
                                                                total <= 100L * 1024 * 1024
                                                        ) {
                                                            "Files must be at most 50 MiB each and 100 MiB together."
                                                        }
                                                        sink.write(buffer, 0, count)
                                                    }
                                                }
                                        }
                                    },
                                )
                            }
                        }
                        .build()
            repository.previewHttp(location.first, location.second, "POST", body).use { response ->
                require(response.isSuccessful) { "The page refused the upload (${response.code})." }
            }
        }

    suspend fun download(tab: BrowserPreviewTab, download: JsonObject, destination: Uri) =
        withContext(Dispatchers.IO) {
            require(download.long("sizeBytes") <= 50L * 1024 * 1024) {
                "The download exceeds the 50 MiB limit."
            }
            val location =
                browserMediaLocation(
                    checkNotNull(repository.httpBaseUrl),
                    download.string("url"),
                    tab,
                    false,
                )
            repository.previewHttp(location.first, location.second).use { response ->
                require(response.isSuccessful) { "The page download failed (${response.code})." }
                checkNotNull(response.body).byteStream().use { input ->
                    checkNotNull(resolver.openOutputStream(destination)).use { output ->
                        val buffer = ByteArray(8192)
                        var size = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= 50L * 1024 * 1024) {
                                "The download exceeds the 50 MiB limit."
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        }
}
