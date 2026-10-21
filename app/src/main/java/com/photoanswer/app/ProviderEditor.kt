package com.photoanswer.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp

@Composable
internal fun ProviderEditor(provider: ProviderConfig, index: Int, expanded: Boolean, canMoveUp: Boolean,
    canMoveDown: Boolean, editable: Boolean = true, showKey: Boolean, onExpand: () -> Unit, onEdit: (ProviderConfig) -> Unit,
    onShowKey: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onDelete: () -> Unit, onTest: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var advanced by rememberSaveable(provider.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 80.dp)
                .clickable(enabled = editable, onClickLabel = if (expanded) "收起模型配置" else "展开模型配置", onClick = onExpand)
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${index + 1}. ${provider.name.ifBlank { "未命名模型" }}", style = MaterialTheme.typography.titleMedium)
                    Text(when { !provider.enabled -> "已停用"; !provider.isConfigured -> "待完善连接信息"; provider.fallbackOnly -> "仅在失败时接替"; else -> provider.model },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            Box {
                IconButton(enabled = editable, onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "${provider.name}的排序和移除操作") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("提高优先级") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) }, enabled = canMoveUp,
                        onClick = { onMoveUp(); menuOpen = false })
                    DropdownMenuItem(text = { Text("降低优先级") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) }, enabled = canMoveDown,
                        onClick = { onMoveDown(); menuOpen = false })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("移除模型", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { onDelete(); menuOpen = false })
                }
            }
        }
        AnimatedVisibility(expanded) {
            Column {
                HorizontalDivider()
                ProviderFields(provider, showKey, editable, onEdit, onShowKey)
                ToggleRow("启用连接", "完整配置后，按优先级选择此模型", provider.enabled, editable) { onEdit(provider.copy(enabled = it)) }
                VisionTestSection(provider, editable) { onTest() }
                TextButton(enabled = editable, onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (advanced) "收起进阶设置" else "进阶设置")
                    Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.padding(start = 8.dp))
                }
                AnimatedVisibility(advanced) { Column { HorizontalDivider(); RoutingFields(provider, editable, onEdit) } }
            }
        }
    }
}

@Composable
private fun ProviderFields(provider: ProviderConfig, showKey: Boolean, editable: Boolean, onEdit: (ProviderConfig) -> Unit, onShowKey: () -> Unit) {
    val focus = LocalFocusManager.current
    val next = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { focus.clearFocus() })
    val urlInvalid = provider.baseUrl.isNotBlank() && !provider.copy(model = "valid", apiKey = "valid").isConfigured
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(enabled = editable, value = provider.name, onValueChange = { onEdit(provider.copy(name = it)) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("显示名称") }, supportingText = { Text("给连接起一个方便辨认的名字") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = next)
        OutlinedTextField(enabled = editable, value = provider.baseUrl, onValueChange = { onEdit(provider.copy(baseUrl = it)) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("接口地址") }, placeholder = { Text("https://api.example.com/v1") }, singleLine = true, isError = urlInvalid,
            supportingText = { Text(if (urlInvalid) "请输入不含密钥或查询参数的完整 HTTPS 地址" else "使用服务商提供的 HTTPS 接口地址") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next), keyboardActions = next)
        OutlinedTextField(enabled = editable, value = provider.model, onValueChange = { onEdit(provider.copy(model = it)) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("模型 ID") }, supportingText = { Text("与服务商提供的模型名称保持一致") }, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next), keyboardActions = next)
        OutlinedTextField(enabled = editable, value = provider.apiKey, onValueChange = { onEdit(provider.copy(apiKey = it)) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("API 密钥") }, supportingText = { Text("加密保存在本机") }, singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(enabled = editable, onClick = onShowKey) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                if (showKey) "隐藏 API 密钥" else "显示 API 密钥") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done), keyboardActions = next)
    }
}

@Composable
private fun RoutingFields(p: ProviderConfig, editable: Boolean, onEdit: (ProviderConfig) -> Unit) {
    Column {
        ToggleRow("允许接替", "其他模型失败时，允许使用此连接", p.allowFallback, p.enabled && editable) {
            onEdit(p.copy(allowFallback = it, fallbackOnly = if (it) p.fallbackOnly else false))
        }
        ToggleRow("仅作备用", "不参与首轮处理，只在切换时使用", p.fallbackOnly, p.enabled && editable) {
            onEdit(p.copy(fallbackOnly = it, allowFallback = if (it) true else p.allowFallback))
        }
        ToggleRow("参与独立校验", "先返回原内容，再进行检查；失败时保留原内容", p.allowVerification, p.enabled && editable) { onEdit(p.copy(allowVerification = it)) }
        ToggleRow("流式响应", "关闭后等待完整回复；输出长度由服务商决定", p.streaming, p.enabled && editable) { onEdit(p.copy(streaming = it)) }
        HorizontalDivider()
        SettingStepper("图片精度", "较高精度适合密集文字", "${p.imageMaxEdge} px", editable && p.imageMaxEdge > 2048, editable && p.imageMaxEdge < 6144,
            { onEdit(p.copy(imageMaxEdge = (p.imageMaxEdge - 1024).coerceAtLeast(2048))) }, { onEdit(p.copy(imageMaxEdge = (p.imageMaxEdge + 1024).coerceAtMost(6144))) })
        SettingStepper("无进展超时", "持续返回推理或正文时会继续等待", "${p.progressTimeoutSeconds} 秒", editable && p.progressTimeoutSeconds > 60, editable && p.progressTimeoutSeconds < 900,
            { onEdit(p.copy(progressTimeoutSeconds = (p.progressTimeoutSeconds - 60).coerceAtLeast(60))) }, { onEdit(p.copy(progressTimeoutSeconds = (p.progressTimeoutSeconds + 60).coerceAtMost(900))) })
        SettingStepper("同时处理数量", "同一服务商与密钥共用并发额度", "${p.maxConcurrent} 个", editable && p.maxConcurrent > 1, editable && p.maxConcurrent < 4,
            { onEdit(p.copy(maxConcurrent = p.maxConcurrent - 1)) }, { onEdit(p.copy(maxConcurrent = p.maxConcurrent + 1)) })
    }
}

@Composable
private fun SettingStepper(title: String, description: String, value: String, canDecrease: Boolean, canIncrease: Boolean,
    decrease: () -> Unit, increase: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = canDecrease, onClick = decrease) { Icon(Icons.Default.Remove, "减小$title") }
            Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { stateDescription = "$title：$value" })
            IconButton(enabled = canIncrease, onClick = increase) { Icon(Icons.Default.Add, "增大$title") }
        }
    }
}

@Composable
internal fun ToggleRow(title: String, description: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
