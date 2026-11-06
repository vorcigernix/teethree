package com.example.teethree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.teethree.data.ConnectionStatus
import com.example.teethree.data.T3Repository
import com.example.teethree.data.T3Thread
import com.example.teethree.data.ThreadFindMatch
import com.example.teethree.data.ThreadFindResult
import com.example.teethree.data.ThreadSearchJump
import com.example.teethree.data.ThreadSearchTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

private data class FindRequest(
    val query: String,
    val index: Int? = null,
    val start: ThreadFindMatch? = null,
    val offset: Int = 0,
    val revision: Int = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadSearchScreen(
    repository: T3Repository,
    thread: T3Thread,
    onBack: () -> Unit,
    onJump: (ThreadSearchJump) -> Unit,
) {
    val state by repository.state.collectAsState()
    val connectionId = repository.connectionId
    val keyboard = LocalSoftwareKeyboardController.current
    val tools = remember(repository) { ThreadSearchTools(repository) }
    var query by rememberSaveable(connectionId, thread.id) { mutableStateOf("") }
    var searchedQuery by rememberSaveable(connectionId, thread.id) { mutableStateOf("") }
    var request by
        remember(connectionId, thread.id) {
            mutableStateOf(
                searchedQuery
                    .takeIf { it.isNotBlank() && it == query.trim() }
                    ?.let { FindRequest(it) }
            )
        }
    var result by remember(connectionId, thread.id) { mutableStateOf<ThreadFindResult?>(null) }
    var pending by remember(connectionId, thread.id) { mutableStateOf(false) }
    var error by remember(connectionId, thread.id) { mutableStateOf<String?>(null) }
    val connected =
        state.connectionStatus == ConnectionStatus.Connected && state.selectedThreadId == thread.id
    fun search(next: FindRequest = FindRequest(query.trim())) {
        keyboard?.hide()
        result = null
        error = null
        searchedQuery = next.query
        request = next.copy(revision = (request?.revision ?: 0) + 1)
    }
    LaunchedEffect(connectionId, thread.id, connected, request) {
        val current = request ?: return@LaunchedEffect
        if (!connected) {
            pending = false
            return@LaunchedEffect
        }
        pending = true
        error = null
        try {
            tools
                .find(thread.id, current.query, current.index, current.start, current.offset)
                .collect { result = it }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "This chat could not be searched."
        } finally {
            pending = false
        }
    }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Find in chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat")
                    }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                thread.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedTextField(
                query,
                onValueChange = { value ->
                    if (value.length <= 200) {
                        query = value
                        request = null
                        result = null
                        error = null
                        pending = false
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("thread-search-query"),
                label = { Text("Search all message history") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(onSearch = { if (connected && query.isNotBlank()) search() }),
                trailingIcon = {
                    IconButton(onClick = { search() }, enabled = connected && query.isNotBlank()) {
                        Icon(Icons.Default.Search, "Search chat history")
                    }
                },
                supportingText = {
                    Text(
                        "Up to 200 characters. Searches the environment, including earlier messages."
                    )
                },
            )
            if (!connected)
                Text(
                    "Connect to this environment to search its history.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(
                    onClick = { search(FindRequest(searchedQuery)) },
                    enabled = connected && searchedQuery.isNotBlank(),
                ) {
                    Text("Retry search")
                }
            }
            val found = result
            if (pending && found == null)
                Text("Searching message history…", Modifier.testTag("thread-search-loading"))
            if (found != null) {
                Text(
                    if (!found.complete) "Found a match. Counting the rest of this chat…"
                    else if (found.totalMatches == 0) "No matches in this chat."
                    else "Match ${found.activeIndex + 1} of ${found.totalMatches}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("thread-search-count"),
                )
                if (found.match != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                search(FindRequest(searchedQuery, start = found.match, offset = -1))
                            },
                            enabled = found.complete && !pending,
                        ) {
                            Text("Previous")
                        }
                        TextButton(
                            onClick = {
                                search(FindRequest(searchedQuery, start = found.match, offset = 1))
                            },
                            enabled = found.complete && !pending,
                        ) {
                            Text("Next")
                        }
                        Button(
                            onClick = {
                                connectionId?.let {
                                    onJump(
                                        ThreadSearchJump(
                                            it,
                                            thread.id,
                                            found.match.entryId,
                                            found.match.occurrence,
                                            searchedQuery,
                                        )
                                    )
                                }
                            },
                            enabled = connectionId != null,
                        ) {
                            Text("Open source")
                        }
                    }
                }
                if (found.complete && found.navigation.isNotEmpty())
                    Text("Nearby matching messages", style = MaterialTheme.typography.labelMedium)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(found.navigation, key = { "${it.entryId}:${it.startIndex}" }) { group ->
                        val message =
                            state.timeline.firstOrNull { it.searchEntryId == group.entryId }
                        val selected = group.entryId == found.match?.entryId
                        Surface(
                            color =
                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            TextButton(
                                onClick = {
                                    connectionId?.let {
                                        onJump(
                                            ThreadSearchJump(
                                                it,
                                                thread.id,
                                                group.entryId,
                                                0,
                                                searchedQuery,
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        if (group.count == 1) "Match ${group.startIndex + 1}"
                                        else
                                            "Matches ${group.startIndex + 1}–${group.startIndex + group.count}",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Text(
                                        message?.text
                                            ?: "Earlier message. Open source to load its context.",
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (!pending && error == null)
                Text(
                    "Enter a word or phrase to find its source in this chat.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}
