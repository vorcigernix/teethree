package com.example.teethree.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.ProjectActionLaunchFailure
import com.example.teethree.data.ProjectActionsTools
import com.example.teethree.data.T3Repository
import com.example.teethree.data.T3Thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProjectActionsMenu(
    repository: T3Repository,
    thread: T3Thread,
    onOpenTerminal: (String) -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val tools = remember(repository) { ProjectActionsTools(repository) }
    val scope = rememberCoroutineScope()
    var expanded by remember(thread.id) { mutableStateOf(false) }
    var busy by remember(thread.id) { mutableStateOf(false) }
    var error by remember(thread.id) { mutableStateOf<String?>(null) }
    var failedTerminal by remember(thread.id) { mutableStateOf<String?>(null) }
    val actions = tools.actions(thread)
    if (actions.isEmpty()) return
    val canRun =
        state.connectionStatus == ConnectionStatus.Connected &&
            "terminal:operate" in state.scopes &&
            !thread.archived &&
            !busy
    Column {
        TextButton(onClick = { expanded = true }, enabled = !busy) {
            Text(if (busy) "Starting action…" else "Project actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.name +
                                listOfNotNull(
                                        if (action.setup) "setup" else null,
                                        if (action.onSettle) "on settle" else null,
                                    )
                                    .takeIf { it.isNotEmpty() }
                                    ?.joinToString(prefix = " (", postfix = ")")
                                    .orEmpty()
                        )
                    },
                    enabled = canRun,
                    onClick = {
                        expanded = false
                        busy = true
                        error = null
                        failedTerminal = null
                        scope.launch {
                            try {
                                onOpenTerminal(tools.run(thread, action))
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                error = failure.message ?: "The action could not be started."
                                failedTerminal =
                                    (failure as? ProjectActionLaunchFailure)?.terminalId
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            }
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        failedTerminal?.let { terminalId ->
            TextButton(
                onClick = { onOpenTerminal(terminalId) },
                enabled = state.connectionStatus == ConnectionStatus.Connected,
            ) {
                Text("Inspect terminal")
            }
        }
    }
}
