package com.example.teethree.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.teethree.data.T3Repository
import com.example.teethree.data.downloadBrowserPdf
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

@Composable
internal fun BrowserPdfPreview(
    repository: T3Repository,
    relativeUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var file by remember(relativeUrl) { mutableStateOf<File?>(null) }
    var error by remember(relativeUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(repository, relativeUrl) {
        var owned: File? = null
        try {
            owned =
                downloadBrowserPdf(repository, relativeUrl, File(context.cacheDir, "attachments"))
            file = owned
            awaitCancellation()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { owned?.delete() }
        }
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        val local = file
        if (local != null)
            PdfFilePreview(
                local,
                Modifier.fillMaxSize(),
                Regex("(?:#|&)page=(\\d+)")
                    .find(relativeUrl)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?.minus(1)
                    ?.coerceAtLeast(0) ?: 0,
                repository.httpBaseUrl?.trimEnd('/')?.plus(relativeUrl),
            )
        else
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error ?: "Loading PDF…", Modifier.padding(24.dp))
            }
    }
}
