package com.example.teethree.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.teethree.data.ComposerDraft
import com.example.teethree.data.RuntimeRequest
import com.example.teethree.data.T3Thread
import com.example.teethree.data.TimelineItem
import com.example.teethree.data.composerEditableText
import com.example.teethree.data.replaceComposerDraftText

/** Voice state for one open conversation, shared by the chat screen and the tabletop layout. */
@Stable
internal class VoiceConversation(
    val voice: VoiceInput,
    val handsFree: Boolean,
    val setHandsFree: (Boolean) -> Unit,
    val speakingId: String?,
    val skipSpeaking: () -> Unit,
    /** The approval a short spoken answer would settle, if any. */
    val approval: RuntimeRequest?,
    /** Routes a finished capture: an approval answer, or a message to send (or edit). */
    val handle: (String, VoiceFinish) -> Unit,
)

/**
 * Hands-free behaviour: new replies are read aloud, and the mic opens once per turn when the agent
 * is waiting on you. Short "allow" / "always" / "no" answers settle the pending approval.
 */
@Composable
internal fun rememberVoiceConversation(
    thread: T3Thread,
    timeline: List<TimelineItem>,
    pendingRequests: List<RuntimeRequest>,
    draft: ComposerDraft,
    editable: Boolean,
    canAnswer: Boolean,
    /** Set while the desktop is away: spoken approvals can't land, replies queue up. */
    offlineHost: String? = null,
    onRespond: (String, String) -> Unit,
    onSend: () -> Unit,
    onDraftChange: (String) -> Unit,
): VoiceConversation {
    val context = LocalContext.current
    val inputMode = LocalComposerInputMode.current
    val handsFree = inputMode.handsFree
    val voice = rememberVoiceInput()
    var micGranted by remember { mutableStateOf(hasMicPermission(context)) }
    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            micGranted = it
        }
    // Created on first use: the engine takes a moment to bind and most chats never need it.
    var speaker by remember { mutableStateOf<Speaker?>(null) }
    DisposableEffect(Unit) { onDispose { speaker?.shutdown() } }
    val approval =
        pendingRequests.firstOrNull {
            it.canRespond && it.questions.isEmpty() && it.options.isNotEmpty()
        }
    val latestAssistant = timeline.lastOrNull { it.type == "assistant" }
    val latestRequests = rememberUpdatedState(pendingRequests)
    val latestDraft = rememberUpdatedState(draft)
    val latestRespond = rememberUpdatedState(onRespond)
    val latestSend = rememberUpdatedState(onSend)
    val latestDraftChange = rememberUpdatedState(onDraftChange)
    val latestCanAnswer = rememberUpdatedState(canAnswer)
    val latestOfflineHost = rememberUpdatedState(offlineHost)
    val handle: (String, VoiceFinish) -> Unit = { text, finish ->
        val request =
            latestRequests.value.firstOrNull { it.canRespond && it.questions.isEmpty() }
        val decision =
            request
                ?.takeIf { finish == VoiceFinish.Send }
                ?.let { pending ->
                    approvalDecision(
                        text,
                        pending.options.filter { it.warning == null }.map { it.decision },
                    )
                }
        val away = latestOfflineHost.value
        if (request != null && decision != null && latestCanAnswer.value)
            latestRespond.value(request.id, decision)
        // "Allow" must never turn into a queued chat message.
        else if (request != null && decision != null && away != null)
            voice.error = "Approvals unlock when $away is back"
        else if (text.isNotBlank() && !isDiscardPhrase(text)) {
            val current = latestDraft.value
            val next =
                replaceComposerDraftText(
                    current,
                    joinSpeech(composerEditableText(current.text, current.context), text),
                )
            latestDraftChange.value(next)
            if (finish == VoiceFinish.Send) {
                SpokenMessages.record(context, next, voice.lastDurationMs)
                latestSend.value()
            }
        }
    }
    var spokenId by rememberSaveable { mutableStateOf<String?>(null) }
    var autoListenKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(handsFree) {
        if (handsFree) {
            if (speaker == null) speaker = Speaker(context)
            // Only replies that arrive from now on are read; history stays quiet.
            spokenId = latestAssistant?.id
            autoListenKey = null
            if (!micGranted) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            speaker?.silence()
            if (voice.listening) voice.cancel()
        }
    }
    // Opening another chat neither reads its last reply nor carries a capture across.
    LaunchedEffect(thread.id) {
        spokenId = latestAssistant?.id
        autoListenKey = null
        if (voice.listening) voice.cancel()
        speaker?.silence()
    }
    LaunchedEffect(handsFree, latestAssistant?.id, latestAssistant?.streaming) {
        val reply = latestAssistant ?: return@LaunchedEffect
        if (!handsFree || reply.streaming || reply.id == spokenId) return@LaunchedEffect
        spokenId = reply.id
        val spoken = speechText(reply.text)
        if (spoken.isBlank()) return@LaunchedEffect
        if (voice.listening) voice.cancel()
        speaker?.speak(reply.id, spoken) {}
    }
    // The mic opens once per turn when the agent waits on you: after a reply is read (or
    // skipped), or when an approval arrives. Tapping the mic covers everything else.
    val speakingId = speaker?.speakingId
    LaunchedEffect(
        handsFree,
        micGranted,
        approval?.id,
        thread.activeRunId,
        speakingId,
        latestAssistant?.id,
        voice.listening,
    ) {
        if (!handsFree || !micGranted || !voice.available) return@LaunchedEffect
        if (voice.listening || speakingId != null || !editable || !canAnswer)
            return@LaunchedEffect
        if (approval == null && thread.activeRunId != null) return@LaunchedEffect
        val key = "${latestAssistant?.id}:${approval?.id}"
        if (key == autoListenKey) return@LaunchedEffect
        autoListenKey = key
        voice.start { text, finish -> handle(text, finish) }
    }
    return VoiceConversation(
        voice = voice,
        handsFree = handsFree,
        setHandsFree = inputMode.onHandsFreeChange,
        speakingId = speakingId,
        skipSpeaking = { speaker?.skip() },
        approval = approval,
        handle = handle,
    )
}
