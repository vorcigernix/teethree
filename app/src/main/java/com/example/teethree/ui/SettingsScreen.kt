package com.example.teethree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.teethree.data.SavedConnection
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    theme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    connections: List<SavedConnection>,
    activeConnectionId: String?,
    onConnectionSelect: (SavedConnection) -> Unit,
    onDisconnect: () -> Unit,
    onConnectionDelete: (SavedConnection) -> Unit,
    onAddConnection: () -> Unit,
    onBack: () -> Unit,
    onEnvironmentSettings: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onUsage: () -> Unit = {},
    onT3Connect: () -> Unit = {},
    onCommands: () -> Unit = {},
    onIncomingShares: () -> Unit = {},
) {
    var deleting by remember { mutableStateOf<SavedConnection?>(null) }
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { LocalNetworkPermissionCard() }
                item {
                    Text(
                        "Appearance",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item {
                    Card(
                        modifier = Modifier.selectableGroup(),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                    ) {
                        AppTheme.entries.forEachIndexed { index, option ->
                            if (index > 0)
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 20.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            ListItem(
                                headlineContent = {
                                    Text(
                                        when (option) {
                                            AppTheme.System -> "Use device theme"
                                            AppTheme.Light -> "Light"
                                            AppTheme.Dark -> "Dark"
                                        }
                                    )
                                },
                                supportingContent =
                                    if (option == AppTheme.System) {
                                        { Text("Changes with your device settings") }
                                    } else null,
                                trailingContent = {
                                    RadioButton(selected = theme == option, onClick = null)
                                },
                                modifier =
                                    Modifier.selectable(
                                        selected = theme == option,
                                        onClick = { onThemeChange(option) },
                                        role = Role.RadioButton,
                                    ),
                                colors =
                                    androidx.compose.material3.ListItemDefaults.colors(
                                        containerColor =
                                            MaterialTheme.colorScheme.surfaceContainerLow
                                    ),
                            )
                        }
                    }
                }
                item {
                    Text(
                        "Uses the app’s blue color palette in light or dark mode.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (activeConnectionId != null)
                    item {
                        Card(onClick = onEnvironmentSettings, modifier = Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text("Environment settings") },
                                supportingContent = { Text("Providers and scheduled tasks") },
                            )
                        }
                    }
                item {
                    Card(onClick = onT3Connect, modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text("T3 Connect") },
                            supportingContent = { Text("Account and remote environments") },
                        )
                    }
                }
                item {
                    Card(onClick = onNotifications, modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text("Notifications and widget") },
                            supportingContent = {
                                Text("Agent alerts, ongoing activity, and home-screen usage")
                            },
                        )
                    }
                }
                item {
                    Card(onClick = onUsage, modifier = Modifier.fillMaxWidth()) {
                        ListItem(headlineContent = { Text("Subscription usage") })
                    }
                }
                item {
                    Card(onClick = onCommands, modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text("Commands and keyboard shortcuts") },
                            supportingContent = {
                                Text("Ctrl/Cmd+K to open commands; Ctrl/Cmd+N for a new task")
                            },
                        )
                    }
                }
                item {
                    Card(onClick = onIncomingShares, modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text("Shared drafts") },
                            supportingContent = {
                                Text("Text and files shared from other Android apps")
                            },
                        )
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Connections",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = onAddConnection) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Text("Add")
                        }
                    }
                }
                if (connections.isEmpty()) {
                    item {
                        Card(
                            colors =
                                CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                )
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    "No saved connections",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "Add a server to access your projects and conversations.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                FilledTonalButton(onClick = onAddConnection) {
                                    Text("Add connection")
                                }
                            }
                        }
                    }
                }
                items(connections, key = { it.id }) { connection ->
                    val active = connection.id == activeConnectionId
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor =
                                    if (active) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow
                            )
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        connection.baseUrl,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (active) "Current connection"
                                        else "Saved on this device",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (active)
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = "Current connection",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                IconButton(onClick = { deleting = connection }) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = "Remove ${connection.baseUrl}",
                                    )
                                }
                            }
                            if (active) {
                                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                            } else {
                                FilledTonalButton(onClick = { onConnectionSelect(connection) }) {
                                    Text("Connect")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { connection ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove connection?") },
            text = {
                Text(
                    "Remove ${connection.baseUrl} from this device? You can pair with it again anytime."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        onConnectionDelete(connection)
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
