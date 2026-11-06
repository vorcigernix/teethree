package com.example.teethree.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberSaveVisiblePreview(
    view: PreviewContentView?,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<Bitmap>(1) }
    val error = rememberUpdatedState(onError)
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri
            ->
            val bitmap = pending[0]
            pending[0] = null
            if (bitmap != null)
                scope.launch {
                    try {
                        if (uri != null)
                            withContext(Dispatchers.IO) {
                                checkNotNull(context.contentResolver.openOutputStream(uri)).use {
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                            }
                    } catch (failure: Exception) {
                        error.value(failure.message ?: "Could not save the preview.")
                    } finally {
                        bitmap.recycle()
                    }
                }
        }
    DisposableEffect(Unit) {
        onDispose {
            pending[0]?.recycle()
            pending[0] = null
        }
    }
    return {
        if (pending[0] == null)
            view?.capture { bitmap, failure ->
                if (bitmap == null) error.value(failure ?: "The preview is not visible.")
                else {
                    pending[0] = bitmap
                    save.launch("preview.png")
                }
            }
    }
}
