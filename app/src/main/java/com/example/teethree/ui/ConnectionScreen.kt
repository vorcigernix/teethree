package com.example.teethree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    loading: Boolean,
    error: String?,
    onConnect: (url: String, code: String) -> Unit,
    onBack: (() -> Unit)? = null,
    initialUrl: String = "",
    initialCode: String = "",
    onT3Connect: (() -> Unit)? = null,
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var code by rememberSaveable(initialCode) { mutableStateOf(initialCode) }
    val canConnect = url.isNotBlank() && !loading
    // App errors also go to a snackbar and are cleared when it times out. This screen has no
    // snackbar, so keep the last one in view until the user changes something or tries again;
    // otherwise a failed connect looks like nothing happened.
    var shownError by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(error) { if (error != null) shownError = error }
    // Android 17 blocks local-network sockets until the user allows it. Ask right when they tap
    // Connect for a local address, then carry on either way: a refusal gets a clear error.
    val context = androidx.compose.ui.platform.LocalContext.current
    var waitingForPermission by remember { mutableStateOf<Pair<String, String>?>(null) }
    val permission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) {
            waitingForPermission?.let { (u, c) -> onConnect(u, c) }
            waitingForPermission = null
        }
    fun connect() {
        shownError = null
        val target = url.trim() to code.trim()
        if (
            android.os.Build.VERSION.SDK_INT >= 37 &&
                !com.example.teethree.localNetworkPermissionGranted(context) &&
                com.example.teethree.looksLikeLocalNetwork(target.first)
        ) {
            waitingForPermission = target
            permission.launch(android.Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else onConnect(target.first, target.second)
    }
    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Connect") },
                navigationIcon = {
                    if (onBack != null)
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                },
            )
        }
    ) { insets ->
        // The whole width scrolls, so a swipe in the side margins of a wide screen works too.
        Box(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                LocalNetworkPermissionCard()
                if (onT3Connect != null)
                    OutlinedButton(
                        onClick = onT3Connect,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Sign in with T3 Connect")
                    }
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "Bring your workspace with you",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "Open Connections on your T3 Code server, then copy its pairing link or enter its address and pairing code.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        shownError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Server URL or pairing link") },
                    supportingText = {
                        Text("Local network, tailnet, and public server addresses are supported.")
                    },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                    shape = MaterialTheme.shapes.medium,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        shownError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pairing code") },
                    supportingText = {
                        Text("Leave empty when your pairing link already includes a code.")
                    },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                if (canConnect) connect()
                            }
                        ),
                    shape = MaterialTheme.shapes.medium,
                )
                shownError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Button(
                    onClick = ::connect,
                    enabled = canConnect,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
                ) {
                    Text(if (loading) "Connecting…" else "Connect to workspace")
                }
                Text(
                    "Your agents and files stay on the server you connect to.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
