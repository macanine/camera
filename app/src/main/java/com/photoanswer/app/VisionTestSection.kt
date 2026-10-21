package com.photoanswer.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VisionTestSection(
    provider: ProviderConfig,
    enabled: Boolean = true,
    onStartTest: (ProviderConfig) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("视觉测试", style = MaterialTheme.typography.titleMedium)
        Text(
            "检查这组连接能否正确读取图片，无需先保存设置。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            enabled = enabled && provider.isConfigured,
            onClick = { onStartTest(provider) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text("检查图片识别能力", Modifier.padding(start = 8.dp))
        }
    }
}

class VisionTestSession internal constructor(
    val provider: ProviderConfig,
    private val cancelAction: () -> Unit
) {
    var isRunning by mutableStateOf(false)
        internal set
    private var runningJob: Job? = null
    private var nextGeneration = 0
    private var activeGeneration: Int? = null

    internal fun reserve(): Int {
        nextGeneration++
        activeGeneration = nextGeneration
        isRunning = true
        return nextGeneration
    }

    internal fun attach(job: Job) {
        runningJob = job
    }

    internal fun isActive(generation: Int): Boolean =
        activeGeneration == generation && runningJob?.isActive == true

    internal fun finish(generation: Int) {
        if (activeGeneration == generation) {
            activeGeneration = null
            runningJob = null
            isRunning = false
        }
    }

    internal fun cancel() {
        runningJob?.cancel()
        runningJob = null
        activeGeneration = null
        cancelAction()
        isRunning = false
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun VisionTestDialog(
    provider: ProviderConfig,
    session: VisionTestSession,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var probe by remember(provider.id) { mutableStateOf<VisionProbe?>(null) }
    var pickedFile by remember(provider.id) { mutableStateOf<File?>(null) }
    var job by remember(provider.id) { mutableStateOf<Job?>(null) }
    var status by remember(provider.id) { mutableStateOf("尚未开始") }
    var answerText by remember(provider.id) { mutableStateOf("") }
    LaunchedEffect(provider.id) {
        var created: VisionProbe? = null
        try {
            withContext(Dispatchers.IO) { created = VisionProbe.create(context) }
            probe = created
            kotlinx.coroutines.awaitCancellation()
        } finally { created?.let { saved -> AppRuntime.scope.launch { saved.file.delete() } } }
    }

    fun stop() {
        job?.cancel()
        job = null
        session.cancel()
        status = "已停止"
    }

    fun run(file: File, custom: Boolean) {
        job?.cancel()
        answerText = ""
        status = "正在读取图片…"
        val nextGeneration = session.reserve()
        val nextJob = scope.launch {
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                val result = ModelClient.requestImage(
                    file.absolutePath,
                    provider,
                    if (custom) REFERENCE_PROMPT else VisionProbe.PROMPT,
                    onProgress = { stage -> scope.launch(Dispatchers.Main.immediate) {
                        if (session.isActive(nextGeneration)) status = stage
                    } }
                ) { partial ->
                    scope.launch(Dispatchers.Main.immediate) {
                        if (session.isActive(nextGeneration)) {
                            answerText = partial
                        }
                    }
                }
                answerText = result.text
                val seconds =
                    (android.os.SystemClock.elapsedRealtime() - started) / 1000.0
                status = when {
                    custom -> "接口可用 · ${"%.1f".format(seconds)} 秒"
                    probe?.matches(result.text) == true ->
                        "视觉能力正常 · ${"%.1f".format(seconds)} 秒"
                    else -> "接口可用，但结果未匹配 · ${"%.1f".format(seconds)} 秒"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "测试失败：${friendlyError(e.localizedMessage.orEmpty(), "FAILED")}"
            } finally {
                session.finish(nextGeneration)
            }
        }
        job = nextJob
        session.attach(nextJob)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        val target =
                            File.createTempFile("vision_", ".image", context.cacheDir)
                        try {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                target.outputStream().use { output ->
                                    val buffer = ByteArray(8192)
                                    var total = 0
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        require(total <= 15 * 1024 * 1024) {
                                            "图片不能超过 15 MB"
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                }
                            } ?: error("无法读取图片")
                            val bounds =
                                BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(target.absolutePath, bounds)
                            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                                "请选择有效图片"
                            }
                            target
                        } catch (e: Exception) {
                            target.delete()
                            throw e
                        }
                    }
                    withContext(Dispatchers.IO) { pickedFile?.delete() }
                    pickedFile = file
                    status = "参考图片已就绪"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    status = "选图失败：${e.localizedMessage}"
                }
            }
        }
    }

    DisposableEffect(provider.id) {
        onDispose {
            job?.cancel()
            val oldFile = pickedFile
            AppRuntime.scope.launch { oldFile?.delete() }
            session.cancel()
        }
    }

    AppFullScreenDialog(onDismiss = { stop(); onDismiss() }) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text("连接测试") }, navigationIcon = {
                IconButton(onClick = { stop(); onDismiss() }) { Icon(Icons.Default.Close, "关闭并停止测试") }
            })
        }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val resultHeight = (maxHeight * .55f).coerceIn(160.dp, 480.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(provider.name.ifBlank { provider.model }, style = MaterialTheme.typography.titleMedium)
                Text("使用当前配置测试图片识别。结果不会保存到相册记录。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(enabled = !session.isRunning && probe != null, onClick = { probe?.let { run(it.file, false) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text("测试视觉能力", Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(enabled = !session.isRunning, onClick = { picker.launch("image/*") }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Image, null)
                        Text("选择参考图", Modifier.padding(start = 8.dp))
                    }
                }
                pickedFile?.let { file ->
                    OutlinedButton(enabled = !session.isRunning, onClick = { run(file, true) }) { Text("测试所选参考图") }
                }
                if (session.isRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                    if (session.isRunning) TextButton(onClick = ::stop) { Text("停止") }
                }
                if (answerText.isNotBlank()) AnswerContent(answerText, Modifier.fillMaxWidth().height(resultHeight), textSize = 16, followEnd = session.isRunning)
                Spacer(Modifier.height(24.dp))
            }
            }
        }
    }
}

private const val REFERENCE_PROMPT =
    "请描述这张图片的主要颜色、物体、数量与相对位置。只输出一段简短中文描述。"
