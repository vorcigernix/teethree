package com.example.teethree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.teethree.data.composerContextItems
import kotlinx.serialization.json.JsonObject

@Composable
internal fun ComposerContextStrip(
    context: JsonObject?,
    enabled: Boolean,
    onRemove: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val records = composerContextItems(context)
    if (records.isEmpty()) return
    LazyRow(
        modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(records, key = { it.contextId }) { record ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    Modifier.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${record.kind.replace('-', ' ')} · ${record.label}",
                        Modifier.padding(start = 12.dp, end = if (onRemove == null) 12.dp else 0.dp)
                            .widthIn(max = 220.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (onRemove != null)
                        IconButton(onClick = { onRemove(record.contextId) }, enabled = enabled) {
                            Icon(Icons.Default.Close, "Remove ${record.label} context")
                        }
                }
            }
        }
    }
}
