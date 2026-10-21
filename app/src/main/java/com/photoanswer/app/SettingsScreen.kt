package com.photoanswer.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.heightIn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val draft: SettingsDraft = viewModel()
    draft.initialize(context)
    val savedProviders by draft::saved
    var providers by draft::providers
    val saving = draft.saving
    val listState = rememberLazyListState()
    var expandedId by rememberSaveable { mutableStateOf(providers.firstOrNull()?.id.orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProviderConfig?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var testProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var testSessionSerial by remember { mutableIntStateOf(0) }
    var expandedAfterLoad by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(draft.loading) {
        if (!draft.loading && !expandedAfterLoad) {
            expandedId = providers.firstOrNull()?.id.orEmpty()
            expandedAfterLoad = true
        }
    }

    fun normalized(list: List<ProviderConfig>): List<ProviderConfig> =
        list.mapIndexed { index, provider ->
            provider.copy(
                priority = index,
                name = provider.name.trim(),
                baseUrl = provider.baseUrl.trim().trimEnd('/'),
                model = provider.model.trim(),
                apiKey = provider.apiKey.trim()
            )
        }

    val cleanDraft = normalized(providers)
    val isDirty = cleanDraft != savedProviders

    fun requestBack() {
        if (saving) return
        if (isDirty) showDiscardDialog = true else onBack()
    }

    fun edit(provider: ProviderConfig) {
        if (saving || draft.loading) return
        providers = providers.map { if (it.id == provider.id) provider else it }
    }

    fun add() {
        if (saving || draft.loading) return
        val provider = ProviderConfig(
            id = UUID.randomUUID().toString(),
            name = "新模型",
            baseUrl = "https://api.openai.com/v1",
            model = "",
            apiKey = "",
            priority = providers.size
        )
        providers = providers + provider
        expandedId = provider.id
        showKey = false
        scope.launch { listState.animateScrollToItem(providers.size + 1) }
    }

    fun removeWithUndo(provider: ProviderConfig) {
        val removedAt = providers.indexOfFirst { it.id == provider.id }
        providers = providers
            .filterNot { it.id == provider.id }
            .mapIndexed { index, item -> item.copy(priority = index) }
        if (expandedId == provider.id) expandedId = providers.firstOrNull()?.id.orEmpty()
        scope.launch {
            val result = snackbar.showSnackbar(
                message = "已移除 ${provider.name.ifBlank { "模型" }}",
                actionLabel = "撤销",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed &&
                providers.none { it.id == provider.id }
            ) {
                val target = removedAt.coerceAtLeast(0).coerceAtMost(providers.size)
                providers = providers.toMutableList().apply {
                    add(target, provider)
                }.mapIndexed { index, item -> item.copy(priority = index) }
                expandedId = provider.id
            }
        }
    }

    fun save() {
        if (saving || draft.loading) return
        val value = normalized(providers)
        val pendingSave = draft.save(context.applicationContext, value)
        scope.launch {
            try {
                pendingSave.await()
                onSaved()
                val missing = value.count { it.enabled && !it.isConfigured }
                snackbar.showSnackbar(if (ProviderRouting.candidates(value, emptySet()).isEmpty()) {
                    if (missing > 0) "设置已保存；还有 $missing 个连接待完善" else "设置已保存；启用一个常规连接后即可使用"
                } else "设置已保存")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { snackbar.showSnackbar(if (e is SettingsStorageException) e.message.orEmpty()
                else "设置保存失败，诊断编号 ${LocalDiagnostics.record("settings-save", e)}") }
        }
    }

    BackHandler(enabled = isDirty || saving) { requestBack() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("设置")
                        if (androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.3f) Text(
                            if (isDirty) "有未保存的更改" else "模型与连接",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(enabled = !saving, onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(enabled = !saving && !draft.loading, onClick = ::add) {
                        Icon(Icons.Default.Add, "添加模型")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = ::save,
                    enabled = isDirty && !saving && !draft.loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            WindowInsets.navigationBars.union(WindowInsets.ime)
                                .asPaddingValues()
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp)
                ) {
                    Icon(
                        if (isDirty) Icons.Default.Save else Icons.Default.Check,
                        contentDescription = null
                    )
                    Text(
                        if (saving) "正在保存…" else if (isDirty) "保存设置" else "设置已保存",
                        Modifier.padding(start = 8.dp)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (saving || draft.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (draft.loadError.isNotBlank()) item {
                    Text(draft.loadError, color = MaterialTheme.colorScheme.error)
                }
                if (draft.saveError.isNotBlank()) item {
                    Text(draft.saveError, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                item { ProviderChainCard(providers) }
                if (providers.isEmpty()) {
                    item {
                        EmptyProviders(onAdd = ::add)
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "连接",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${providers.size} 个",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    itemsIndexed(providers, key = { _, item -> item.id }) { index, provider ->
                        ProviderEditor(
                            provider = provider,
                            editable = !saving && !draft.loading,
                            index = index,
                            expanded = expandedId == provider.id,
                            canMoveUp = index > 0,
                            canMoveDown = index < providers.lastIndex,
                            showKey = showKey && expandedId == provider.id,
                            onExpand = {
                                expandedId =
                                    if (expandedId == provider.id) "" else provider.id
                                showKey = false
                            },
                            onEdit = ::edit,
                            onShowKey = { showKey = !showKey },
                            onMoveUp = {
                                providers =
                                    ProviderRouting.move(providers, provider.id, -1)
                            },
                            onMoveDown = {
                                providers =
                                    ProviderRouting.move(providers, provider.id, 1)
                            },
                            onDelete = { pendingDelete = provider },
                            onTest = {
                                testSessionSerial++
                                testProvider = provider
                            }
                        )
                    }
                    item {
                        TextButton(
                            onClick = ::add,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text("添加模型", Modifier.padding(start = 8.dp))
                        }
                    }
                }
                item { AppUpdateSettings() }
            }
        }
    }

    pendingDelete?.let { provider ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除这个模型？") },
            text = {
                Text("“${provider.name.ifBlank { provider.model }}”会从当前草稿中移除，保存前仍可撤销。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    removeWithUndo(provider)
                }) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃未保存的更改？") },
            text = { Text("返回后会恢复上次保存的模型配置。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    draft.discard()
                    onBack()
                }) {
                    Text("放弃", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            }
        )
    }

    testProvider?.let { provider ->
        val session = remember(provider.id, testSessionSerial) {
            VisionTestSession(provider) {}
        }
        VisionTestDialog(
            provider = provider,
            session = session,
            onDismiss = {
                session.cancel()
                testProvider = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderChainCard(providers: List<ProviderConfig>) {
    val chain = providers.filter {
        it.enabled && it.isConfigured
    }.sortedBy { it.priority }
    val primary = chain.firstOrNull { !it.fallbackOnly }
    val fallbackCount = chain.count { it.id != primary?.id }
    val verifierCount = ProviderRouting.verifiers(chain, primary?.id.orEmpty()).size
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        "模型连接",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "优先使用排在前面的模型，失败时自动切换。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            HorizontalDivider()
            if (chain.isEmpty()) {
                Text(
                    "添加接口、模型 ID 和密钥后即可开始。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    chain.take(6).forEach { provider ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (provider.id == primary?.id) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Text(
                                "${chain.indexOf(provider) + 1}  ${
                                    provider.name.ifBlank { provider.model }
                                }",
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    if (chain.size > 6) {
                        Text(
                            "+${chain.size - 6}",
                            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Text(
                    "${chain.size} 个可用连接 · $verifierCount 个参与校验",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun EmptyProviders(onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("添加第一个模型", style = MaterialTheme.typography.titleMedium)
            Text(
                "接口、模型名称和密钥都只保存在本机。",
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("添加模型", Modifier.padding(start = 8.dp))
            }
        }
    }
}
