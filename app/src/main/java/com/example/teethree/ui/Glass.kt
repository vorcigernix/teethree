package com.example.teethree.ui

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * What a glass panel sees through: the content it floats over, recorded once per frame into a
 * layer. Panels blur their slice of it; the content itself draws as usual.
 */
@Stable
internal class Backdrop {
    internal var layer by mutableStateOf<GraphicsLayer?>(null)
    internal var origin by mutableStateOf(Offset.Zero)
}

/**
 * The backdrop glass panels in this subtree blur. Content that is itself recorded as a backdrop
 * must see null here, or a panel inside it would end up drawing itself.
 */
internal val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Whether this device should pay for live blur; off on low-RAM devices and in battery saver. */
internal val LocalGlassBlur = staticCompositionLocalOf { true }

@Composable
internal fun rememberGlassBlurAllowed(): Boolean {
    val context = LocalContext.current
    val lowRam = remember {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    }
    val power = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var saving by remember { mutableStateOf(power.isPowerSaveMode) }
    DisposableEffect(power) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    saving = power.isPowerSaveMode
                }
            }
        context.registerReceiver(
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return !lowRam && !saving
}

/** Records this content as the [backdrop] that glass panels outside it blur. */
@Composable
internal fun Modifier.backdropSource(backdrop: Backdrop): Modifier {
    val layer = rememberGraphicsLayer()
    DisposableEffect(backdrop, layer) {
        backdrop.layer = layer
        onDispose { if (backdrop.layer === layer) backdrop.layer = null }
    }
    return onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

private val GlassBlur = 24.dp
private const val SolidAlpha = 0.92f

/**
 * Frosted glass: [tint] at [opacity] over a blur of whatever scrolls underneath, with a thin
 * light edge and an optional soft shadow. Without a backdrop, or where blur is too costly, it
 * falls back to a nearly solid fill that looks almost the same.
 */
@Composable
internal fun Modifier.glass(
    shape: Shape,
    tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    opacity: Float = 0.70f,
    shadow: Boolean = true,
): Modifier {
    val backdrop = LocalBackdrop.current
    val live = backdrop != null && LocalGlassBlur.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val edge = Color.White.copy(alpha = if (dark) 0.10f else 0.65f)
    val canvas = MaterialTheme.colorScheme.surface
    val fill = tint.copy(alpha = if (live) opacity else SolidAlpha)
    val blurLayer = rememberGraphicsLayer()
    // Where this panel sits over the backdrop; a state so a move redraws the blurred slice.
    var offset by remember { mutableStateOf(Offset.Zero) }
    val shadowed =
        if (shadow)
            Modifier.dropShadow(
                shape,
                Shadow(
                    radius = 20.dp,
                    color = Color(0xFF191C20).copy(alpha = if (dark) 0.24f else 0.08f),
                    offset = DpOffset(0.dp, (-2).dp),
                ),
            )
        else Modifier
    return this.then(shadowed)
        .onGloballyPositioned { coordinates ->
            backdrop?.let { offset = coordinates.positionInRoot() - it.origin }
        }
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val source = backdrop?.layer
            if (live && source != null) {
                val pad = ceil(GlassBlur.toPx()).toInt()
                blurLayer.renderEffect = BlurEffect(GlassBlur.toPx(), GlassBlur.toPx(), TileMode.Clamp)
                blurLayer.topLeft = IntOffset(-pad, -pad)
                blurLayer.record(
                    IntSize(ceil(size.width).toInt() + pad * 2, ceil(size.height).toInt() + pad * 2)
                ) {
                    drawRect(canvas)
                    translate(pad - offset.x, pad - offset.y) { drawLayer(source) }
                }
                clipPath(path) { drawLayer(blurLayer) }
            }
            drawOutline(outline, fill)
            // Inner edge: a 2 dp stroke clipped to the shape leaves 1 dp inside.
            clipPath(path) { drawOutline(outline, edge, style = Stroke(2.dp.toPx())) }
        }
}
