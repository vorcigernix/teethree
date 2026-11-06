package com.example.teethree.ui.adaptive

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.DatePickerDialog as MaterialDatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun AdaptiveDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val info = currentWindowAdaptiveInfoV2()
    if (info.hasSeparatingFold()) FoldAwareDialog(onDismissRequest, properties, info, content)
    else Dialog(onDismissRequest, properties, content)
}

@Composable
fun AdaptiveAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val info = currentWindowAdaptiveInfoV2()
    if (!info.hasSeparatingFold()) {
        MaterialAlertDialog(
            onDismissRequest,
            confirmButton,
            modifier,
            dismissButton,
            icon,
            title,
            text,
            shape,
            containerColor,
            iconContentColor,
            titleContentColor,
            textContentColor,
            tonalElevation,
            properties,
        )
    } else {
        FoldAwareDialog(onDismissRequest, properties, info) {
            Surface(
                modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = shape,
                color = containerColor,
                tonalElevation = tonalElevation,
            ) {
                Column(Modifier.padding(24.dp)) {
                    if (icon != null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                                icon()
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    if (title != null) {
                        CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    if (text != null) {
                        Box(
                            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        ) {
                            CompositionLocalProvider(LocalContentColor provides textContentColor) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    DialogButtons(confirmButton, dismissButton)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveDatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val info = currentWindowAdaptiveInfoV2()
    if (!info.hasSeparatingFold()) {
        MaterialDatePickerDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            properties = properties,
            content = { content() },
        )
    } else {
        FoldAwareDialog(onDismissRequest, properties, info) {
            Surface(
                modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column {
                    Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        content()
                    }
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        DialogButtons(confirmButton, dismissButton)
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldAwareDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties,
    info: WindowAdaptiveInfo,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest,
        DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = false,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier.fillMaxSize().pointerInput(
                properties.dismissOnClickOutside,
                onDismissRequest,
            ) {
                detectTapGestures { if (properties.dismissOnClickOutside) onDismissRequest() }
            }
        ) {
            FoldSafeContent(info, avoidVerticalHinges = true) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.pointerInput(Unit) { detectTapGestures {} }) { content() }
                }
            }
        }
    }
}

@Composable
private fun DialogButtons(
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)?,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dismissButton != null) dismissButton()
        confirmButton()
    }
}

private fun WindowAdaptiveInfo.hasSeparatingFold() =
    windowPosture.hingeList.any {
        it.isSeparating || it.isOccluding || (!it.isVertical && !it.isFlat)
    }
