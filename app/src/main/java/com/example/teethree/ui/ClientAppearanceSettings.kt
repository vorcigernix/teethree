package com.example.teethree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.teethree.ClientAppearancePreferences
import com.example.teethree.normalizeClientAppearance
import com.example.teethree.resolveClientAppearance
import kotlin.math.roundToInt

@Composable
fun ClientAppearanceSettings(
    appearance: ClientAppearancePreferences,
    onAppearanceChange: (ClientAppearancePreferences) -> Unit,
) {
    val value = normalizeClientAppearance(appearance)
    val resolved = resolveClientAppearance(value)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        AppearanceSlider(
            "Text size",
            value.baseFontSize.toFloat(),
            11f..22f,
            1f,
            "${(resolved.textScale * 100).roundToInt()}%",
        ) {
            onAppearanceChange(value.copy(baseFontSize = it.roundToInt()))
        }
        AppearanceToggle("Custom code and diff font size", value.codeFontSize != null) {
            onAppearanceChange(value.copy(codeFontSize = if (it) resolved.codeFontSize else null))
        }
        if (value.codeFontSize != null)
            AppearanceSlider(
                "Code font size",
                value.codeFontSize.toFloat(),
                8f..18f,
                1f,
                "${value.codeFontSize} sp",
            ) {
                onAppearanceChange(value.copy(codeFontSize = it.roundToInt()))
            }
        AppearanceToggle("Wrap code and diffs", value.codeWordBreak) {
            onAppearanceChange(value.copy(codeWordBreak = it))
        }
        AppearanceToggle("Custom terminal font size", value.terminalFontSize != null) {
            onAppearanceChange(
                value.copy(terminalFontSize = if (it) resolved.terminalFontSize else null)
            )
        }
        if (value.terminalFontSize != null)
            AppearanceSlider(
                "Terminal font size",
                value.terminalFontSize,
                6f..14f,
                .5f,
                "${value.terminalFontSize} sp",
            ) {
                onAppearanceChange(value.copy(terminalFontSize = (it * 2).roundToInt() / 2f))
            }
        AppearanceToggle("Render math", value.mathRenderingEnabled) {
            onAppearanceChange(value.copy(mathRenderingEnabled = it))
        }
        Text(
            "Code and terminal sizes follow text size unless customized. Your device's accessibility text scale also applies.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { onAppearanceChange(ClientAppearancePreferences()) }) {
            Text("Reset appearance")
        }
    }
}

@Composable
private fun AppearanceSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    description: String,
    onChange: (Float) -> Unit,
) {
    var pending by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label · $description", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = pending,
            onValueChange = { pending = it },
            valueRange = range,
            steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
            onValueChangeFinished = { onChange(pending) },
        )
    }
}

@Composable
private fun AppearanceToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
