package com.example.teethree.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.teethree.data.ApprovalOption
import com.example.teethree.data.ComposerDraft
import com.example.teethree.data.RuntimeRequest
import com.example.teethree.data.T3Thread
import com.example.teethree.data.TimelineItem
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog

/**
 * The chat for a half-folded foldable resting on a table: the agent's latest words fill the upper
 * half, and the lower half is a voice console with the pending decision, the mic, and what it hears.
 *
 * [hingeTop] and [hingeBottom] are the fold's edges in window pixels.
 */
@Composable
internal fun TabletopChat(
    hingeTop: Float,
    hingeBottom: Float,
    thread: T3Thread,
    projectLabel: String?,
    modelLabel: String,
    modeLabel: String,
    timeline: List<TimelineItem>,
    pendingRequests: List<RuntimeRequest>,
    draft: ComposerDraft,
    busy: Boolean,
    canOperate: Boolean,
    onBack: () -> Unit,
    onKeyboard: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRespond: (String, String) -> Unit,
    onAttach: (Uri) -> Unit,
) {
    val editable = !thread.archived && !com.example.teethree.data.providerManagedChild(thread)
    val conversation =
        rememberVoiceConversation(
            thread,
            timeline,
            pendingRequests,
            draft,
            editable = editable,
            canAnswer = canOperate && !busy,
            onRespond = onRespond,
            onSend = onSend,
            onDraftChange = onDraftChange,
        )
    val voice = conversation.voice
    val startVoice = rememberVoiceStarter(voice, conversation.handle)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(onAttach)
        }
    val density = LocalDensity.current
    var top by remember { mutableFloatStateOf(0f) }
    val upper = with(density) { (hingeTop - top).coerceAtLeast(0f).toDp() }
    val fold = with(density) { (hingeBottom - hingeTop).coerceAtLeast(0f).toDp() }
    val reply = timeline.lastOrNull { it.type == "assistant" }
    val lastSegment = remember(timeline) { lastActivitySegment(timeline) }
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .onGloballyPositioned { top = it.boundsInWindow().top }
            .testTag("tabletop-chat")
    ) {
        Column(Modifier.fillMaxWidth().height(upper).statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 24.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to conversations")
                }
                Text(
                    listOfNotNull(thread.title, projectLabel).joinToString("  ·  "),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (editable) HandsFreeToggle(conversation.handsFree, conversation.setHandsFree)
            }
            SpokenReply(
                speaking = conversation.speakingId != null,
                working = thread.activeRunId != null,
                reply = reply?.let { speechText(it.text) }.orEmpty(),
                segment = lastSegment,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            Modifier.fillMaxWidth().height(fold).background(MaterialTheme.colorScheme.surfaceContainer)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .navigationBarsPadding()
        ) {
            // A flip phone's half is too narrow for three columns: one card sits beside the mic.
            val wide = maxWidth >= 720.dp
            Row(
                Modifier.fillMaxSize()
                    .padding(
                        horizontal = if (wide) 32.dp else 16.dp,
                        vertical = if (wide) 24.dp else 16.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val sideCard = Modifier.weight(1f).fillMaxHeight()
                val approval = conversation.approval
                val lastSent = timeline.lastOrNull { it.type == "user" }?.text
                when {
                    approval != null ->
                        TabletopApproval(approval, canOperate && !busy, onRespond, sideCard)
                    !wide && voice.listening ->
                        HeardCard(lastSent, voice, conversation.handsFree, sideCard)
                    thread.activeRunId != null ->
                        TabletopRunCard(lastSegment, canOperate, onStop, sideCard)
                    !wide -> HeardCard(lastSent, voice, conversation.handsFree, sideCard)
                    else -> TabletopContextCard(projectLabel, modelLabel, modeLabel, sideCard)
                }
                MicColumn(
                    voice = voice,
                    speaking = conversation.speakingId != null,
                    enabled = editable && canOperate,
                    compact = !wide,
                    onStart = startVoice,
                    onPause = {
                        if (voice.listening) voice.cancel()
                        else if (conversation.speakingId != null) conversation.skipSpeaking()
                    },
                    onKeyboard = onKeyboard,
                    onAttach = { picker.launch(arrayOf("*/*")) },
                )
                if (wide) HeardCard(lastSent, voice, conversation.handsFree, sideCard)
            }
        }
    }
}

/** The steps of the most recent run of activity, for the progress line under the reply. */
private fun lastActivitySegment(timeline: List<TimelineItem>): List<TimelineItem> {
    val end = timeline.indexOfLast { it.type != "user" && it.type != "assistant" }
    if (end < 0) return emptyList()
    var start = end
    while (start > 0 && timeline[start - 1].type.let { it != "user" && it != "assistant" }) start--
    return timeline.subList(start, end + 1)
}

@Composable
private fun SpokenReply(
    speaking: Boolean,
    working: Boolean,
    reply: String,
    segment: List<TimelineItem>,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val sidePadding = (maxWidth * 0.09f).coerceIn(24.dp, 96.dp)
        Column(
            Modifier.fillMaxSize().padding(horizontal = sidePadding, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            if (speaking || working)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (speaking) SpeakingWave(colors.primary, barWidth = 4.dp, maxHeight = 24.dp)
                    else
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            color = colors.primary,
                            strokeWidth = 2.dp,
                        )
                    Text(
                        if (speaking) "Speaking" else "Working…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                    )
                }
            // Long replies shrink to fit the half instead of scrolling out of reach.
            BasicText(
                reply.ifEmpty { "Tap the mic and tell the agent what to do." },
                Modifier.weight(1f, fill = false),
                style =
                    MaterialTheme.typography.displayMedium.copy(
                        color = if (reply.isEmpty()) colors.onSurfaceVariant else colors.onSurface,
                        letterSpacing = (-0.4).sp,
                    ),
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 36.sp),
                overflow = TextOverflow.Ellipsis,
            )
            if (segment.isNotEmpty()) {
                val working = segment.any { it.stepState() == StepState.Working }
                val failed = segment.any { it.stepState() == StepState.Failed }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    activitySummary(segment).forEach { part ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            when {
                                working ->
                                    CircularProgressIndicator(
                                        Modifier.size(14.dp),
                                        color = colors.primary,
                                        strokeWidth = 1.5.dp,
                                    )
                                failed ->
                                    Icon(
                                        Icons.Outlined.ErrorOutline,
                                        null,
                                        Modifier.size(18.dp),
                                        tint = colors.error,
                                    )
                                else ->
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        null,
                                        Modifier.size(18.dp),
                                        tint = T3Colors.success,
                                    )
                            }
                            Text(
                                part,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletopApproval(
    request: RuntimeRequest,
    enabled: Boolean,
    onRespond: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var confirmation by remember { mutableStateOf<ApprovalOption?>(null) }
    val choose: (ApprovalOption) -> Unit = {
        if (it.warning != null) confirmation = it else onRespond(request.id, it.decision)
    }
    val primary = request.options.firstOrNull { it.decision == "accept" }
    val others =
        request.options.filter { it != primary }.sortedByDescending { approvalKeyword(it.decision) == "always" }
    val spoken =
        request.options.sortedByDescending { it.decision == "accept" }.mapNotNull {
            approvalKeyword(it.decision)?.takeIf { _ -> it.warning == null }
        }
    TabletopCard(colors.tertiaryContainer, colors.onTertiaryContainer, modifier) {
        Text(
            request.prompt.ifBlank { "Your approval is needed" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (spoken.isNotEmpty())
            Text(spokenChoices(spoken.reversed()), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        primary?.let { option ->
            Surface(
                onClick = { choose(option) },
                enabled = enabled,
                shape = CircleShape,
                color = colors.tertiary,
                contentColor = colors.onTertiary,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            others.forEach { option ->
                Surface(
                    onClick = { choose(option) },
                    enabled = enabled,
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, colors.tertiary),
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    confirmation?.let { option ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(option.label) },
            text = { Text(option.warning.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        onRespond(request.id, option.decision)
                    },
                    enabled = enabled,
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TabletopRunCard(
    segment: List<TimelineItem>,
    canOperate: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    TabletopCard(colors.surfaceContainerLowest, colors.onSurface, modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), color = colors.primary, strokeWidth = 2.dp)
            Text("Agent running", style = MaterialTheme.typography.headlineSmall)
        }
        if (segment.isNotEmpty())
            Text(
                summaryLine(activitySummary(segment)),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant,
            )
        Text(
            "Talk to steer it. It hears you between steps.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onStop, enabled = canOperate) {
            Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
            Text("Stop", Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun TabletopContextCard(
    projectLabel: String?,
    modelLabel: String,
    modeLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    TabletopCard(colors.surfaceContainerLowest, colors.onSurface, modifier) {
        Text("Ready when you are", style = MaterialTheme.typography.headlineSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            projectLabel?.let { ContextChip(DockChip(it, Icons.Outlined.Folder, "Project $it", null)) }
            ContextChip(DockChip(modelLabel, Icons.Outlined.AutoAwesome, "Model $modelLabel", null))
            ContextChip(DockChip(modeLabel, Icons.Outlined.Shield, modeLabel, null))
        }
    }
}

@Composable
private fun TabletopCard(
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(32.dp),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            content = body,
        )
    }
}

@Composable
private fun MicColumn(
    voice: VoiceInput,
    speaking: Boolean,
    enabled: Boolean,
    compact: Boolean,
    onStart: (hold: Boolean) -> Unit,
    onPause: () -> Unit,
    onKeyboard: () -> Unit,
    onAttach: () -> Unit,
) {
    val outer = if (compact) 150.dp else 200.dp
    val halo = if (compact) 124.dp else 160.dp
    val button = if (compact) 96.dp else 120.dp
    val corner = if (compact) 36.dp else 44.dp
    val icon = if (compact) 44.dp else 52.dp
    Column(
        Modifier.width(if (compact) 176.dp else 260.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
    ) {
        Box(
            Modifier.size(outer)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (voice.listening)
                VoiceHeroButton(
                    Icons.Default.GraphicEq,
                    "Listening. Tap to send now",
                    enabled = true,
                    onTap = { voice.finish(VoiceFinish.Send) },
                    buttonSize = button,
                    haloSize = halo,
                    corner = corner,
                    iconSize = icon,
                )
            else
                VoiceHeroButton(
                    Icons.Default.Mic,
                    "Talk to the agent",
                    enabled = enabled,
                    onTap = { onStart(false) },
                    onHoldStart = { onStart(true) },
                    onHoldEnd = { voice.finish(VoiceFinish.Send) },
                    buttonSize = button,
                    haloSize = halo,
                    corner = corner,
                    iconSize = icon,
                )
        }
        Text(
            when {
                voice.listening -> "Listening…"
                speaking -> "Speaking…"
                else -> voice.error ?: "Tap to talk"
            },
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
            color =
                if (voice.error != null && !voice.listening) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 16.dp)) {
            val side = if (compact) 48.dp else 56.dp
            DockCircleButton(Icons.Outlined.Keyboard, "Type instead", onKeyboard, size = side)
            DockCircleButton(
                Icons.Default.Pause,
                if (voice.listening) "Stop listening" else "Stop speaking",
                onPause,
                enabled = voice.listening || speaking,
                size = side,
            )
            DockCircleButton(
                Icons.Outlined.AddPhotoAlternate,
                "Attach image or file",
                onAttach,
                enabled = enabled,
                size = side,
            )
        }
    }
}

@Composable
private fun HeardCard(
    lastSent: String?,
    voice: VoiceInput,
    handsFree: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerLowest, shape = RoundedCornerShape(32.dp), modifier = modifier) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (lastSent != null) {
                Text(
                    "You",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    lastSent,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 25.sp),
                    fontWeight = FontWeight.Normal,
                    color = colors.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                HorizontalDivider(color = colors.outlineVariant)
            }
            if (voice.listening) {
                Text(
                    "Hearing now",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.primary,
                )
                Text(
                    joinSpeech(voice.transcript, voice.partial).ifEmpty { "…" },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Waveform(
                    voice.levels.takeLast(18),
                    barWidth = 4.dp,
                    gap = 3.dp,
                    maxHeight = 30.dp,
                    minHeight = 6.dp,
                    color = colors.primary,
                )
            } else
                Text(
                    if (handsFree) "I'll listen when the agent needs you."
                    else "Turn on Hands-free to talk without touching the screen.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
        }
    }
}
