package com.example.teethree.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal data class AppCommand(
    val id: String,
    val title: String,
    val shortcut: String? = null,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

@Composable
internal fun CommandPalette(commands: List<AppCommand>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.padding(20.dp).widthIn(max = 600.dp).fillMaxWidth().heightIn(max = 620.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    query,
                    { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Find a command") },
                )
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(
                        commands.filter { it.title.contains(query, ignoreCase = true) },
                        key = { it.id },
                    ) { command ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    command.title,
                                    color =
                                        if (command.enabled) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent =
                                command.shortcut?.let {
                                    { Text(it, style = MaterialTheme.typography.labelSmall) }
                                },
                            modifier =
                                Modifier.clickable(enabled = command.enabled) {
                                    onDismiss()
                                    command.run()
                                },
                        )
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
        }
    }
}
