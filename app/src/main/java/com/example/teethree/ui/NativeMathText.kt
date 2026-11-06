package com.example.teethree.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import com.example.teethree.R
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.RaTeXRenderer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal const val INLINE_MATH = "t3.math.inline"
internal const val DISPLAY_MATH = "t3.math.display"

private data class MathRasterKey(
    val latex: String,
    val display: Boolean,
    val pixels: Float,
    val color: Int,
)

private class NativeMathSpan(
    val bitmap: Bitmap,
    private val ascent: Float,
    private val descent: Float,
) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        fm?.let {
            val surrounding = paint.fontMetricsInt
            it.ascent = min(surrounding.ascent, -ceil(ascent).toInt())
            it.descent = max(surrounding.descent, ceil(descent).toInt())
            it.top = min(surrounding.top, it.ascent)
            it.bottom = max(surrounding.bottom, it.descent)
        }
        return bitmap.width
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        canvas.drawBitmap(bitmap, x, y - ascent, null)
    }
}

private object MathRasters {
    private val renders = Semaphore(2)
    private val cache =
        object : LruCache<MathRasterKey, NativeMathSpan>(16 * 1024 * 1024) {
            override fun sizeOf(key: MathRasterKey, value: NativeMathSpan) =
                value.bitmap.allocationByteCount
        }

    suspend fun get(context: Context, key: MathRasterKey): NativeMathSpan? =
        withContext(Dispatchers.Default) {
            if (key.latex.length > 16_384) return@withContext null
            cache.get(key)?.let {
                return@withContext it
            }
            renders.withPermit {
                cache.get(key)?.let {
                    return@withPermit it
                }
                try {
                    ensureActive()
                    RaTeXFontLoader.ensureLoaded(context)
                    val displayList = RaTeXEngine.parse(key.latex, key.display, key.color)
                    val renderer =
                        RaTeXRenderer(displayList, key.pixels) { RaTeXFontLoader.getTypeface(it) }
                    if (!renderer.widthPx.isFinite() || !renderer.totalHeightPx.isFinite())
                        return@withPermit null
                    val width = ceil(renderer.widthPx.toDouble()).toLong().coerceAtLeast(1)
                    val height = ceil(renderer.totalHeightPx.toDouble()).toLong().coerceAtLeast(1)
                    if (width > 16_384 || height > 16_384 || width * height > 4_194_304)
                        return@withPermit null
                    ensureActive()
                    val bitmap =
                        Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
                    renderer.draw(Canvas(bitmap))
                    NativeMathSpan(bitmap, renderer.heightPx, renderer.depthPx).also {
                        cache.put(key, it)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                } catch (_: LinkageError) {
                    null
                }
            }
        }
}

private class MathTextView(context: Context) : TextView(context) {
    var appliedText: CharSequence? = null
    var findStart: Int? = null
    var findBounds: (Rect) -> Unit = {}

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val start = findStart?.coerceIn(0, text.length) ?: return
        val textLayout = layout ?: return
        val line = textLayout.getLineForOffset(start)
        val x = textLayout.getPrimaryHorizontal(start)
        findBounds(
            Rect(
                x,
                textLayout.getLineTop(line).toFloat(),
                x + 1,
                textLayout.getLineBottom(line).toFloat(),
            )
        )
    }
}

@Composable
internal fun NativeMathText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    centered: Boolean = false,
    findStart: Int? = null,
    onFindBounds: (Rect) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val defaultColor = MaterialTheme.colorScheme.onSurface
    val color = (if (style.color.isSpecified) style.color else defaultColor).toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val pixels = with(density) { style.fontSize.toPx() }
    val linePixels = with(density) { style.lineHeight.toPx() }
    val math =
        remember(text) {
            text.getStringAnnotations(0, text.length).filter {
                it.tag == INLINE_MATH || it.tag == DISPLAY_MATH
            }
        }
    val rasters by
        produceState<Map<AnnotatedString.Range<String>, NativeMathSpan>>(
            emptyMap(),
            math,
            pixels,
            color,
        ) {
            value =
                math
                    .mapNotNull { range ->
                        MathRasters.get(
                                context.applicationContext,
                                MathRasterKey(range.item, range.tag == DISPLAY_MATH, pixels, color),
                            )
                            ?.let { range to it }
                    }
                    .toMap()
        }
    val spanned =
        remember(text, rasters, math, linkColor) {
            SpannableStringBuilder(text.text).apply {
                fun span(value: Any, start: Int, end: Int) {
                    if (start < end) setSpan(value, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                text.spanStyles.forEach { range ->
                    val attributes = range.item
                    if (attributes.color.isSpecified)
                        span(ForegroundColorSpan(attributes.color.toArgb()), range.start, range.end)
                    if (attributes.background.isSpecified)
                        span(
                            BackgroundColorSpan(attributes.background.toArgb()),
                            range.start,
                            range.end,
                        )
                    if (attributes.fontWeight != null || attributes.fontStyle != null)
                        span(
                            StyleSpan(
                                (if (
                                    (attributes.fontWeight ?: FontWeight.Normal) >=
                                        FontWeight.SemiBold
                                )
                                    Typeface.BOLD
                                else 0) or
                                    (if (attributes.fontStyle == FontStyle.Italic) Typeface.ITALIC
                                    else 0)
                            ),
                            range.start,
                            range.end,
                        )
                    if (attributes.fontFamily == T3MonoFontFamily)
                        span(TypefaceSpan("monospace"), range.start, range.end)
                    if (attributes.textDecoration?.contains(TextDecoration.Underline) == true)
                        span(UnderlineSpan(), range.start, range.end)
                }
                text.getLinkAnnotations(0, text.length).forEach { range ->
                    span(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                range.item.linkInteractionListener?.onClick(range.item)
                            }

                            override fun updateDrawState(ds: TextPaint) {
                                ds.color = linkColor
                                ds.isUnderlineText = true
                            }
                        },
                        range.start,
                        range.end,
                    )
                }
                rasters.forEach { (range, raster) ->
                    if (range in math) span(raster, range.start, range.end)
                }
            }
        }
    AndroidView(
        modifier = modifier,
        factory = {
            MathTextView(it).apply {
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.setTextColor(color)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, pixels)
            view.typeface =
                Typeface.create(
                    ResourcesCompat.getFont(context, R.font.roboto_flex),
                    (fontWeight ?: style.fontWeight ?: FontWeight.Normal).weight,
                    style.fontStyle == FontStyle.Italic,
                )
            view.setLineSpacing((linePixels - view.paint.fontSpacing).coerceAtLeast(0f), 1f)
            view.gravity = if (centered) Gravity.CENTER_HORIZONTAL else Gravity.START
            if (view.appliedText !== spanned) {
                val start = view.selectionStart
                val end = view.selectionEnd
                val sameSource = view.text.toString() == spanned.toString()
                view.text = spanned
                view.appliedText = spanned
                if (sameSource && start >= 0 && end >= 0)
                    (view.text as? Spannable)?.let {
                        Selection.setSelection(
                            it,
                            start.coerceAtMost(it.length),
                            end.coerceAtMost(it.length),
                        )
                    }
            }
            view.findStart = findStart
            view.findBounds = onFindBounds
            view.requestLayout()
        },
    )
}
