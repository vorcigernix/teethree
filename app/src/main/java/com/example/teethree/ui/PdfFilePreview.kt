package com.example.teethree.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.teethree.data.resolvePreviewPdfLink
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class PdfPageLink(
    val bounds: List<RectF>,
    val url: String? = null,
    val page: Int? = null,
)

private data class RenderedPdfPage(
    val bitmap: Bitmap,
    val count: Int,
    val width: Int,
    val height: Int,
    val links: List<PdfPageLink>,
)

@Composable
fun PdfFilePreview(
    file: File,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
    sourceUrl: String? = null,
) {
    val context = LocalContext.current
    var page by rememberSaveable(file.absolutePath) { mutableStateOf(initialPage.coerceAtLeast(0)) }
    var count by remember(file) { mutableStateOf(0) }
    var image by remember(file) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var loading by remember(file) { mutableStateOf(true) }
    var renderedPage by remember(file) { mutableStateOf<RenderedPdfPage?>(null) }
    var showLinks by rememberSaveable { mutableStateOf(false) }
    val lock = remember(file) { Mutex() }
    BoxWithConstraints(modifier) {
        val width = with(LocalDensity.current) { maxWidth.toPx().roundToInt().coerceIn(256, 1600) }
        LaunchedEffect(file, page, width) {
            loading = true
            error = null
            image = null
            renderedPage = null
            try {
                val rendered =
                    withContext(Dispatchers.IO) {
                        lock.withLock {
                            PdfRenderer(
                                    ParcelFileDescriptor.open(
                                        file,
                                        ParcelFileDescriptor.MODE_READ_ONLY,
                                    )
                                )
                                .use { renderer ->
                                    require(renderer.pageCount > 0) { "The PDF has no pages." }
                                    renderer
                                        .openPage(page.coerceAtMost(renderer.pageCount - 1))
                                        .use { source ->
                                            require(source.width > 0 && source.height > 0) {
                                                "The PDF page has invalid dimensions."
                                            }
                                            val scale =
                                                minOf(
                                                    width.toDouble() / source.width,
                                                    sqrt(
                                                        4_000_000.0 /
                                                            (source.width.toDouble() *
                                                                source.height)
                                                    ),
                                                )
                                            val bitmap =
                                                Bitmap.createBitmap(
                                                    (source.width * scale)
                                                        .roundToInt()
                                                        .coerceAtLeast(1),
                                                    (source.height * scale)
                                                        .roundToInt()
                                                        .coerceAtLeast(1),
                                                    Bitmap.Config.ARGB_8888,
                                                )
                                            bitmap.eraseColor(AndroidColor.WHITE)
                                            source.render(
                                                bitmap,
                                                null,
                                                null,
                                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                            )
                                            val links =
                                                if (Build.VERSION.SDK_INT >= 35) {
                                                    source.linkContents.take(512).mapNotNull { link
                                                        ->
                                                        resolvePreviewPdfLink(
                                                                link.uri.toString(),
                                                                sourceUrl,
                                                            )
                                                            ?.let {
                                                                PdfPageLink(
                                                                    link.bounds.map(::RectF),
                                                                    url = it,
                                                                )
                                                            }
                                                    } +
                                                        source.gotoLinks
                                                            .take(512)
                                                            .filter {
                                                                it.destination.pageNumber in
                                                                    0 until renderer.pageCount
                                                            }
                                                            .map { link ->
                                                                PdfPageLink(
                                                                    link.bounds.map(::RectF),
                                                                    page =
                                                                        link.destination.pageNumber,
                                                                )
                                                            }
                                                } else emptyList()
                                            RenderedPdfPage(
                                                bitmap,
                                                renderer.pageCount,
                                                source.width,
                                                source.height,
                                                links,
                                            )
                                        }
                                }
                        }
                    }
                count = rendered.count
                if (page >= count) page = count - 1
                image = rendered.bitmap
                renderedPage = rendered
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error =
                    failure.message
                        ?: "This PDF could not be rendered. It may be damaged or password protected."
            } finally {
                loading = false
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page-- }, enabled = page > 0 && !loading) {
                    Text("Previous")
                }
                Text(if (count > 0) "${page + 1} of $count" else "PDF", Modifier.weight(1f))
                TextButton(onClick = { page++ }, enabled = page + 1 < count && !loading) {
                    Text("Next")
                }
            }
            fun follow(link: PdfPageLink) {
                showLinks = false
                link.page?.let { page = it }
                link.url?.let { url ->
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (failure: Exception) {
                        error = failure.message ?: "No browser can open this link."
                    }
                }
            }
            Row {
                if (renderedPage?.links?.isNotEmpty() == true)
                    TextButton(onClick = { showLinks = true }) {
                        Text("Links (${renderedPage!!.links.size})")
                    }
                TextButton(
                    onClick = {
                        try {
                            openLocalFile(context, file, file.name, "application/pdf")
                        } catch (failure: Exception) {
                            error = failure.message ?: "No PDF viewer is installed."
                        }
                    }
                ) {
                    Text("Open with")
                }
            }
            val bitmap = image
            if (bitmap != null)
                ZoomableBitmap(
                    bitmap,
                    "PDF page ${page + 1}",
                    Modifier.weight(1f).fillMaxWidth(),
                    "$page:$width",
                    onImageTap = { point ->
                        renderedPage?.let { pdf ->
                            pdf.links
                                .firstOrNull { link ->
                                    link.bounds.any {
                                        it.contains(point.x * pdf.width, point.y * pdf.height)
                                    }
                                }
                                ?.let(::follow)
                        }
                    },
                )
            else
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(error ?: "Rendering PDF…", Modifier.padding(24.dp))
                }
            if (showLinks)
                AlertDialog(
                    onDismissRequest = { showLinks = false },
                    title = { Text("Links on this page") },
                    text = {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(renderedPage?.links.orEmpty()) { link ->
                                TextButton(onClick = { follow(link) }) {
                                    Text(link.url ?: "Page ${(link.page ?: 0) + 1}")
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showLinks = false }) { Text("Done") }
                    },
                )
        }
    }
}

@Composable
internal fun ZoomableBitmap(
    bitmap: Bitmap,
    description: String,
    modifier: Modifier = Modifier,
    identity: String = description,
    onImageTap: ((Offset) -> Unit)? = null,
) {
    var scale by remember(identity) { mutableStateOf(1f) }
    var offset by remember(identity) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val geometry = rememberUpdatedState(Triple(scale, offset, size))
    val latestTap = rememberUpdatedState(onImageTap)
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(modifier.clipToBounds()) {
        Image(
            bitmap.asImageBitmap(),
            description,
            Modifier.fillMaxSize()
                .onSizeChanged { size = it }
                .pointerInput(bitmap, identity) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2f
                            offset = Offset.Zero
                        },
                        onTap = { point ->
                            val (zoom, pan, viewport) = geometry.value
                            val fit =
                                minOf(
                                    viewport.width.toFloat() / bitmap.width,
                                    viewport.height.toFloat() / bitmap.height,
                                )
                            if (fit > 0) {
                                val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                val adjusted = (point - center - pan) / zoom + center
                                val origin =
                                    Offset(
                                        (viewport.width - bitmap.width * fit) / 2f,
                                        (viewport.height - bitmap.height * fit) / 2f,
                                    )
                                val normalized =
                                    Offset(
                                        (adjusted.x - origin.x) / (bitmap.width * fit),
                                        (adjusted.y - origin.y) / (bitmap.height * fit),
                                    )
                                if (normalized.x in 0f..1f && normalized.y in 0f..1f)
                                    latestTap.value?.invoke(normalized)
                            }
                        },
                    )
                }
                .transformable(transform)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )
        Row(Modifier.align(Alignment.BottomCenter)) {
            TextButton(
                onClick = {
                    scale = (scale / 1.5f).coerceAtLeast(1f)
                    if (scale == 1f) offset = Offset.Zero
                },
                enabled = scale > 1f,
            ) {
                Text("Zoom out")
            }
            TextButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                }
            ) {
                Text("Reset")
            }
            TextButton(
                onClick = { scale = (scale * 1.5f).coerceAtMost(5f) },
                enabled = scale < 5f,
            ) {
                Text("Zoom in")
            }
        }
    }
}
