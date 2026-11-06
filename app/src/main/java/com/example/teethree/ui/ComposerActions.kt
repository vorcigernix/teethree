package com.example.teethree.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.teethree.data.ChatAttachment

@Composable
internal fun ComposerActions(
    enabled: Boolean,
    onAttach: (Uri) -> Unit,
    onDictated: (String) -> Unit,
    modifier: Modifier = Modifier,
    onVoiceMode: (() -> Unit)? = null,
    middleContent: @Composable RowScope.() -> Unit = {},
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(onAttach)
        }
    val speech =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
            ->
            if (result.resultCode == Activity.RESULT_OK)
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.let(onDictated)
        }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = enabled) {
            Icon(Icons.Default.AttachFile, "Attach file or image", Modifier.size(20.dp))
        }
        middleContent()
        IconButton(
            onClick = {
                if (onVoiceMode != null) {
                    onVoiceMode()
                    return@IconButton
                }
                try {
                    speech.launch(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(
                                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                    )
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(
                            context,
                            "Install a speech recognition service to use dictation.",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
            },
            enabled = enabled,
        ) {
            Icon(
                Icons.Default.Mic,
                if (onVoiceMode != null) "Talk instead" else "Dictate message",
                Modifier.size(20.dp),
            )
        }
        trailingContent()
    }
}

@Composable
internal fun AttachmentStrip(
    attachments: List<ChatAttachment>,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            InputChip(
                selected = false,
                onClick = { onRemove(attachment.id) },
                enabled = enabled,
                label = { Text(attachment.name) },
                trailingIcon = { Icon(Icons.Default.Close, "Remove ${attachment.name}") },
            )
        }
    }
}
