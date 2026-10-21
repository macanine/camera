package com.photoanswer.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

object WindowState {
    val inMultiWindow = mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        TaskStore.init(this)
        WindowState.inMultiWindow.value = isInMultiWindowMode
        setContent {
            CameraTheme {
                PhotoAnswerApp()
            }
        }
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        WindowState.inMultiWindow.value = isInMultiWindowMode
    }

}

internal enum class Page { CAMERA, ALBUM, SETTINGS }

@Composable
private fun PhotoAnswerApp() {
    val context = LocalContext.current
    val view = LocalView.current
    val multiWindow by WindowState.inMultiWindow
    var page by rememberSaveable { mutableStateOf(Page.CAMERA.name) }
    var pageBeforeMultiWindow by rememberSaveable { mutableStateOf(Page.CAMERA.name) }
    var automaticWindowPage by rememberSaveable { mutableStateOf(false) }
    var settingsOrigin by rememberSaveable { mutableStateOf(Page.CAMERA.name) }
    var selectedId by rememberSaveable { mutableStateOf("") }
    var snapshot by remember { mutableStateOf(HistorySnapshot()) }
    val tasks = snapshot.tasks
    var historyLimit by rememberSaveable { mutableIntStateOf(50) }
    val scope = rememberCoroutineScope()
    var appError by remember { mutableStateOf("") }
    val darkTheme = isSystemInDarkTheme()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            SecurePrefs.changes.collect {
                try { TaskRepository.resumeQueue(context.applicationContext) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    appError = if (e is SettingsStorageException) e.message.orEmpty()
                        else "后台暂时无法启动，已有记录仍可查看"
                }
            }
        }
    }

    LaunchedEffect(lifecycle, historyLimit, selectedId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                TaskRepository.initialize(context)
                TaskRepository.observe(historyLimit, selectedId).collect { snapshot = it }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                appError = if (e is SettingsStorageException) e.message.orEmpty()
                    else "本地记录暂时无法读取，诊断编号 ${LocalDiagnostics.record("history", e)}"
            }
        }
    }
    LaunchedEffect(multiWindow) {
        if (multiWindow) {
            if (Page.valueOf(page) == Page.CAMERA) {
                pageBeforeMultiWindow = page
                page = Page.ALBUM.name
                automaticWindowPage = true
            }
        } else if (automaticWindowPage) {
            page = pageBeforeMultiWindow
            automaticWindowPage = false
        }
    }
    BackHandler(page != Page.CAMERA.name) {
        page = if (Page.valueOf(page) == Page.SETTINGS) settingsOrigin else Page.CAMERA.name
        automaticWindowPage = false
    }
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            val camera = Page.valueOf(page) == Page.CAMERA
            val light = !camera && !darkTheme
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = Page.valueOf(page),
            transitionSpec = {
                val forward = targetState != Page.CAMERA
                (
                    fadeIn() + slideInHorizontally(
                        initialOffsetX = { if (forward) it / 12 else -it / 12 }
                    )
                ).togetherWith(
                    fadeOut() + slideOutHorizontally(
                        targetOffsetX = { if (forward) -it / 12 else it / 12 }
                    )
                )
            },
            label = "pageTransition"
        ) { current ->
            when (current) {
                Page.CAMERA -> CameraScreen(
                    tasks = tasks,
                    activeCount = snapshot.active,
                    queuedCount = snapshot.queued,
                    onAlbum = {
                        selectedId = tasks.firstOrNull()?.id.orEmpty()
                        page = Page.ALBUM.name
                    },
                    onSettings = { settingsOrigin = Page.CAMERA.name; page = Page.SETTINGS.name }
                )

                Page.ALBUM -> AlbumScreen(
                    tasks = tasks,
                    selectedTask = snapshot.selected,
                    totalCount = snapshot.total,
                    onLoadMore = { historyLimit += 50 },
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    onBack = {
                        page = Page.CAMERA.name
                        automaticWindowPage = false
                    },
                    onSettings = { settingsOrigin = Page.ALBUM.name; page = Page.SETTINGS.name },
                    onRetry = { serviceAction(context, SolveService.ACTION_RETRY, it) },
                    onCancel = { serviceAction(context, SolveService.ACTION_CANCEL, it) },
                    onDelete = { id ->
                        val deletedIndex = tasks.indexOfFirst { it.id == id }
                        val nextSelected = tasks.getOrNull(deletedIndex + 1)?.id
                            ?: tasks.getOrNull(deletedIndex - 1)?.id
                        scope.launch {
                            try {
                                serviceAction(context, SolveService.ACTION_CANCEL, id)
                                TaskRepository.delete(id)
                                if (selectedId == id) selectedId = nextSelected.orEmpty()
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) {
                                appError = "记录暂时无法删除，诊断编号 ${LocalDiagnostics.record("delete", e)}"
                            }
                        }
                    }
                )

                Page.SETTINGS -> SettingsScreen(
                    onBack = { page = settingsOrigin },
                    onSaved = {
                        scope.launch {
                            try { TaskRepository.resumeQueue(context) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (_: Exception) { appError = "设置已保存，后台暂时无法启动，请返回前台重试" }
                        }
                    }
                )
            }
        }
    }
    if (appError.isNotBlank()) androidx.compose.material3.AlertDialog(
        onDismissRequest = { appError = "" },
        title = { androidx.compose.material3.Text("暂时无法完成操作") },
        text = { androidx.compose.material3.Text(appError) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { appError = "" }) { androidx.compose.material3.Text("知道了") } })
    AppUpdateHost()
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun serviceAction(context: Context, action: String, id: String) {
    val intent = Intent(context, SolveService::class.java)
        .setAction(action)
        .putExtra(SolveService.EXTRA_ID, id)
    context.startForegroundService(intent)
}
