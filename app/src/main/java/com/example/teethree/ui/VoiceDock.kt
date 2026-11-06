package com.example.teethree.ui

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
internal data class DockChip(
    val label: String,
    val icon: ImageVector,
    val description: String,
    val onClick: (() -> Unit)?,
)

/**
 * The voice-first composer: one large mic, with the keyboard and attachments a tap away. While a
 * capture runs it becomes the listening panel, or the slim live bar in hands-free mode so the
 * conversation stays readable.
 */
@Composable
internal fun VoiceDock(
    voice: VoiceInput,
    onStartVoice: (hold: Boolean) -> Unit,
    onKeyboard: () -> Unit,
    onAttach: () -> Unit,
    enabled: Boolean,
    attachEnabled: Boolean,
    chips: List<DockChip>,
    hint: String,
    target: String,
    modifier: Modifier = Modifier,
    draftPreview: String = "",
    onSend: (() -> Unit)? = null,
    sendEnabled: Boolean = false,
    sendDescription: String = "Send",
    compactListening: Boolean = false,
    compactLabel: String = "Listening…",
    topContent: @Composable ColumnScope.() -> Unit = {},
) {
    val pushToTalk = rememberPushToTalk(voice)
    Box(
        modifier
            .fillMaxWidth()
            .releaseEndsHold(pushToTalk)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
    ) {
        when {
            voice.listening && compactListening ->
                LiveVoiceBar(voice, compactLabel, onKeyboard = {
                    voice.cancel()
                    onKeyboard()
                })
            voice.listening ->
                ListeningPanel(
                    voice,
                    target,
                    onEdit = { voice.finish(VoiceFinish.Edit) },
                )
            else ->
                IdleDock(
                    voice,
                    pushToTalk,
                    onStartVoice,
                    onKeyboard,
                    onAttach,
                    enabled,
                    attachEnabled,
                    chips,
                    hint,
                    draftPreview,
                    onSend,
                    sendEnabled,
                    sendDescription,
                    topContent,
                )
        }
    }
}

@Composable
private fun IdleDock(
    voice: VoiceInput,
    pushToTalk: PushToTalk,
    onStartVoice: (hold: Boolean) -> Unit,
    onKeyboard: () -> Unit,
    onAttach: () -> Unit,
    enabled: Boolean,
    attachEnabled: Boolean,
    chips: List<DockChip>,
    hint: String,
    draftPreview: String,
    onSend: (() -> Unit)?,
    sendEnabled: Boolean,
    sendDescription: String,
    topContent: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = Color.Transparent,
        contentColor = colors.onSurface,
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier.glass(RoundedCornerShape(32.dp)).fillMaxWidth().testTag("voice-dock"),
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            topContent()
            if (draftPreview.isNotBlank())
                // Words already in the draft (spoken or typed): tap to edit, or send them as they are.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        draftPreview,
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClickLabel = "Edit draft", onClick = onKeyboard)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onSend != null)
                        FilledIconButton(
                            onClick = onSend,
                            enabled = sendEnabled,
                            modifier = Modifier.size(44.dp).testTag("voice-dock-send"),
                        ) {
                            Icon(Icons.Default.ArrowUpward, sendDescription, Modifier.size(20.dp))
                        }
                }
            if (chips.isNotEmpty())
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    chips.forEach { ContextChip(it) }
                }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockCircleButton(Icons.Outlined.Keyboard, "Type instead", onKeyboard)
                VoiceHeroButton(
                    Icons.Default.Mic,
                    "Talk to the agent",
                    enabled = enabled,
                    onTap = { onStartVoice(false) },
                    onHoldStart = {
                        pushToTalk.begin()
                        onStartVoice(true)
                    },
                    onHoldEnd = pushToTalk::release,
                    modifier = Modifier.testTag("voice-mic"),
                )
                DockCircleButton(
                    Icons.Outlined.AddPhotoAlternate,
                    "Attach image or file",
                    onAttach,
                    enabled = attachEnabled,
                )
            }
            Text(
                voice.error ?: hint,
                style = MaterialTheme.typography.bodyMedium,
                color = if (voice.error != null) colors.error else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ListeningPanel(voice: VoiceInput, target: String, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = Color.Transparent,
        contentColor = colors.onSurface,
        shape = RoundedCornerShape(32.dp),
        modifier =
            Modifier.glass(RoundedCornerShape(32.dp)).fillMaxWidth().testTag("voice-listening"),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).background(colors.error, CircleShape))
                Text(
                    "Listening",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "· $target",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    elapsedLabel(voice.startedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = T3MonoFontFamily,
                    color = colors.onSurfaceVariant,
                )
            }
            LiveTranscript(voice)
            Waveform(
                voice.levels,
                barWidth = 4.dp,
                gap = 4.dp,
                maxHeight = 40.dp,
                minHeight = 6.dp,
                color = colors.primary,
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LabeledCircleButton(Icons.Default.Close, "Cancel", "Discard what you said") {
                    voice.cancel()
                }
                VoiceHeroButton(
                    Icons.Default.ArrowUpward,
                    "Send now",
                    enabled = true,
                    onTap = { voice.finish(VoiceFinish.Send) },
                    modifier = Modifier.testTag("voice-send"),
                )
                LabeledCircleButton(Icons.Outlined.Edit, "Edit text", "Edit before sending", onEdit)
            }
            Text(
                "Pause to send  ·  say “cancel” to discard",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LiveTranscript(voice: VoiceInput) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(voice.transcript, voice.partial) { scroll.animateScrollTo(scroll.maxValue) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val style = MaterialTheme.typography.headlineSmall
        if (voice.transcript.isEmpty() && voice.partial.isEmpty())
            Text("Go ahead, I'm listening…", style = style, color = colors.outline)
        if (voice.transcript.isNotEmpty())
            Text(voice.transcript, style = style, color = colors.onSurface)
        if (voice.partial.isNotEmpty()) Text(voice.partial, style = style, color = colors.outline)
    }
}

/** Hands-free capture: a slim bar, so the approval card and the reply stay in view. */
@Composable
private fun LiveVoiceBar(voice: VoiceInput, label: String, onKeyboard: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = Color.Transparent,
        contentColor = colors.onSurface,
        shape = RoundedCornerShape(36.dp),
        modifier =
            Modifier.glass(RoundedCornerShape(36.dp)).fillMaxWidth().testTag("voice-live-bar"),
    ) {
        Row(
            Modifier.height(72.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DockCircleButton(Icons.Outlined.Keyboard, "Type instead", onKeyboard, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val heard = joinSpeech(voice.transcript, voice.partial)
                Text(
                    heard.ifEmpty { label },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Waveform(
                    voice.levels.takeLast(18),
                    barWidth = 3.dp,
                    gap = 3.dp,
                    maxHeight = 14.dp,
                    minHeight = 4.dp,
                    color = colors.primary,
                )
            }
            Box(
                Modifier.size(56.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.primary)
                    .clickable(role = Role.Button, onClickLabel = "Send now") {
                        voice.finish(VoiceFinish.Send)
                    }
                    .semantics { contentDescription = "Listening. Tap to send now" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.GraphicEq, null, Modifier.size(28.dp), tint = colors.onPrimary)
            }
        }
    }
}

/** Shown above the reply that hands-free mode is reading aloud. */
@Composable
internal fun ReadingAloudRow(onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SpeakingWave(colors.primary)
        Text(
            "Reading aloud",
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = colors.primary,
        )
        Surface(
            onClick = onSkip,
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Row(
                Modifier.height(32.dp).padding(start = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp))
                Text("Skip", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun ContextChip(chip: DockChip) {
    val colors = MaterialTheme.colorScheme
    val content =
        @Composable {
            Row(
                Modifier.height(28.dp).padding(start = 8.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(chip.icon, null, Modifier.size(16.dp), tint = colors.primary)
                Text(
                    chip.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    val shape = RoundedCornerShape(14.dp)
    val semantics = Modifier.semantics { contentDescription = chip.description }
    if (chip.onClick != null)
        Surface(
            onClick = chip.onClick,
            shape = shape,
            color = colors.surfaceContainerLowest,
            modifier = semantics,
        ) {
            content()
        }
    else Surface(shape = shape, color = colors.surfaceContainerLowest, modifier = semantics) { content() }
}

@Composable
internal fun DockCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Dp = 56.dp,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(size).semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
    }
}

@Composable
private fun LabeledCircleButton(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DockCircleButton(icon, description, onClick)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The 88dp primary button in its halo. A tap runs [onTap]; with [onHoldStart], pressing past the
 * long-press timeout starts push-to-talk and releasing runs [onHoldEnd].
 */
@Composable
internal fun VoiceHeroButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    onHoldStart: (() -> Unit)? = null,
    onHoldEnd: () -> Unit = {},
    buttonSize: Dp = 88.dp,
    haloSize: Dp = 112.dp,
    corner: Dp = 32.dp,
    iconSize: Dp = 40.dp,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "voice-press")
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier.size(haloSize)
            .background(
                if (enabled) colors.primaryContainer else colors.surfaceContainerHighest,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier
                .size(buttonSize)
                .scale(scale)
                .shadow(
                    if (enabled) 8.dp else 0.dp,
                    shape,
                    ambientColor = colors.primary,
                    spotColor = colors.primary,
                )
                .clip(shape)
                .background(if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.12f))
                .semantics {
                    role = Role.Button
                    contentDescription = description
                    if (enabled) {
                        onClick {
                            onTap()
                            true
                        }
                        if (onHoldStart != null)
                            onLongClick("Hold to talk") {
                                onTap()
                                true
                            }
                    }
                }
                .pointerInput(enabled, onHoldStart != null) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown()
                        pressed = true
                        var released = false
                        val up =
                            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                waitForUpOrCancellation().also { released = true }
                            }
                        when {
                            released -> if (up != null) onTap()
                            onHoldStart == null -> if (waitForUpOrCancellation() != null) onTap()
                            else -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onHoldStart()
                                // Starting a capture usually swaps this button for the listening
                                // panel, which cancels this gesture: that is not a release, so only
                                // a real lift ends it here. [releaseEndsHold] on the container
                                // catches the lift after the swap.
                                if (waitForUpOrCancellation() != null) onHoldEnd()
                            }
                        }
                        pressed = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                Modifier.size(iconSize),
                tint = if (enabled) colors.onPrimary else colors.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

@Composable
internal fun Waveform(
    levels: List<Float>,
    barWidth: Dp,
    gap: Dp,
    maxHeight: Dp,
    minHeight: Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val width = barWidth * levels.size + gap * (levels.size - 1).coerceAtLeast(0)
    Canvas(modifier.width(width).height(maxHeight)) {
        val bar = barWidth.toPx()
        val step = bar + gap.toPx()
        val min = minHeight.toPx()
        levels.forEachIndexed { index, level ->
            val height = min + (size.height - min) * level
            drawRoundRect(
                color,
                topLeft = Offset(index * step, (size.height - height) / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** A gently moving waveform for speech output, which has no input level to follow. */
@Composable
internal fun SpeakingWave(color: Color, barWidth: Dp = 3.dp, maxHeight: Dp = 18.dp) {
    val phase by
        rememberInfiniteTransition(label = "speaking")
            .animateFloat(
                0f,
                (2 * Math.PI).toFloat(),
                infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
                label = "speaking-phase",
            )
    val levels = List(8) { 0.25f + 0.75f * abs(sin(phase + it * 0.8f)) }
    Waveform(
        levels,
        barWidth = barWidth,
        gap = barWidth,
        maxHeight = maxHeight,
        minHeight = maxHeight / 3.6f,
        color = color,
    )
}

@Composable
private fun elapsedLabel(startedAt: Long): String {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val seconds = ((now - startedAt) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Push-to-talk across the swap: holding the mic starts a capture, which replaces the mic with the
 * listening panel. The finger's eventual lift lands on whatever is there by then, so the release is
 * tracked on a container that outlives the swap (see [releaseEndsHold]).
 */
@Stable
internal class PushToTalk(private val voice: VoiceInput) {
    internal var holding = false

    fun begin() {
        holding = true
    }

    fun release() {
        if (!holding) return
        holding = false
        voice.finish(VoiceFinish.Send)
    }
}

@Composable
internal fun rememberPushToTalk(voice: VoiceInput): PushToTalk {
    val pushToTalk = remember(voice) { PushToTalk(voice) }
    // A capture that ended on its own (error, "Edit text") is no longer held.
    LaunchedEffect(voice.listening) { if (!voice.listening) pushToTalk.holding = false }
    return pushToTalk
}

/** Ends a held capture when every finger is up. Watches without consuming anything. */
internal fun Modifier.releaseEndsHold(pushToTalk: PushToTalk): Modifier =
    pointerInput(pushToTalk) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (pushToTalk.holding && event.changes.none { it.pressed }) pushToTalk.release()
            }
        }
    }
