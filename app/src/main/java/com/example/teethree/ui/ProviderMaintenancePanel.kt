package com.example.teethree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.ProviderInstallation
import com.example.teethree.data.ProviderMaintenanceTools
import com.example.teethree.data.T3Repository
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProviderMaintenancePanel(
    repository: T3Repository,
    instanceId: String,
    onChanged: () -> Unit = {},
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val tools = remember(repository) { ProviderMaintenanceTools(repository) }
    val provider = tools.provider(instanceId) ?: return
    val scope = rememberCoroutineScope()
    var installation by remember(instanceId) { mutableStateOf<ProviderInstallation?>(null) }
    var revision by remember(instanceId) { mutableLongStateOf(0L) }
    var busy by remember(instanceId) { mutableStateOf(false) }
    var error by remember(instanceId) { mutableStateOf<String?>(null) }
    var version by remember(instanceId) { mutableStateOf("") }
    var removing by remember(instanceId) { mutableStateOf(false) }
    val canRead =
        "orchestration:read" in state.scopes && state.connectionStatus == ConnectionStatus.Connected
    val canManage =
        "providers:manage" in state.scopes &&
            state.connectionStatus == ConnectionStatus.Connected &&
            provider.policyReason == null &&
            !busy
    fun perform(action: suspend () -> ProviderInstallation?) {
        if (!canManage) return
        busy = true
        error = null
        scope.launch {
            val before = revision
            try {
                val result = action()
                if (result != null && before == revision) installation = result
                onChanged()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "Provider maintenance failed."
            } finally {
                busy = false
            }
        }
    }
    DisposableEffect(tools, instanceId, canRead) {
        val subscription =
            if (canRead)
                runCatching {
                    tools.observe(
                        instanceId,
                        onError = { failure -> scope.launch { error = failure.message } },
                        onValue = { value ->
                            scope.launch {
                                revision += 1
                                installation = value
                            }
                        },
                    )
                }
                    .getOrElse {
                        error = it.message
                        null
                    }
            else null
        onDispose { subscription?.close() }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Installation and updates", style = MaterialTheme.typography.titleMedium)
            Text(
                if (provider.installed) "Installed ${provider.version ?: "version unknown"}"
                else "Not installed"
            )
            provider.policyReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (busy) Text("Sending request…", style = MaterialTheme.typography.bodySmall)
            installation?.let { value ->
                Text("Installation: ${value.phase}", style = MaterialTheme.typography.bodySmall)
                if (value.active && value.totalBytes != null && value.totalBytes > 0)
                    LinearProgressIndicator(
                        progress = {
                            (value.downloadedBytes.toFloat() / value.totalBytes).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                if (value.downloadedBytes > 0)
                    Text(
                        "${formatStorageBytes(value.downloadedBytes)} downloaded" +
                            (value.totalBytes?.let { " of ${formatStorageBytes(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                value.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (value.source != null)
                    Text("Source: ${value.source}", style = MaterialTheme.typography.bodySmall)
                value.executablePath?.let {
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (value.active && value.operationId != null)
                    TextButton(
                        onClick = { perform { tools.cancel(instanceId, value.operationId) } },
                        enabled = canManage,
                    ) {
                        Text("Cancel installation")
                    }
            }
            if (provider.updateStatus != "idle")
                Text("Update: ${provider.updateStatus}", style = MaterialTheme.typography.bodySmall)
            provider.updateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            provider.updateOutput
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            if (installation?.active != true) {
                Row {
                    if (provider.canInstall)
                        TextButton(
                            onClick = { perform { tools.install(instanceId) } },
                            enabled = canManage,
                        ) {
                            Text(
                                if (installation?.phase in setOf("failed", "cancelled"))
                                    "Retry installation"
                                else "Install"
                            )
                        }
                    if (provider.canUpdate)
                        TextButton(
                            onClick = {
                                perform {
                                    tools.update(instanceId)
                                    null
                                }
                            },
                            enabled = canManage,
                        ) {
                            Text("Update to ${provider.latestVersion}")
                        }
                    if (installation?.canRemove == true)
                        TextButton(onClick = { removing = true }, enabled = canManage) {
                            Text("Remove installation")
                        }
                }
                if (provider.canInstallVersion) {
                    OutlinedTextField(
                        version,
                        { version = it },
                        label = { Text("Specific version") },
                        singleLine = true,
                        enabled = canManage,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            perform {
                                tools.update(instanceId, version)
                                null
                            }
                        },
                        enabled = canManage && version.isNotBlank(),
                    ) {
                        Text("Install version")
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (removing)
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${provider.name} installation?") },
            text = {
                Text(
                    "The managed CLI will be removed from this environment. Your conversations are kept."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = false
                        perform { tools.remove(instanceId) }
                    },
                    enabled = canManage,
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
}
