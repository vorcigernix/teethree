package com.example.teethree.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun fileActionMime(value: String) =
    value.substringBefore(';').trim().takeIf {
        it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))
    } ?: "application/octet-stream"

internal fun openLocalFile(
    context: Context,
    file: File,
    name: String,
    mimeType: String,
    share: Boolean = false,
) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = fileActionMime(mimeType)
    val intent =
        if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    intent.clipData = ClipData.newRawUri(name, uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(
        Intent.createChooser(intent, if (share) "Share $name" else "Open $name")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
internal fun rememberSaveLocalFile(
    file: File?,
    name: String,
    mimeType: String,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<File>(1) }
    val latestError = rememberUpdatedState(onError)
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(fileActionMime(mimeType))
        ) { uri ->
            val source = pending[0]
            pending[0] = null
            if (source != null && uri != null)
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            source.inputStream().use { input ->
                                checkNotNull(context.contentResolver.openOutputStream(uri)).use {
                                    output ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        latestError.value(failure.message ?: "Could not save the file.")
                    }
                }
        }
    return {
        file?.let {
            pending[0] = it
            launcher.launch(name.substringAfterLast('/').take(255).ifBlank { "attachment" })
        }
    }
}
