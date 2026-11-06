package com.example.teethree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clerk.ui.auth.AuthView
import com.clerk.ui.userprofile.UserProfileView
import com.example.teethree.data.SavedConnection
import com.example.teethree.data.auth.CloudEnvironment
import com.example.teethree.data.auth.CloudEnvironmentStatus
import com.example.teethree.data.auth.NativeCloudConnections
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class ConnectConfirmation(
    val title: String,
    val message: String,
    val action: suspend () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun T3ConnectScreen(
    cloud: NativeCloudConnections,
    connections: List<SavedConnection>,
    onConnect: (SavedConnection) -> Unit,
    onBack: () -> Unit,
    onRemoved: (environmentId: String) -> Unit = {},
) {
    val account by cloud.account.state.collectAsStateWithLifecycle()
    val identity = account.identity
    val scope = rememberCoroutineScope()
    var environments by remember(identity) { mutableStateOf<List<CloudEnvironment>>(emptyList()) }
    var statuses by
        remember(identity) { mutableStateOf<Map<String, CloudEnvironmentStatus>>(emptyMap()) }
    var busy by remember(identity) { mutableStateOf(false) }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var profile by rememberSaveable(identity?.sessionId) { mutableStateOf(false) }
    var confirmation by remember(identity) { mutableStateOf<ConnectConfirmation?>(null) }

    suspend fun refresh() {
        val loaded = cloud.relay?.environments().orEmpty()
        if (cloud.account.state.value.identity == identity) environments = loaded
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (cloud.account.state.value.identity == identity)
                    error = failure.message ?: "T3 Connect could not complete this request."
            } finally {
                if (cloud.account.state.value.identity == identity) busy = false
            }
        }
    }
    LaunchedEffect(identity) {
        if (identity != null) {
            busy = true
            try {
                refresh()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (cloud.account.state.value.identity == identity)
                    error = failure.message ?: "T3 Connect environments could not be loaded."
            } finally {
                if (cloud.account.state.value.identity == identity) busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("T3 Connect") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                cloud.config == null ->
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            cloud.configurationError
                                ?: "T3 Connect sign-in is not configured in this build."
                        )
                        Text(
                            "You can still connect directly using an environment's pairing link.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = onBack) { Text("Back to direct connections") }
                    }
                account.error != null ->
                    Column(Modifier.padding(24.dp)) {
                        Text(account.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = cloud.account::retry) { Text("Retry sign-in") }
                    }
                !account.initialized -> Text("Loading T3 Connect sign-in…", Modifier.padding(24.dp))
                identity == null ->
                    AuthView(modifier = Modifier.fillMaxSize(), isDismissible = false)
                profile -> UserProfileView(onDismiss = { profile = false }, isDismissible = true)
                else ->
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    ) {
                        item {
                            Text("Your computers", style = MaterialTheme.typography.headlineSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { action { refresh() } }, enabled = !busy) {
                                    Text("Refresh")
                                }
                                TextButton(onClick = { profile = true }, enabled = !busy) {
                                    Text("Account")
                                }
                                TextButton(
                                    onClick = { action { cloud.signOut() } },
                                    enabled = !busy,
                                ) {
                                    Text("Sign out")
                                }
                            }
                            if (busy) Text("Working…", style = MaterialTheme.typography.labelMedium)
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        if (environments.isEmpty() && !busy)
                            item {
                                Text(
                                    "No computers are linked. Pair directly with a computer, then enable T3 Connect below."
                                )
                            }
                        items(environments, key = { it.environmentId }) { environment ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        environment.label,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        environment.endpoint.httpBaseUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    statuses[environment.environmentId]?.let { status ->
                                        Text(
                                            if (status.online) "Online"
                                            else if (status.reason == "tunnel_released")
                                                "Tunnel released"
                                            else "Offline"
                                        )
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                action { onConnect(cloud.connect(environment)) }
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text("Connect")
                                        }
                                        TextButton(
                                            onClick = {
                                                action {
                                                    val status = cloud.relay?.status(environment)
                                                    if (
                                                        status != null &&
                                                            cloud.account.state.value.identity ==
                                                                identity
                                                    )
                                                        statuses =
                                                            statuses +
                                                                (environment.environmentId to
                                                                    status)
                                                }
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text("Check status")
                                        }
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = {
                                                confirmation =
                                                    ConnectConfirmation(
                                                        "Release tunnel?",
                                                        "This disconnects remote access while keeping the computer linked. Start T3 Code on that computer to provision its tunnel again.",
                                                    ) {
                                                        cloud.relay?.releaseTunnel(
                                                            environment.environmentId
                                                        )
                                                        statuses =
                                                            statuses - environment.environmentId
                                                    }
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text("Release tunnel")
                                        }
                                        TextButton(
                                            onClick = {
                                                confirmation =
                                                    ConnectConfirmation(
                                                        "Unlink computer?",
                                                        "This removes this computer from your T3 Connect account.",
                                                    ) {
                                                        cloud.unlink(environment.environmentId)
                                                        onRemoved(environment.environmentId)
                                                        refresh()
                                                    }
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text("Unlink")
                                        }
                                    }
                                }
                            }
                        }
                        val local = connections.filter {
                            it.cloudAccountId == null && "relay:write" in it.scopes
                        }
                        if (local.isNotEmpty())
                            item {
                                Text(
                                    "Link a paired computer",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                        items(local, key = { "local:${it.id}" }) { connection ->
                            OutlinedButton(
                                onClick = {
                                    confirmation =
                                        ConnectConfirmation(
                                            "Enable T3 Connect?",
                                            "Link ${connection.label ?: connection.baseUrl} to this account and enable its managed remote tunnel and notifications.",
                                        ) {
                                            cloud.authorization?.linkLocal(connection)
                                            refresh()
                                        }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(connection.label ?: connection.baseUrl)
                            }
                        }
                    }
            }
        }
    }
    confirmation?.let { choice ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(choice.title) },
            text = { Text(choice.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        action(choice.action)
                    },
                    enabled = !busy,
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}
