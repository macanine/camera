package com.photoanswer.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decode only enough pixels for the surface. Image work never blocks the UI thread. */
@Composable
internal fun TaskPhoto(path: String, modifier: Modifier = Modifier, thumbnail: Boolean = false) {
    var loading by remember(path, thumbnail) { mutableStateOf(true) }
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path, thumbnail) {
        value = null
        loading = true
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val target = if (thumbnail) 160 else 2048
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > target) sample *= 2
                val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@runCatching null
                val orientation = runCatching { androidx.exifinterface.media.ExifInterface(path).rotationDegrees }.getOrDefault(0)
                if (orientation == 0) decoded.asImageBitmap() else {
                    val rotated = android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height,
                        android.graphics.Matrix().apply { postRotate(orientation.toFloat()) }, true)
                    if (rotated !== decoded) decoded.recycle()
                    rotated.asImageBitmap()
                }
            }.getOrNull()
        }
        loading = false
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        image?.let {
            Image(it, if (thumbnail) null else "拍摄的照片原图", Modifier.fillMaxSize(),
                contentScale = if (thumbnail) ContentScale.Crop else ContentScale.Fit)
        } ?: if (loading) {
            CircularProgressIndicator(Modifier.size(if (thumbnail) 20.dp else 32.dp)
                .semantics { contentDescription = "正在读取照片" }, strokeWidth = 2.dp)
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.BrokenImage, if (thumbnail) null else "原图暂不可用", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!thumbnail) Text("照片暂时无法读取", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OriginalPhotoDialog(task: SolveTask, onDismiss: () -> Unit) {
    var zoom by remember(task.id) { mutableFloatStateOf(1f) }
    var pan by remember(task.id) { mutableStateOf(Offset.Zero) }
    AppFullScreenDialog(onDismiss) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
            TopAppBar(title = { Text("照片原图") }, navigationIcon = {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭原图") }
            }, actions = {
                TextButton(onClick = { zoom = 1f; pan = Offset.Zero }) { Text("重置") }
            })
        }, bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { zoom = (zoom - .5f).coerceAtLeast(1f); if (zoom == 1f) pan = Offset.Zero }) { Icon(Icons.Default.ZoomOut, "缩小原图") }
                Text("${(zoom * 100).toInt()}%", Modifier.padding(horizontal = 24.dp))
                IconButton(onClick = { zoom = (zoom + .5f).coerceAtMost(5f) }) { Icon(Icons.Default.ZoomIn, "放大原图") }
            }
        }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding).clipToBoundsCompat()) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val width = with(density) { maxWidth.toPx() }
                val height = with(density) { maxHeight.toPx() }
                TaskPhoto(task.imagePath, Modifier.fillMaxSize()
                    .pointerInput(task.id) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f; pan = Offset.Zero }) }
                    .transformable(rememberTransformableState { scale, offset, _ ->
                        zoom = (zoom * scale).coerceIn(1f, 5f)
                        pan = Offset((pan.x + offset.x).coerceIn(-width * (zoom - 1) / 2, width * (zoom - 1) / 2),
                            (pan.y + offset.y).coerceIn(-height * (zoom - 1) / 2, height * (zoom - 1) / 2))
                    })
                    .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y))
            }
        }
    }
}

private fun Modifier.clipToBoundsCompat() = this.then(Modifier.graphicsLayer { clip = true })

@Composable
internal fun AppFullScreenDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val light = MaterialTheme.colorScheme.background.luminance() > .5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = light
                    isAppearanceLightNavigationBars = light
                }
            }
        }
        content()
    }
}

@Composable
internal fun StatusPill(status: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val warning = status in setOf("FAILED", "NEEDS_REVIEW", "INTERRUPTED")
    val container = when { status == "FAILED" -> colors.errorContainer; warning -> colors.tertiaryContainer; status == "SUCCEEDED" -> colors.primaryContainer; else -> colors.surfaceVariant }
    val foreground = when { status == "FAILED" -> colors.onErrorContainer; warning -> colors.onTertiaryContainer; status == "SUCCEEDED" -> colors.onPrimaryContainer; else -> colors.onSurfaceVariant }
    Surface(modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = foreground) {
        Text(statusLabel(status), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}
