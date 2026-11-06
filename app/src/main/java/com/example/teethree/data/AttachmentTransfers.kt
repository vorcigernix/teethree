package com.example.teethree.data

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response

@Serializable
data class ChatAttachment(
    val id: String,
    val type: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
    fun wire(): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        put("name", name)
        put("mimeType", mimeType)
        put("sizeBytes", sizeBytes)
    }
}

@Serializable
data class ComposerDraft(
    val text: String = "",
    val attachments: List<ChatAttachment> = emptyList(),
    val importedShareIds: Set<String> = emptySet(),
    val context: JsonObject? = null,
    val pendingDispatch: PendingMessageDispatch? = null,
)

class AttachmentTransfers(private val repository: T3Repository) {
    private val http =
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    suspend fun upload(
        file: File,
        name: String,
        mimeType: String,
        expectedConnectionId: String? = null,
    ): ChatAttachment {
        val connection =
            checkNotNull(repository.connectionId) { "Connect to an environment first." }
        check(expectedConnectionId == null || connection == expectedConnectionId) {
            "The active environment changed. Attach the file again."
        }
        val origin = checkNotNull(repository.httpBaseUrl)
        val image = mimeType in setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        val size = file.length()
        require(size in 1..(if (image) 10L else 50L) * 1024 * 1024) {
            if (image) "Images must be between 1 byte and 10 MiB."
            else "Files must be between 1 byte and 50 MiB."
        }
        val target =
            repository
                .callTool(
                    "attachments.createUploadUrl",
                    buildJsonObject {
                        put("type", if (image) "image" else "file")
                        put("name", name)
                        put("mimeType", mimeType)
                        put("sizeBytes", size)
                    },
                    expectedConnectionId = connection,
                )
                .jsonObject
        check(repository.connectionId == connection) {
            "The active environment changed. Attach the file again."
        }
        val request =
            Request.Builder()
                .url(resolveSignedAssetUrl(origin, target.string("relativeUrl")))
                .post(file.asRequestBody(mimeType.toMediaType()))
                .build()
        execute(request).use { requireSuccess(it, "Attachment upload") }
        check(repository.connectionId == connection) {
            "The active environment changed. Attach the file again."
        }
        return ChatAttachment(
            target.string("attachmentId"),
            if (image) "image" else "file",
            name,
            mimeType,
            size,
        )
    }

    suspend fun download(attachment: ChatAttachment, cacheDirectory: File): File =
        withContext(Dispatchers.IO) {
            val connection =
                checkNotNull(repository.connectionId) { "Connect to an environment first." }
            val origin = checkNotNull(repository.httpBaseUrl)
            val target =
                repository
                    .callTool(
                        "assets.createUrl",
                        buildJsonObject {
                            put(
                                "resource",
                                buildJsonObject {
                                    put("_tag", "attachment")
                                    put("attachmentId", attachment.id)
                                    put("fileName", attachment.name)
                                    put("mimeType", attachment.mimeType)
                                },
                            )
                        },
                    )
                    .jsonObject
            check(repository.connectionId == connection) {
                "The active environment changed. Open the attachment again."
            }
            check(cacheDirectory.isDirectory || cacheDirectory.mkdirs()) {
                "Could not create the attachment cache."
            }
            val extension =
                attachment.name
                    .substringAfterLast('.', "")
                    .takeIf { it.matches(Regex("[a-zA-Z0-9]{1,12}")) }
                    ?.let { ".$it" }
                    .orEmpty()
            val file = File(cacheDirectory, UUID.randomUUID().toString() + extension)
            try {
                val request =
                    Request.Builder()
                        .url(
                            resolveSignedAssetUrl(
                                origin,
                                target.string("relativeUrl"),
                                "/api/assets/",
                            )
                        )
                        .get()
                        .build()
                execute(request).use { response ->
                    requireSuccess(response, "Attachment download")
                    checkNotNull(response.body) { "The attachment was empty." }
                        .byteStream()
                        .use { input ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var copied = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    copied += count
                                    require(copied <= 50L * 1024 * 1024) {
                                        "The attachment exceeds the file size limit."
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                }
                check(repository.connectionId == connection) {
                    "The active environment changed. Open the attachment again."
                }
                file
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    private fun requireSuccess(response: Response, operation: String) {
        if (!response.isSuccessful) throw IOException("$operation failed (HTTP ${response.code}).")
    }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive)
                            continuation.resumeWithException(
                                IOException(
                                    "The attachment could not be transferred. Try again.",
                                    e,
                                )
                            )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(
                            response,
                            onCancellation = { _, abandonedResponse, _ ->
                                abandonedResponse.close()
                            },
                        )
                    }
                }
            )
        }
}

internal fun resolveSignedAssetUrl(
    origin: String,
    relativeUrl: String,
    pathPrefix: String = "/api/attachments/upload/",
): String {
    val base = origin.toHttpUrl()
    val target =
        requireNotNull(base.resolve(relativeUrl)) {
            "The environment returned an invalid asset URL."
        }
    require(
        target.scheme == base.scheme &&
            target.host == base.host &&
            target.port == base.port &&
            target.username.isEmpty() &&
            target.password.isEmpty()
    ) {
        "The environment returned an asset URL on a different origin."
    }
    require(target.fragment == null && target.encodedPath.startsWith(pathPrefix)) {
        "The environment returned an invalid asset URL."
    }
    return target.toString()
}
