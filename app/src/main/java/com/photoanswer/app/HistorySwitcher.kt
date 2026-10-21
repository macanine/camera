package com.photoanswer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistorySwitcher(tasks: List<SolveTask>, selectedId: String, onSelect: (String) -> Unit,
    totalCount: Int = tasks.size, onLoadMore: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(enabled = tasks.isNotEmpty(), onClick = { expanded = true }) {
        Icon(Icons.Default.History, "浏览全部相册记录")
    }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false }) {
        Text("全部记录 · $totalCount", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        HistoryList(tasks, selectedId, { onSelect(it); expanded = false }, Modifier.fillMaxWidth().heightIn(max = 560.dp), totalCount, onLoadMore)
    }
}

@Composable
internal fun HistoryList(tasks: List<SolveTask>, selectedId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier,
    totalCount: Int = tasks.size, onLoadMore: () -> Unit = {}) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        tasks.groupBy { historyBucket(it.createdAt) }.forEach { (bucket, entries) ->
            item(key = "bucket-$bucket") {
                Text(bucket, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            items(entries, key = { it.id }) { task ->
                val current = task.id == selectedId
                ListItem(
                    modifier = Modifier.clickable { onSelect(task.id) }.semantics { selected = current },
                    colors = ListItemDefaults.colors(containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                    leadingContent = { TaskPhoto(task.imagePath, Modifier.size(48.dp).clip(MaterialTheme.shapes.small), thumbnail = true) },
                    headlineContent = { Text(historyTimeLabel(task.createdAt), style = MaterialTheme.typography.bodyMedium) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(statusLabel(task.status), style = MaterialTheme.typography.labelMedium)
                            if (task.answer.isNotBlank()) Text(task.answer.replace('\n', ' '), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    trailingContent = if (current) ({ Icon(Icons.Default.CheckCircle, "当前记录", tint = MaterialTheme.colorScheme.primary) }) else null
                )
            }
        }
        if (tasks.size < totalCount) item(key = "load-more") {
            TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多记录") }
        }
    }
}
