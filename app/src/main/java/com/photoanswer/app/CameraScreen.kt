package com.photoanswer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val photoSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

@Composable
internal fun CameraScreen(tasks: List<SolveTask> = emptyList(), activeCount: Int = tasks.count { it.status in setOf("RUNNING", "SWITCHING", "VERIFYING") },
    queuedCount: Int = tasks.count { it.status == "QUEUED" }, onAlbum: () -> Unit, onSettings: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val rootView = LocalView.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf(false) }
    var controllerVersion by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var flashFrame by remember { mutableStateOf(false) }
    var permissionDenied by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val settingsNeeded = permissionDenied && context is android.app.Activity &&
        !ActivityCompat.shouldShowRequestPermissionRationale(context, Manifest.permission.CAMERA)
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        hasPermission = it
        permissionDenied = !it
        if (!it) notice = "如需使用相机，请在系统设置中允许权限"
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    var requestedNotificationPermission by remember { mutableStateOf(false) }
    val requestNotificationPermission = rememberUpdatedState {
        if (Build.VERSION.SDK_INT >= 33 &&
            !requestedNotificationPermission &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestedNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val controller = remember(controllerVersion) {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            imageCaptureFlashMode = ImageCapture.FLASH_MODE_OFF
            setEnabledUseCases(LifecycleCameraController.IMAGE_CAPTURE)
            setPinchToZoomEnabled(false)
            setTapToFocusEnabled(false)
            setTapToFocusAutoCancelDuration(900L, TimeUnit.MILLISECONDS)
        }
    }
    DisposableEffect(controller, owner, hasPermission) {
        val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { state ->
            state?.let {
                zoomRatio = it.zoomRatio
                maxZoom = it.maxZoomRatio
            }
        }
        if (hasPermission) controller.zoomState.observe(owner, observer)
        onDispose {
            if (hasPermission) controller.zoomState.removeObserver(observer)
        }
    }
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                hasPermission = granted
                if (granted) permissionDenied = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) permissionDenied = false
    }
    LaunchedEffect(notice) {
        if (notice.isNotBlank()) {
            val result = snackbar.showSnackbar(notice, actionLabel = if (notice.startsWith("照片已保存")) "查看" else null)
            if (result == SnackbarResult.ActionPerformed) onAlbum()
            notice = ""
        }
    }
    LaunchedEffect(flashFrame) {
        if (flashFrame) {
            delay(130)
            flashFrame = false
        }
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(900)
            focusPoint = null
        }
    }
    DisposableEffect(controller, owner, hasPermission) {
        var disposed = false
        cameraReady = false
        if (hasPermission) {
            val initialization = controller.initializationFuture
            initialization.addListener(
                {
                    if (!disposed) {
                        runCatching {
                            initialization.get()
                            controller.bindToLifecycle(owner)
                        }.onSuccess {
                            cameraError = false
                            cameraReady = true
                        }.onFailure {
                            cameraError = true
                            notice = "相机无法启动，请检查设备状态后重试"
                        }
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }
        onDispose {
            disposed = true
            controller.unbind()
        }
    }


    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var configured by remember { mutableStateOf(false) }
    var configurationLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(owner, context) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            SecurePrefs.changes.collect {
                    try {
                        configured = withContext(Dispatchers.IO) {
                            SecurePrefs.load(context).any { it.enabled && !it.fallbackOnly && it.isConfigured }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        configured = false
                        notice = if (e is SettingsStorageException) e.message.orEmpty()
                            else "连接配置暂时无法读取，请在设置中重试"
                    } finally { configurationLoaded = true }
            }
        }
    }
    LaunchedEffect(flashMode, controller) { controller.imageCaptureFlashMode = flashMode }
    fun capture() {
        if (busy || !cameraReady || !hasPermission) return
        rootView.tapHaptic()
        busy = true
        takePhoto(context, controller) { result ->
            // Commit a captured file even if the composable leaves the screen during I/O.
            photoSaveScope.launch {
                try {
                    val file = result.getOrThrow()
                    flashFrame = true
                    withContext(Dispatchers.IO) {
                        try {
                            TaskRepository.initialize(context)
                            TaskStore.db.insert(SolveTask(UUID.randomUUID().toString(), System.currentTimeMillis(), file.absolutePath, "QUEUED"))
                        } catch (e: Exception) { file.delete(); throw e }
                    }
                    val configuredNow = withContext(Dispatchers.IO) {
                        runCatching { SecurePrefs.load(context).any { it.enabled && !it.fallbackOnly && it.isConfigured } }.getOrDefault(false)
                    }
                    if (configuredNow) {
                        if (scope.isActive) requestNotificationPermission.value()
                        runCatching { SolveService.start(context) }
                            .onFailure { notice = "照片已保存，后台暂时无法启动，请稍后重试" }
                        if (notice.isBlank()) notice = "照片已保存，正在处理"
                    } else notice = "照片已保存；添加模型后会自动开始处理"
                } catch (_: Exception) { notice = "拍照或保存失败，请重试" }
                finally { busy = false }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        var headerHeight by remember { mutableIntStateOf(0) }
        var footerHeight by remember { mutableIntStateOf(0) }
        val compact = maxHeight < 480.dp || maxWidth < 340.dp
        val veryShort = maxHeight < 340.dp
        if (hasPermission) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { viewContext ->
                PreviewView(viewContext).apply {
                    this.controller = controller
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = this
                }
            }, update = { if (it.controller !== controller) it.controller = controller },
                onRelease = { if (previewView === it) previewView = null })
            Box(Modifier.fillMaxSize()
                .pointerInput(cameraReady, controller) {
                    detectTapGestures { offset ->
                        if (cameraReady) {
                            focusPoint = offset
                            rootView.tapHaptic()
                            previewView?.let { preview ->
                                runCatching { controller.cameraControl?.startFocusAndMetering(
                                    FocusMeteringAction.Builder(preview.meteringPointFactory.createPoint(offset.x, offset.y),
                                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                        .setAutoCancelDuration(2, TimeUnit.SECONDS).build()) }
                            }
                        }
                    }
                }.pointerInput(cameraReady, maxZoom, controller) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (cameraReady) controller.setZoomRatio((zoomRatio * zoom).coerceIn(1f, maxZoom))
                    }
                })
            focusPoint?.let { point ->
                with(LocalDensity.current) {
                    Box(Modifier.offset { IntOffset(point.x.roundToInt() - 28.dp.roundToPx(), point.y.roundToInt() - 28.dp.roundToPx()) }
                        .size(56.dp).border(2.dp, Color.White, MaterialTheme.shapes.small))
                }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .onSizeChanged { headerHeight = it.height }
            .background(Color.Black.copy(alpha = .72f)).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("相机", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    val active = activeCount
                    val waiting = queuedCount
                    if (!veryShort) Text(if (active + waiting > 0) "处理中 $active · 排队 $waiting" else "保持画面稳定，文字清晰",
                        color = Color.White, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                IconButton(enabled = !busy, onClick = onSettings) { Icon(Icons.Default.Settings, "设置", tint = Color.White) }
                if (cameraReady && controller.cameraInfo?.hasFlashUnit() == true) {
                    IconButton(onClick = { flashMode = if (flashMode == ImageCapture.FLASH_MODE_OFF) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }, enabled = !busy) {
                        Icon(if (flashMode == ImageCapture.FLASH_MODE_OFF) Icons.Default.FlashOff else Icons.Default.FlashOn,
                            if (flashMode == ImageCapture.FLASH_MODE_OFF) "开启闪光灯" else "关闭闪光灯", tint = Color.White)
                    }
                }
            }
            if (configurationLoaded && !configured && !veryShort) Surface(
                onClick = onSettings,
                color = Color.Black.copy(alpha = .48f),
                contentColor = Color.White,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.size(18.dp))
                    Text("添加模型后，照片会自动开始处理", Modifier.weight(1f).padding(horizontal = 8.dp),
                        color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Text("设置", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (!hasPermission || cameraError) {
            Column(Modifier.fillMaxSize().padding(
                top = with(density) { headerHeight.toDp() },
                bottom = with(density) { footerHeight.toDp() }
            ).padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!compact) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.CameraAlt, null, Modifier.size(48.dp), tint = Color.White)
                        Text(if (cameraError) "相机暂时无法使用" else "允许相机访问，开始拍摄",
                            Modifier.padding(top = 16.dp), color = Color.White,
                            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Text(if (cameraError) "可以重新连接相机，已拍摄的照片仍在相册中。" else "照片仅用于你发起的处理请求，你可以随时删除记录。",
                            Modifier.padding(top = 12.dp), color = Color.White,
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                } else Spacer(Modifier.weight(1f))
                Button(onClick = {
                    if (cameraError) { cameraReady = false; cameraError = false; controllerVersion++ }
                    else {
                        if (settingsNeeded) context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                        )
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (cameraError) "重新连接" else if (settingsNeeded) "前往系统设置" else if (compact) "开启相机" else "允许访问相机") }
                if (compact) Spacer(Modifier.weight(1f))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { footerHeight = it.height }) {
            SnackbarHost(snackbar, Modifier.padding(horizontal = 16.dp))
            Surface(color = Color(0xFF121815).copy(alpha = .94f), contentColor = Color.White) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = if (compact) 8.dp else 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    if (hasPermission && cameraReady && maxZoom > 1f) {
                        TextButton(onClick = { controller.setZoomRatio(if (zoomRatio < 1.5f) minOf(2f, maxZoom) else 1f) }) {
                            Text("${"%.1f".format(zoomRatio)}×", color = Color.White)
                        }
                    }
                    Box(Modifier.widthIn(max = 520.dp).fillMaxWidth().height(if (compact) 64.dp else 96.dp)) {
                        Box(Modifier.align(Alignment.Center).size(if (compact) 64.dp else 80.dp).clip(CircleShape)
                            .border(3.dp, Color.White, CircleShape)
                            .clickable(enabled = hasPermission && cameraReady && !busy, role = Role.Button, onClickLabel = "拍照", onClick = ::capture)
                            .semantics { contentDescription = "拍照"; stateDescription = if (busy) "正在保存照片" else if (!cameraReady) "相机尚未就绪" else "已就绪" }, contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxSize().padding(7.dp).clip(CircleShape).background(if (cameraReady && !busy) Color.White else Color.Gray))
                            if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = CameraColors.Ink, strokeWidth = 2.dp)
                        }
                        // Deliberately invisible: the gallery is a double-tap shortcut beside the shutter.
                        Box(Modifier.align(Alignment.Center).offset(x = if (compact) 76.dp else 84.dp).size(64.dp)
                            .pointerInput(onAlbum, busy) { detectTapGestures(onDoubleTap = { if (!busy) { rootView.tapHaptic(); onAlbum() } }) }
                            .semantics {
                                contentDescription = "相册"
                                customActions = listOf(CustomAccessibilityAction("打开相册") { onAlbum(); true })
                            })
                    }
                }
            }
        }
        if (flashFrame) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .18f)))
    }
}

private fun takePhoto(
    context: android.content.Context,
    controller: LifecycleCameraController,
    onResult: (Result<File>) -> Unit
) {
    val folder = File(context.filesDir, "images").apply { mkdirs() }
    val file = File(folder, "${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    controller.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                onResult(Result.success(file))
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                onResult(Result.failure(exception))
            }
        }
    )
}
