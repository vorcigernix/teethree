package com.example.teethree.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.teethree.data.AttachmentTransfers
import com.example.teethree.data.ChatAttachment
import com.example.teethree.data.T3Repository
import com.example.teethree.data.imageDecodeSample
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentViewerScreen(
    repository: T3Repository,
    attachment: ChatAttachment,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = File(context.cacheDir, "attachments")
    var savedPath by
        rememberSaveable(attachment.id, repository.connectionId) { mutableStateOf<String?>(null) }
    var file by remember(attachment.id, repository.connectionId) { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val save = rememberSaveLocalFile(file, attachment.name, attachment.mimeType) { error = it }
    LaunchedEffect(attachment.id, repository.connectionId, attempt) {
        error = null
        try {
            val existing =
                withContext(Dispatchers.IO) {
                    savedPath?.let(::File)?.takeIf {
                        it.isFile &&
                            it.canonicalPath.startsWith(root.canonicalPath + File.separator)
                    }
                }
            val loaded = existing ?: AttachmentTransfers(repository).download(attachment, root)
            savedPath = loaded.absolutePath
            file = loaded
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "Could not open the attachment."
        }
    }
    fun external(share: Boolean) {
        val source = file ?: return
        try {
            openLocalFile(context, source, attachment.name, attachment.mimeType, share)
        } catch (failure: Exception) {
            error = failure.message ?: "No app can open this attachment."
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(attachment.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, "Attachment options")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Save a copy") },
                                enabled = file != null,
                                onClick = {
                                    menu = false
                                    save()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                enabled = file != null,
                                onClick = {
                                    menu = false
                                    external(true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Open with") },
                                enabled = file != null,
                                onClick = {
                                    menu = false
                                    external(false)
                                },
                            )
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                TextButton(
                    onClick = {
                        savedPath = null
                        file = null
                        attempt++
                    }
                ) {
                    Text("Try again")
                }
            }
            file?.let {
                LocalAttachmentPreview(
                    it,
                    attachment.mimeType,
                    attachment.name,
                    Modifier.weight(1f).fillMaxWidth(),
                )
            } ?: if (error == null) Text("Opening attachment…", Modifier.padding(24.dp)) else Unit
        }
    }
}

@Composable
internal fun LocalAttachmentPreview(
    file: File,
    mimeType: String,
    name: String,
    modifier: Modifier = Modifier,
    archiveDepth: Int = 0,
) {
    when {
        mimeType.substringBefore(';') in setOf("application/zip", "application/x-zip-compressed") ||
            name.endsWith(".zip", true) ->
            if (archiveDepth < 3) ZipAttachmentPreview(file, modifier, archiveDepth)
            else
                Text(
                    "Nested ZIP preview is limited to three levels. Save or Open with to continue.",
                    Modifier.padding(24.dp),
                )
        mimeType == "application/pdf" || name.endsWith(".pdf", true) ->
            PdfFilePreview(file, modifier)
        mimeType.startsWith("image/") -> {
            var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
            var error by remember(file) { mutableStateOf<String?>(null) }
            LaunchedEffect(file) {
                try {
                    bitmap =
                        withContext(Dispatchers.IO) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.absolutePath, bounds)
                            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                                "This image format cannot be previewed here."
                            }
                            val options =
                                BitmapFactory.Options().apply {
                                    inSampleSize =
                                        imageDecodeSample(bounds.outWidth, bounds.outHeight)
                                }
                            checkNotNull(BitmapFactory.decodeFile(file.absolutePath, options)) {
                                "The image could not be decoded."
                            }
                        }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    error = failure.message
                }
            }
            bitmap?.let { ZoomableBitmap(it, name, modifier) }
                ?: Text(error ?: "Loading image…", Modifier.padding(24.dp))
        }
        mimeType.startsWith("video/") -> LocalVideoPreview(file, modifier)
        mimeType.startsWith("audio/") -> LocalAudioPreview(file, modifier)
        mimeType.startsWith("text/") ||
            name.substringAfterLast('.', "").lowercase() in
                setOf(
                    "md",
                    "txt",
                    "json",
                    "yaml",
                    "yml",
                    "kt",
                    "java",
                    "js",
                    "ts",
                    "tsx",
                    "py",
                    "css",
                    "html",
                    "htm",
                    "xml",
                    "csv",
                    "tsv",
                    "log",
                    "sh",
                ) -> TextAttachmentPreview(file, mimeType, name, modifier)
        else ->
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(
                    "Use Open with, Save a copy, or Share to view this format.",
                    Modifier.padding(24.dp),
                )
            }
    }
}

@Composable
private fun LocalVideoPreview(file: File, modifier: Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reference = remember { arrayOfNulls<VideoView>(1) }
    var position by rememberSaveable(file.absolutePath) { mutableStateOf(0) }
    AndroidView(
        factory = { context ->
            VideoView(context).also { video ->
                reference[0] = video
                video.setMediaController(MediaController(context).apply { setAnchorView(video) })
                video.setVideoPath(file.absolutePath)
                video.setOnPreparedListener {
                    video.seekTo(position)
                    video.pause()
                }
            }
        },
        modifier = modifier,
        onRelease = { video ->
            position = video.currentPosition
            reference[0] = null
            video.stopPlayback()
        },
    )
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE)
                reference[0]?.let {
                    position = it.currentPosition
                    it.pause()
                }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun LocalAudioPreview(file: File, modifier: Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var playing by remember(file) { mutableStateOf(false) }
    var prepared by remember(file) { mutableStateOf(false) }
    var duration by remember(file) { mutableStateOf(0) }
    var position by rememberSaveable(file.absolutePath) { mutableStateOf(0) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var seeking by remember(file) { mutableStateOf(false) }
    val player = remember(file) { MediaPlayer() }
    DisposableEffect(player, lifecycle) {
        player.setOnPreparedListener {
            duration = it.duration.coerceAtLeast(0)
            it.seekTo(position)
            prepared = true
        }
        player.setOnCompletionListener {
            playing = false
            position = 0
        }
        player.setOnErrorListener { _, _, _ ->
            playing = false
            error = "This audio format could not be played."
            true
        }
        try {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        } catch (failure: Exception) {
            error = failure.message
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && prepared) {
                player.pause()
                position = player.currentPosition
                playing = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
        }
    }
    LaunchedEffect(playing) {
        while (playing && isActive) {
            if (!seeking) position = player.currentPosition
            delay(1000)
        }
    }
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "${position / 60_000}:${(position / 1000 % 60).toString().padStart(2, '0')} / ${duration / 60_000}:${(duration / 1000 % 60).toString().padStart(2, '0')}"
        )
        Slider(
            value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
            onValueChange = {
                seeking = true
                position = it.toInt()
            },
            onValueChangeFinished = {
                player.seekTo(position)
                seeking = false
            },
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = prepared,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                enabled = prepared,
                onClick = {
                    if (playing) {
                        player.pause()
                        position = player.currentPosition
                    } else player.start()
                    playing = !playing
                },
            ) {
                Text(if (playing) "Pause" else "Play")
            }
            TextButton(
                enabled = prepared,
                onClick = {
                    player.pause()
                    player.seekTo(0)
                    position = 0
                    playing = false
                },
            ) {
                Text("Stop")
            }
        }
    }
}
