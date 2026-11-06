package com.example.teethree.ui

import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.teethree.data.ArchivePreviewEntry
import com.example.teethree.data.PreviewText
import com.example.teethree.data.extractAttachmentZipEntry
import com.example.teethree.data.listAttachmentZip
import com.example.teethree.data.parseDelimitedAttachment
import com.example.teethree.data.readAttachmentText
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun TextAttachmentPreview(
    file: File,
    mimeType: String,
    name: String,
    modifier: Modifier = Modifier,
) {
    val html =
        mimeType.substringBefore(';').equals("text/html", true) ||
            name.endsWith(".html", true) ||
            name.endsWith(".htm", true)
    val delimiter =
        when {
            mimeType.substringBefore(';') == "text/csv" || name.endsWith(".csv", true) -> ','
            mimeType.substringBefore(';') == "text/tab-separated-values" ||
                name.endsWith(".tsv", true) -> '\t'
            else -> null
        }
    var source by rememberSaveable(file.absolutePath) { mutableStateOf(false) }
    var document by remember(file) { mutableStateOf<PreviewText?>(null) }
    var failure by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file, html) {
        try {
            document =
                withContext(Dispatchers.IO) {
                    readAttachmentText(file, if (html) 5 * 1024 * 1024 else 1024 * 1024)
                }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure = error.message
        }
    }
    Column(modifier) {
        if (html || delimiter != null || name.endsWith(".md", true) || mimeType == "text/markdown")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                FilterChip(
                    !source,
                    onClick = { source = false },
                    label = { Text(if (delimiter == null) "Preview" else "Table") },
                )
                FilterChip(source, onClick = { source = true }, label = { Text("Source") })
            }
        val text = document
        when {
            failure != null ->
                Text(
                    failure.orEmpty(),
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            text == null -> Text("Loading document…", Modifier.padding(24.dp))
            html && !source && !text.truncated ->
                SafeHtmlAttachment(text.text, Modifier.weight(1f).fillMaxWidth())
            delimiter != null && !source ->
                DelimitedAttachmentTable(text, delimiter, Modifier.weight(1f).fillMaxWidth())
            else ->
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    if (text.truncated || text.text.length > 1024 * 1024)
                        Text(
                            "Partial preview. Save a copy or Open with to read the full document.",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    if (!source && (name.endsWith(".md", true) || mimeType == "text/markdown"))
                        MarkdownMessage(text.text.take(1024 * 1024))
                    else
                        SelectionContainer {
                            Text(
                                text.text.take(1024 * 1024),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = T3MonoFontFamily,
                            )
                        }
                }
        }
    }
}

@Composable
private fun DelimitedAttachmentTable(text: PreviewText, delimiter: Char, modifier: Modifier) {
    val table = remember(text, delimiter) { parseDelimitedAttachment(text.text, delimiter) }
    Column(modifier) {
        if (text.truncated || table.truncated)
            Text(
                "Table preview: up to 100 rows, 30 columns, and 2000 characters per cell.",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        val columns = table.rows.maxOfOrNull { it.size } ?: 0
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                items(table.rows) { row ->
                    Row {
                        repeat(columns) { column ->
                            Surface(
                                border =
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Text(
                                    row.getOrNull(column).orEmpty(),
                                    Modifier.width(180.dp).padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafeHtmlAttachment(html: String, modifier: Modifier) {
    val context = LocalContext.current
    val name = remember(html) { UUID.randomUUID().toString() }
    var failed by remember(html) { mutableStateOf(false) }
    var link by remember { mutableStateOf<Uri?>(null) }
    var linkError by remember { mutableStateOf<String?>(null) }
    val policy =
        "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:; media-src data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    val content =
        "<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"$policy\">$html"
    val document =
        remember(content) {
            """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body,iframe{margin:0;width:100%;height:100%;border:0}iframe{display:block}</style></head><body><iframe id="content" sandbox="allow-scripts"></iframe><script>document.getElementById('content').srcdoc=${JsonPrimitive(content).toString().replace("<", "\\u003c")};</script></body></html>"""
        }
    Column(modifier) {
        Text(
            "Network access is blocked in this preview.",
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        linkError?.let {
            Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        }
        if (failed)
            Text(
                "The HTML renderer stopped. Switch to Source or reopen the document.",
                Modifier.padding(24.dp),
            )
        else
            PreviewContent(
                document,
                name,
                Modifier.weight(1f).fillMaxWidth(),
                connectNativeBridge = false,
                onMessage = { failed = true },
                allowRequest = { false },
                onView = {},
                onNavigation = { uri, gesture ->
                    if (
                        gesture &&
                            uri.scheme in setOf("http", "https") &&
                            !uri.host.isNullOrBlank() &&
                            uri.userInfo == null
                    )
                        link = uri
                },
            )
    }
    link?.let { uri ->
        AlertDialog(
            onDismissRequest = { link = null },
            title = { Text("Open link?") },
            text = { Text(uri.toString()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        link = null
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (failure: Exception) {
                            linkError = failure.message ?: "No browser can open this link."
                        }
                    }
                ) {
                    Text("Open")
                }
            },
            dismissButton = { TextButton(onClick = { link = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun ZipAttachmentPreview(file: File, modifier: Modifier = Modifier, depth: Int = 0) {
    val context = LocalContext.current
    var entries by remember(file) { mutableStateOf<List<ArchivePreviewEntry>?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(file.absolutePath) { mutableStateOf("") }
    var selectedName by rememberSaveable(file.absolutePath) { mutableStateOf<String?>(null) }
    var extracted by remember(file) { mutableStateOf<File?>(null) }
    var retained by remember(file, selectedName) { mutableStateOf(false) }
    LaunchedEffect(file) {
        try {
            entries = listAttachmentZip(file)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message
        }
    }
    val selected = entries?.find { it.name == selectedName }
    BackHandler(enabled = selectedName != null) {
        selectedName = null
        error = null
    }
    val entryMime =
        selected?.name?.substringAfterLast('.', "")?.lowercase()?.let {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
        } ?: "application/octet-stream"
    val saveEntry =
        rememberSaveLocalFile(
            extracted,
            selected?.name?.substringAfterLast('/') ?: "entry",
            entryMime,
        ) {
            error = it
        }
    LaunchedEffect(file, selected) {
        extracted = null
        var owned: File? = null
        try {
            if (selected != null) {
                owned =
                    extractAttachmentZipEntry(
                        file,
                        selected,
                        File(context.cacheDir, "attachments"),
                        50L * 1024 * 1024,
                    )
                extracted = owned
                awaitCancellation()
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { if (!retained) owned?.delete() }
        }
    }
    Column(modifier) {
        if (selected != null) {
            TextButton(
                onClick = {
                    selectedName = null
                    error = null
                }
            ) {
                Text("Back to archive")
            }
            Text(
                selected.name,
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            Row {
                TextButton(
                    enabled = extracted != null,
                    onClick = {
                        retained = true
                        saveEntry()
                    },
                ) {
                    Text("Save entry")
                }
                TextButton(
                    enabled = extracted != null,
                    onClick = {
                        retained = true
                        try {
                            extracted?.let { openLocalFile(context, it, selected.name, entryMime) }
                        } catch (failure: Exception) {
                            error = failure.message
                        }
                    },
                ) {
                    Text("Open with")
                }
                TextButton(
                    enabled = extracted != null,
                    onClick = {
                        retained = true
                        try {
                            extracted?.let {
                                openLocalFile(context, it, selected.name, entryMime, true)
                            }
                        } catch (failure: Exception) {
                            error = failure.message
                        }
                    },
                ) {
                    Text("Share")
                }
            }
            error?.let {
                Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            }
            extracted?.let { local ->
                if (selected.size <= 10L * 1024 * 1024)
                    LocalAttachmentPreview(
                        local,
                        entryMime,
                        selected.name,
                        Modifier.weight(1f).fillMaxWidth(),
                        depth + 1,
                    )
                else
                    Text(
                        "Entry previews are limited to 10 MiB. Save entry or Open with to read this file.",
                        Modifier.padding(24.dp),
                    )
            }
                ?: if (error == null) Text("Opening archive entry…", Modifier.padding(24.dp))
                else Unit
        } else {
            OutlinedTextField(
                query,
                { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                label = { Text("Find a file in this ZIP") },
                singleLine = true,
            )
            error?.let {
                Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(
                    entries.orEmpty().filter {
                        !it.directory && (query.isBlank() || it.name.contains(query, true))
                    },
                    key = { it.name },
                ) { entry ->
                    TextButton(
                        onClick = {
                            error = null
                            selectedName = entry.name
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(entry.name)
                            Text(
                                "${entry.size / 1024} KiB",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (entries != null && entries!!.isEmpty())
                    item { Text("This archive is empty.", Modifier.padding(24.dp)) }
            }
        }
    }
}
