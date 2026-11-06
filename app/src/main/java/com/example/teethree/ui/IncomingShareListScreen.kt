package com.example.teethree.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.teethree.data.IncomingSharedDraft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingShareListScreen(
    shares: List<IncomingSharedDraft>,
    staging: Boolean,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shared drafts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (staging)
                item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Preparing a shared draft…")
                }
            if (shares.isEmpty() && !staging)
                item {
                    Text(
                        "Share text or files to teethree from another Android app. Saved shares stay here until you use or discard them."
                    )
                }
            items(shares, key = { it.id }) { share ->
                Card(onClick = { onOpen(share.id) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            share.text.ifBlank {
                                share.files.firstOrNull()?.name ?: "Shared draft"
                            },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${share.files.size} files${if (share.warnings.isEmpty()) "" else " · ${share.warnings.size} notices"}"
                        )
                    }
                }
            }
        }
    }
}
