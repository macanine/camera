package com.photoanswer.app

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.net.toUri
import com.nic.photoanswer.BuildConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment

@Composable
internal fun AppUpdateHost() {
    val context = LocalContext.current
    val state by AppUpdates.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var installError by remember { mutableStateOf("") }
    var installing by rememberSaveable { mutableStateOf(false) }
    fun install() { scope.launch {
        if (installing) return@launch
        installing = true
        try { context.startActivity(AppUpdates.installIntent(context)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            installError = "无法启动安装，请重新下载后重试"
            AppUpdates.show()
        }
        finally { installing = false }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else {
            installError = "允许此来源安装后，可以继续安装更新"
            AppUpdates.show()
        }
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            AppUpdates.check(context.applicationContext)
            awaitCancellation()
        }
    }
    val release = state.release
    if (state.prompt && release != null) AlertDialog(
        onDismissRequest = { AppUpdates.dismiss() },
        title = { Text("新版本 ${release.versionName}") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (release.notes.isNotBlank()) Text(neutralUiCopy(release.notes))
            Text(installError.ifBlank { state.message })
            if (state.downloading) { LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth()); Text("${state.progress}%") }
        } },
        confirmButton = { TextButton(enabled = !state.downloading && !installing, onClick = {
            installError = ""
            if (state.ready == null) AppUpdates.download(context.applicationContext)
            else if (!context.packageManager.canRequestPackageInstalls()) {
                try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())) }
                catch (_: Exception) { installError = "请在系统设置中允许此来源安装" }
            } else install()
        }) { Text(if (installing) "正在校验…" else if (state.downloading) "正在下载…" else if (state.ready != null) "安装更新" else "下载更新") } },
        dismissButton = { TextButton(onClick = { AppUpdates.dismiss() }) { Text("稍后") } }
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AppUpdateSettings() {
    val context = LocalContext.current
    val state by AppUpdates.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("应用更新", style = MaterialTheme.typography.titleMedium)
        Text("当前版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        if (BuildConfig.DEBUG) {
            Text("调试版本不参与自动更新", style = MaterialTheme.typography.bodySmall)
        } else {
            ToggleRow("自动检查新版本", "在打开应用时检查，每六小时最多一次", state.automatic, true) {
                AppUpdates.setAutomatic(context.applicationContext, it)
            }
        }
        FlowRow {
            if (state.release != null) TextButton(onClick = AppUpdates::show) {
                Text(if (state.downloading) "查看下载进度 ${state.progress}%" else if (state.ready != null) "安装已下载的更新" else "查看可用更新")
            }
            if (!BuildConfig.DEBUG) TextButton(enabled = !state.checking && !state.downloading, onClick = {
                AppUpdates.check(context.applicationContext, manual = true)
            }) { Text(if (state.checking) "正在检查…" else "检查更新") }
        }
        if (state.message.isNotBlank()) Text(state.message, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
    }
}
