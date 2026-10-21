package com.photoanswer.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.ClipData
import android.content.ClipboardManager
import androidx.core.content.edit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumScreen(tasks: List<SolveTask>, selectedId: String, onSelect: (String) -> Unit,
    onBack: () -> Unit, onRetry: (String) -> Unit, onCancel: (String) -> Unit, onDelete: (String) -> Unit,
    onSettings: () -> Unit = {}, selectedTask: SolveTask? = null, totalCount: Int = tasks.size,
    onLoadMore: () -> Unit = {}) {
    val selectedIndex = tasks.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: tasks.indices.firstOrNull()
    val expectedId = selectedId.ifBlank { tasks.firstOrNull()?.id.orEmpty() }
    val summary = selectedIndex?.let(tasks::getOrNull)?.takeIf { it.id == expectedId }
    val selected = selectedTask?.takeIf { it.id == expectedId }
    val loadingDetail = selected == null && summary != null
    var deleteTarget by remember { mutableStateOf<SolveTask?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var rawMode by rememberSaveable(selected?.id) { mutableStateOf(false) }
    var details by remember { mutableStateOf<String?>(null) }
    var showOriginal by rememberSaveable(selected?.id) { mutableStateOf(false) }
    var readingSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var textSize by rememberSaveable { mutableIntStateOf(18) }
    LaunchedEffect(context) { textSize = withContext(Dispatchers.IO) {
        context.getSharedPreferences("display", android.content.Context.MODE_PRIVATE).getInt("answer_size", 18).coerceIn(16, 24)
    } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    fun saveTextSize() { scope.launch(Dispatchers.IO) {
        context.getSharedPreferences("display", android.content.Context.MODE_PRIVATE)
            .edit { putInt("answer_size", textSize) }
    } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 420.dp
        val wide = maxWidth >= 840.dp
        val showCounter = maxWidth >= 320.dp
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = {
            TopAppBar(title = {
                Column {
                    Text("相册记录", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!compact && !largeFont && selected != null) Text(historyTimeLabel(selected.createdAt), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回拍照") } }, actions = {
                if (!wide) HistorySwitcher(tasks, selected?.id.orEmpty(), onSelect, totalCount, onLoadMore)
                Box(Modifier.wrapContentSize(Alignment.TopEnd)) {
                    IconButton(enabled = selected != null, onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, "更多操作")
                    }
                    DropdownMenu(
                        expanded = menuOpen && selected != null,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.widthIn(max = 280.dp),
                        properties = PopupProperties(focusable = true, usePlatformDefaultWidth = false)
                    ) {
                        DropdownMenuItem(text = { Text(if (rawMode) "显示公式" else "查看原文") }, leadingIcon = { Icon(Icons.Default.Code, null) },
                            onClick = { rawMode = !rawMode; menuOpen = false })
                        DropdownMenuItem(text = { Text("阅读字号") }, leadingIcon = { Icon(Icons.Default.TextFields, null) },
                            onClick = { readingSettings = true; menuOpen = false })
                        DropdownMenuItem(text = { Text("调用详情") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = {
                            val id = selected?.id
                            menuOpen = false
                            if (id != null) scope.launch {
                                details = try {
                                    withContext(Dispatchers.IO) { neutralUiCopy(TaskStore.db.attemptDetails(id)).ifBlank { "尚无调用记录" } }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { "调用记录暂时无法读取，诊断编号 ${LocalDiagnostics.record("attempt-details", e)}" }
                            }
                        })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("删除记录", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { deleteTarget = selected; menuOpen = false })
                    }
                }
            })
        }, bottomBar = {
            if (selected != null) Surface(tonalElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (tasks.size > 1) {
                        IconButton(enabled = selectedIndex != null && selectedIndex < tasks.lastIndex,
                            onClick = { selectedIndex?.let { onSelect(tasks[it + 1].id) } }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "上一条记录") }
                        if (showCounter) Text("${(selectedIndex ?: 0) + 1} / $totalCount", style = MaterialTheme.typography.labelMedium)
                        IconButton(enabled = selectedIndex != null && selectedIndex > 0,
                            onClick = { selectedIndex?.let { onSelect(tasks[it - 1].id) } }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "下一条记录") }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showOriginal = true }) { Icon(Icons.Default.Image, "查看照片原图") }
                    IconButton(enabled = selected.answer.isNotBlank(), onClick = {
                        clipboard.setPrimaryClip(ClipData.newPlainText("内容", selected.answer))
                        scope.launch { snackbar.showSnackbar("内容已复制") }
                    }) { Icon(Icons.Default.ContentCopy, "复制完整内容") }
                }
            }
        }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide && tasks.isNotEmpty()) {
                    HistoryList(tasks, selected?.id.orEmpty(), onSelect, Modifier.width(264.dp).fillMaxHeight(), totalCount, onLoadMore)
                    VerticalDivider()
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    if (loadingDetail) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.semantics { contentDescription = "正在读取记录" })
                    }
                    else if (selected == null) EmptyAlbum(onBack)
                    else key(selected.id) {
                        AnswerPane(selected, rawMode, textSize, compact,
                            onRetry = { onRetry(selected.id) }, onCancel = { onCancel(selected.id) }, onSettings = onSettings)
                    }
                }
            }
        }
    }
    if (showOriginal && selected != null) OriginalPhotoDialog(selected) { showOriginal = false }
    deleteTarget?.let { task ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除这条记录？") },
            text = { Text("${historyTimeLabel(task.createdAt)} 的照片、内容和调用记录会一并删除，无法恢复。") },
            confirmButton = { TextButton(onClick = { onDelete(task.id); deleteTarget = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("保留记录") } })
    }
    details?.let { content ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text("调用详情") },
            text = { SelectionContainer { Text(content, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { details = null }) { Text("关闭") } })
    }
    if (readingSettings) AlertDialog(onDismissRequest = { readingSettings = false }, title = { Text("阅读字号") }, text = {
        Column {
            Text("字号会跟随系统字体设置进一步缩放。", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(enabled = textSize > 16, onClick = { textSize -= 2; saveTextSize() }) { Icon(Icons.Default.TextDecrease, "减小内容字号") }
                Text("${textSize} sp", style = MaterialTheme.typography.titleLarge)
                IconButton(enabled = textSize < 24, onClick = { textSize += 2; saveTextSize() }) { Icon(Icons.Default.TextIncrease, "增大内容字号") }
            }
        }
    }, confirmButton = { TextButton(onClick = { readingSettings = false }) { Text("完成") } })
}

@Composable
private fun EmptyAlbum(onCamera: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.PhotoLibrary, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("还没有记录", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall)
        Text("拍摄的照片和处理内容会保存在这里。", Modifier.padding(top = 8.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onCamera, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.CameraAlt, null); Text("去拍照", Modifier.padding(start = 8.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerPane(task: SolveTask, rawMode: Boolean, textSize: Int, compact: Boolean,
    onRetry: () -> Unit, onCancel: () -> Unit, onSettings: () -> Unit) {
    val streaming = isBusyStatus(task.status)
    val visible = task.answer.ifBlank { task.draft }
    var showError by remember(task.id) { mutableStateOf(false) }
    var showSuggestion by remember(task.id) { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
        if (visible.isNotBlank()) {
            if (streaming) LinearProgressIndicator(Modifier.fillMaxWidth())
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusPill(task.status)
                if (task.answer.isBlank()) Text("未完成草稿", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                if (rawMode) Text("原文模式", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
            val note = when {
                task.status in setOf("FAILED", "INTERRUPTED", "CANCELLED") ->
                    if (task.answer.isBlank()) "本轮未完成，草稿仅供参考" else "本轮未完成，已保留返回的内容"
                task.status == "NEEDS_REVIEW" -> "部分内容无法确定，请核对原图"
                else -> verificationLabel(task.verification)
            }
            if (!compact && note.isNotBlank()) Text(note, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            AnswerContent(visible, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), textSize, followEnd = streaming, rawMode = rawMode)
            if (!compact && task.model.isNotBlank()) Text("${task.provider} · ${task.model}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (streaming || task.verification in setOf("PENDING", "RUNNING")) TextButton(onClick = onCancel) {
                    Text(if (streaming) "停止处理" else "停止校验")
                } else if (task.status in setOf("FAILED", "INTERRUPTED", "CANCELLED", "NEEDS_REVIEW")) TextButton(onClick = onRetry) { Text("重新处理") }
                if (task.error.isNotBlank()) TextButton(onClick = { showError = true }) { Text("失败原因") }
                if (task.suggestion.isNotBlank()) TextButton(onClick = { showSuggestion = true }) { Text("修正建议（未确认）") }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (streaming) CircularProgressIndicator(Modifier.size(40.dp))
                else Icon(if (task.status == "CANCELLED") Icons.Default.PauseCircle else Icons.Default.ErrorOutline,
                    null, Modifier.size(40.dp), tint = if (task.status == "CANCELLED") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                Text(if (streaming) task.progress.ifBlank { currentStatusDescription(task.status) }
                    else if (task.status == "CANCELLED") "已停止处理" else if (task.status == "INTERRUPTED") "处理已中断" else "这次未能完成处理",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                Text(if (streaming) "可以继续拍摄其他照片，结果会保存在这里。" else if (task.status == "CANCELLED") "照片已保留，可以重新处理。" else friendlyError(task.error, task.status),
                    Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (streaming) OutlinedButton(onClick = onCancel) { Text(if (task.status == "QUEUED") "取消排队" else "停止处理") }
                    else Button(onClick = onRetry) { Icon(Icons.Default.Refresh, null); Text("重新处理", Modifier.padding(start = 8.dp)) }
                    if (task.status == "QUEUED" || (!streaming && task.status != "CANCELLED")) TextButton(onClick = onSettings) { Text("检查模型设置") }
                }
            }
        }
    }
    if (showError) AlertDialog(onDismissRequest = { showError = false }, title = { Text("本次未完成的原因") },
        text = { Text(friendlyError(task.error, task.status), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showError = false }) { Text("关闭") } })
    if (showSuggestion) AlertDialog(onDismissRequest = { showSuggestion = false }, title = { Text("修正建议（尚未确认）") },
        text = { AnswerContent(task.suggestion, Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 360.dp), textSize = textSize) },
        confirmButton = { TextButton(onClick = { showSuggestion = false }) { Text("保留原内容") } })
}

internal fun statusLabel(status: String): String = when (status) {
    "QUEUED" -> "等待中"; "RUNNING" -> "正在处理"; "SWITCHING" -> "正在切换"; "VERIFYING" -> "正在校验"
    "SUCCEEDED" -> "已完成"; "FAILED" -> "失败"; "CANCELLED" -> "已停止"; "INTERRUPTED" -> "已中断"; "NEEDS_REVIEW" -> "需要核对"; else -> status
}
internal fun verificationLabel(status: String): String = when (status) {
    "PENDING" -> "内容已返回，等待独立校验"; "RUNNING" -> "内容已返回，正在独立校验"; "VERIFIED" -> "独立校验已通过"
    "SUGGESTED" -> "存在尚未确认的修正建议"; "UNCERTAIN" -> "校验无法确认，已保留原内容"; "UNAVAILABLE" -> "校验未完成，已保留原内容"
    "CANCELLED" -> "校验已停止，已保留原内容"; else -> ""
}
