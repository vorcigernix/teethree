package com.example.teethree.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.teethree.data.terminalComposerContext
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import kotlinx.serialization.json.JsonObject

@Composable
internal fun TerminalContextDialog(
    terminalId: String,
    label: String,
    text: String,
    onDismiss: () -> Unit,
    onAdd: (JsonObject) -> Unit,
) {
    val lines = remember(text) { text.trimEnd('\n').split('\n') }
    var first by remember(text) { mutableStateOf(0) }
    var last by remember(text) { mutableStateOf(lines.lastIndex) }
    var anchor by remember(text) { mutableStateOf<Int?>(null) }
    val selection = lines.subList(first, last + 1).joinToString("\n")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Attach terminal output") },
        text = {
            Column {
                Text(
                    "Captured visible output. Tap the first and last line to choose a range.",
                    style = MaterialTheme.typography.labelMedium,
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(lines) { index, line ->
                        TextButton(
                            onClick = {
                                val start = anchor
                                if (start == null) {
                                    anchor = index
                                    first = index
                                    last = index
                                } else {
                                    first = minOf(start, index)
                                    last = maxOf(start, index)
                                    anchor = null
                                }
                            },
                            modifier =
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                    selected = index in first..last
                                },
                        ) {
                            Text(
                                "${index + 1}  $line",
                                fontFamily = T3MonoFontFamily,
                                color =
                                    if (index in first..last) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (selection.length > 64_000)
                    Text(
                        "Select fewer lines to fit the 64000 character context limit.",
                        color = MaterialTheme.colorScheme.error,
                    )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selection.isNotBlank() && selection.length <= 64_000,
                onClick = {
                    onAdd(
                        terminalComposerContext(
                            terminalId,
                            "$label (visible output)",
                            first + 1L,
                            last + 1L,
                            selection,
                        )
                    )
                },
            ) {
                Text("Attach selected output")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
