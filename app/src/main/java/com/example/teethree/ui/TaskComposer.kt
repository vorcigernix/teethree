package com.example.teethree.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.teethree.data.ComposerDraft
import com.example.teethree.data.composerEditableText
import com.example.teethree.data.replaceComposerDraftText

@Composable
internal fun TaskComposer(
    draft: ComposerDraft,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onAttach: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    actionsEnabled: Boolean = true,
    attachmentRemovalEnabled: Boolean = true,
    submitEnabled: Boolean = true,
    busyLabel: String? = null,
    onCancelTransfer: () -> Unit = {},
    modelLabel: String = "Model",
    modeLabel: String = "Ask first",
    onModelOptions: (() -> Unit)? = null,
    onModeOptions: (() -> Unit)? = null,
    placeholder: String = "Reply, or steer the agent…",
    submitDescription: String = "Send message",
    textFieldTag: String = "chat-composer",
    onAddContext: (() -> Unit)? = null,
    onRemoveContext: (String) -> Unit = {},
    enterBehavior: String = "send",
    onCopyContext: (() -> Unit)? = null,
    onPasteContext: (() -> Unit)? = null,
    projectLabel: String? = null,
    voiceHint: String = "Tap to talk  ·  hold for push-to-talk",
    voiceTarget: String = "reply",
    voice: VoiceInput? = null,
    onVoiceResult: ((String, VoiceFinish) -> Unit)? = null,
    compactListening: Boolean = false,
    compactListeningLabel: String = "Listening…",
    /** Capture can stay on when other actions can't, e.g. while replies queue offline. */
    voiceEnabled: Boolean = actionsEnabled,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val enter = remember { ComposerEnterHandler() }
    val editableText = composerEditableText(draft.text, draft.context)
    val inputMode = LocalComposerInputMode.current
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    var focusWhenShown by remember { mutableStateOf(false) }
    val ownVoice = if (voice == null) rememberVoiceInput() else null
    val activeVoice = voice ?: ownVoice!!
    val latestDraft = rememberUpdatedState(draft)
    val latestSubmit = rememberUpdatedState(onSubmit)
    val latestDraftChange = rememberUpdatedState(onDraftChange)
    val latestVoiceResult = rememberUpdatedState(onVoiceResult)
    // The transcript joins whatever was typed, then sends; "Edit text" opens the keyboard on it
    // instead. Screens with approvals pass [onVoiceResult] to route short answers first.
    val beginVoice =
        rememberVoiceStarter(activeVoice) { text, finish ->
            if (text.isBlank() || isDiscardPhrase(text)) return@rememberVoiceStarter
            val custom = latestVoiceResult.value
            if (custom != null) custom(text, finish)
            else {
                val current = latestDraft.value
                val combined =
                    joinSpeech(composerEditableText(current.text, current.context), text)
                val next = replaceComposerDraftText(current, combined)
                latestDraftChange.value(next)
                if (finish == VoiceFinish.Send) {
                    SpokenMessages.record(context, next, activeVoice.lastDurationMs)
                    latestSubmit.value()
                }
            }
            if (finish == VoiceFinish.Edit) {
                inputMode.onChange(false)
                focusWhenShown = true
            }
        }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(onAttach)
        }
    LaunchedEffect(inputMode.voice, focusWhenShown) {
        if (!inputMode.voice && focusWhenShown) {
            focus.requestFocus()
            focusWhenShown = false
        }
    }
    if (inputMode.voice && editable) {
        Column(modifier.fillMaxWidth()) {
            VoiceDock(
                voice = activeVoice,
                onStartVoice = beginVoice,
                onKeyboard = {
                    inputMode.onChange(false)
                    focusWhenShown = true
                },
                onAttach = { picker.launch(arrayOf("*/*")) },
                enabled = voiceEnabled,
                attachEnabled = actionsEnabled,
                chips =
                    listOfNotNull(
                        projectLabel?.let {
                            DockChip(it, Icons.Outlined.Folder, "Project $it", null)
                        },
                        DockChip(
                            modelLabel,
                            Icons.Outlined.AutoAwesome,
                            "Model $modelLabel. Choose model",
                            onModelOptions?.takeIf { actionsEnabled },
                        ),
                        DockChip(
                            modeLabel,
                            Icons.Outlined.Shield,
                            "$modeLabel. Choose interaction mode",
                            onModeOptions?.takeIf { actionsEnabled },
                        ),
                    ),
                hint = voiceHint,
                target = voiceTarget,
                draftPreview = editableText,
                compactListening = compactListening,
                compactLabel = compactListeningLabel,
                topContent = {
                    AttachmentStrip(draft.attachments, attachmentRemovalEnabled, onRemoveAttachment)
                    ComposerContextStrip(draft.context, attachmentRemovalEnabled, onRemoveContext)
                },
            )
            busyLabel?.let {
                TransferRow(it, onCancelTransfer, Modifier.padding(horizontal = 12.dp))
            }
        }
        return
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color.Transparent,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, if (focused) colors.primary else Color.Transparent),
            modifier = Modifier.glass(RoundedCornerShape(24.dp)),
        ) {
            Column {
                AttachmentStrip(draft.attachments, attachmentRemovalEnabled, onRemoveAttachment)
                ComposerContextStrip(draft.context, attachmentRemovalEnabled, onRemoveContext)
                BasicTextField(
                    value = editableText,
                    onValueChange = { onDraftChange(replaceComposerDraftText(draft, it)) },
                    enabled = editable,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    minLines = 2,
                    maxLines = 6,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
                            .heightIn(min = 44.dp)
                            .focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                enter.handle(
                                    native.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                                        native.keyCode ==
                                            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                                    native.action == android.view.KeyEvent.ACTION_DOWN,
                                    native.action == android.view.KeyEvent.ACTION_UP,
                                    native.repeatCount > 0,
                                    native.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD != 0,
                                    enterBehavior,
                                    native.isShiftPressed,
                                    native.isCtrlPressed,
                                    native.isMetaPressed,
                                    editable && submitEnabled,
                                    onSubmit,
                                )
                            }
                            .testTag(textFieldTag)
                            .semantics { contentDescription = "Message" },
                    decorationBox = { input ->
                        Box {
                            if (editableText.isEmpty()) {
                                Text(
                                    placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            input()
                        }
                    },
                )
                ComposerActions(
                    enabled = actionsEnabled,
                    onAttach = onAttach,
                    onDictated = {
                        onDraftChange(
                            replaceComposerDraftText(
                                draft,
                                listOf(editableText, it)
                                    .filter(String::isNotBlank)
                                    .joinToString(" "),
                            )
                        )
                    },
                    modifier =
                        Modifier.fillMaxWidth().padding(start = 2.dp, end = 8.dp, bottom = 4.dp),
                    onVoiceMode =
                        if (activeVoice.available) ({ inputMode.onChange(true) }) else null,
                    middleContent = {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            onAddContext?.let { action ->
                                ComposerOption(
                                    "Context",
                                    Icons.Outlined.AlternateEmail,
                                    "Add workspace, skill, or chat context",
                                    actionsEnabled,
                                    action,
                                )
                            }
                            onPasteContext?.let { action ->
                                ComposerOption(
                                    "Paste context",
                                    Icons.Outlined.ContentPaste,
                                    "Paste copied task context",
                                    actionsEnabled,
                                    action,
                                )
                            }
                            onCopyContext?.let { action ->
                                ComposerOption(
                                    "Copy context",
                                    Icons.Outlined.ContentCopy,
                                    "Copy task with context",
                                    editable &&
                                        (draft.text.isNotEmpty() || draft.attachments.isNotEmpty()),
                                    action,
                                )
                            }
                            onModelOptions?.let { action ->
                                ComposerOption(
                                    modelLabel,
                                    Icons.Outlined.AutoAwesome,
                                    "Choose model",
                                    actionsEnabled,
                                    action,
                                )
                            }
                            onModeOptions?.let { action ->
                                ComposerOption(
                                    modeLabel,
                                    Icons.Outlined.Shield,
                                    "Choose interaction mode",
                                    actionsEnabled,
                                    action,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        FilledIconButton(
                            onClick = onSubmit,
                            enabled = submitEnabled,
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Default.ArrowUpward, submitDescription, Modifier.size(20.dp))
                        }
                    },
                )
            }
        }
        busyLabel?.let { TransferRow(it, onCancelTransfer) }
    }
}

@Composable
private fun TransferRow(label: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ComposerOption(
    label: String,
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Row(
            Modifier.height(32.dp).padding(horizontal = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
