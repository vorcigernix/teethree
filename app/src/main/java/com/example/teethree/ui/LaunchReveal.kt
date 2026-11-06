package com.example.teethree.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Launch → reveal. Opening the app paints the saved screen first, as real UI. Loading indicators
 * only join after [IndicatorHoldMs], as a layer on top, so nothing shifts. When the live data
 * lands, only what changed animates: fields crossfade at the same size, status icons morph, new
 * rows expand in, and a light tint fades off whatever changed.
 */

/** Connections that open faster than this never show a loading state. */
internal const val IndicatorHoldMs = 400L

/** True when the system's animator scale is off ("Remove animations"). */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * True once the link has been waiting longer than [IndicatorHoldMs]. Before that the screen looks
 * exactly as it was left.
 */
@Composable
internal fun rememberLinkWaiting(): Boolean {
    val link = LocalLink.current ?: return false
    val phase = link.phase
    val waiting = phase == LinkPhase.Connecting || phase == LinkPhase.Unreachable
    val since = link.info.offlineSince
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(waiting, since) {
        if (!waiting) {
            shown = false
            return@LaunchedEffect
        }
        val start = since ?: System.currentTimeMillis()
        delay((start + IndicatorHoldMs - System.currentTimeMillis()).coerceAtLeast(0))
        shown = true
    }
    return shown
}

/**
 * The wavy Material 3 progress line under the top bar while connecting. Drawn as an overlay by the
 * caller (aligned in a Box), so it never takes height. Static with reduced motion.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WaitingProgressLine(modifier: Modifier = Modifier) {
    val waiting = rememberLinkWaiting()
    val reduced = rememberReducedMotion()
    AnimatedVisibility(
        visible = waiting,
        modifier = modifier,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(150)),
    ) {
        Box(Modifier.fillMaxWidth().testTag("waiting-progress")) {
            if (reduced)
                LinearProgressIndicator(
                    progress = { 0.35f },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
            else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/**
 * A soft band of light sweeping down through content while the link is waiting. It fades in
 * over 200 ms and out over 150 ms, and does nothing with reduced motion.
 */
internal fun Modifier.contentSheen(active: Boolean): Modifier = composed {
    if (rememberReducedMotion()) return@composed this
    val strength by animateFloatAsState(
        if (active) 1f else 0f,
        tween(if (active) 200 else 150),
        label = "sheen-strength",
    )
    if (strength == 0f) return@composed this
    val sweep by
        rememberInfiniteTransition(label = "sheen")
            .animateFloat(
                -0.3f,
                1.3f,
                infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
                label = "sheen-y",
            )
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val light = Color.White.copy(alpha = if (dark) 0.07f else 0.5f)
    drawWithContent {
        drawContent()
        val band = size.height * 0.22f
        val center = size.height * sweep
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, light.copy(alpha = light.alpha * strength), Color.Transparent),
                startY = center - band,
                endY = center + band,
            )
        )
    }
}

/**
 * The status icon's container. When [kind] changes after first paint it morphs through a rounded
 * square back to a circle (350 ms, spring) while its colour moves to the new state.
 */
@Composable
internal fun MorphingStatusBadge(
    kind: Any,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    content: @Composable () -> Unit,
) {
    val reduced = rememberReducedMotion()
    val corner = remember { Animatable(50f) }
    var last by remember { mutableStateOf(kind) }
    LaunchedEffect(kind) {
        if (kind == last) return@LaunchedEffect
        last = kind
        if (reduced) return@LaunchedEffect
        corner.animateTo(
            50f,
            keyframes {
                durationMillis = 350
                26f at 140
                50f at 350
            },
        )
    }
    val tint by androidx.compose.animation.animateColorAsState(
        color,
        if (reduced) tween(150) else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "badge-color",
    )
    Box(
        modifier.size(size).background(tint, RoundedCornerShape(corner.value.toInt())),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * A light tint that fades off a row when its [signature] changes after first paint, or when the
 * row is [fresh] (new since the saved copy). 600 ms, secondary container to transparent.
 */
@Composable
internal fun rememberChangeTint(signature: Any, fresh: Boolean): Float {
    val tint = remember { Animatable(if (fresh) 1f else 0f) }
    var last by remember { mutableStateOf(signature) }
    LaunchedEffect(signature, fresh) {
        if (signature != last) {
            last = signature
            tint.snapTo(1f)
        }
        if (tint.value > 0f) {
            delay(250)
            tint.animateTo(0f, tween(600))
        }
    }
    return tint.value
}

private val EmphasizedDecelerateEasing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * Swaps a field's value in place: the new value fades in (250 ms, emphasized decelerate) over the
 * old one fading out (90 ms), and the box keeps its size so nothing reflows.
 */
@Composable
internal fun <T> SameSizeSwap(value: T, content: @Composable (T) -> Unit) {
    val reduced = rememberReducedMotion()
    androidx.compose.animation.AnimatedContent(
        value,
        transitionSpec = {
            (fadeIn(tween(if (reduced) 150 else 250, easing = EmphasizedDecelerateEasing)) togetherWith
                fadeOut(tween(if (reduced) 150 else 90))) using
                androidx.compose.animation.SizeTransform(clip = false)
        },
        contentAlignment = Alignment.CenterStart,
        label = "same-size-swap",
    ) {
        content(it)
    }
}

