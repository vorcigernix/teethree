package com.example.teethree.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One bottom dock shared by the screens of a single-pane layout. Screens hand their dock content
 * to the host instead of drawing it, so the dock stays put while the panes slide underneath it,
 * and only what it targets (new task, reply) changes.
 */
@Stable
internal class DockHost {
    internal val slots = mutableStateMapOf<String, @Composable () -> Unit>()
    // Plain field: it only steers the next frame's fallback, never triggers one.
    private var lastShown: String? = null

    /** Distance from the dock's top edge to the bottom of the window, insets included. */
    var height by mutableStateOf(0.dp)
        internal set

    @Composable
    internal fun Overlay(active: String, modifier: Modifier = Modifier) {
        val density = LocalDensity.current
        // A pane registers its dock one frame after it enters; keep the old dock until then so
        // the dock never blinks out mid-transition.
        val key = active.takeIf { it in slots } ?: lastShown?.takeIf { it in slots }
        if (key != null) lastShown = key
        Box(
            modifier
                .fillMaxWidth()
                .onSizeChanged { height = with(density) { it.height.toDp() } }
                .settleIntoGestureInset()
                .navigationBarsPadding()
                .imePadding()
                .testTag("floating-dock")
        ) {
            key?.let { slots[it] }?.invoke()
        }
    }
}

internal val LocalDockHost = staticCompositionLocalOf<DockHost?> { null }

/**
 * Lays [content] edge to edge with the dock floating over its bottom. [content] receives the
 * bottom padding that keeps its last row clear of the dock; rows scroll on behind the dock's
 * rounded corners. Inside a [DockHost] the dock is drawn by the host, otherwise in place.
 */
@Composable
internal fun FloatingDockLayout(
    slot: String,
    modifier: Modifier = Modifier,
    dock: (@Composable () -> Unit)?,
    content: @Composable BoxScope.(bottomPadding: Dp) -> Unit,
) {
    val host = LocalDockHost.current
    if (host != null) {
        val latest = rememberUpdatedState(dock)
        val present = dock != null
        DisposableEffect(host, slot, present) {
            if (present) host.slots[slot] = { latest.value?.invoke() }
            onDispose { host.slots.remove(slot) }
        }
        Box(modifier) { content(if (present) host.height else 0.dp) }
        return
    }
    val density = LocalDensity.current
    var height by remember { mutableStateOf(0.dp) }
    val backdrop = remember { Backdrop() }
    Box(modifier) {
        CompositionLocalProvider(LocalBackdrop provides null) {
            Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
                content(if (dock != null) height else 0.dp)
            }
        }
        if (dock != null)
            CompositionLocalProvider(LocalBackdrop provides backdrop) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .onSizeChanged { height = with(density) { it.height.toDp() } }
                        .settleIntoGestureInset()
                        .navigationBarsPadding()
                        .imePadding()
                        .testTag("floating-dock")
                ) {
                    dock()
                }
            }
    }
}

/**
 * Gesture navigation can reserve more height than its handle needs (36 dp on recent Pixels versus
 * the 24 dp the design assumes). Resting on the whole inset leaves the dock floating too high, so
 * it settles to half the inset plus 12 dp: the design's spot on a 24 dp inset, and the same gap to
 * the handle on taller ones. Button bars and the keyboard keep the full inset.
 */
@Composable
private fun Modifier.settleIntoGestureInset(): Modifier {
    val navigation = WindowInsets.navigationBars
    val tappable = WindowInsets.tappableElement
    val ime = WindowInsets.ime
    return layout { measurable, constraints ->
        val inset = navigation.getBottom(this)
        val slack =
            if (tappable.getBottom(this) > 0) 0
            else (inset / 2 - 12.dp.roundToPx() - ime.getBottom(this)).coerceAtLeast(0)
        val placeable = measurable.measure(constraints)
        // Report a shorter box; the inset padding below the dock overhangs the bottom edge.
        layout(placeable.width, (placeable.height - slack).coerceAtLeast(0)) { placeable.place(0, 0) }
    }
}
