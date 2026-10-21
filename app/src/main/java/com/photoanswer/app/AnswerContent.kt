package com.photoanswer.app

import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import java.util.concurrent.Executors
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

private val mathExecutor = Executors.newFixedThreadPool(2)

internal fun answerRenderer(context: android.content.Context, textSize: Float): Markwon {
    ru.noties.jlatexmath.JLatexMathAndroid.init(context.applicationContext)
    return Markwon.builder(context)
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(JLatexMathPlugin.create(textSize) { builder ->
            builder.inlinesEnabled(true)
            builder.executorService(mathExecutor)
            builder.errorHandler { _, error ->
                android.util.Log.w("MathRenderer", "Formula rendering failed; keeping source", error)
                null
            }
            // Keep display equations at their natural height. Fitting a block
            // formula to the full canvas can reserve a screen-sized blank area
            // around short equations on phones.
            builder.theme().blockFitCanvas(false)
        }).build()
}

@OptIn(FlowPreview::class)
@Composable
fun AnswerContent(
    text: String,
    modifier: Modifier = Modifier,
    textSize: Int = 20,
    followEnd: Boolean = false,
    rawMode: Boolean = false
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { textSize.sp.toPx() }
    val renderer = remember(context, sizePx) { answerRenderer(context, sizePx) }
    val textColor = MaterialTheme.colorScheme.onBackground.answerTextColor
    val latestText by rememberUpdatedState(text)
    var renderedText by remember { mutableStateOf(text) }
    LaunchedEffect(followEnd) {
        if (followEnd) {
            snapshotFlow { latestText }.sample(220).collect { renderedText = it }
        } else {
            renderedText = latestText
        }
    }
    LaunchedEffect(text) {
        if (!followEnd) renderedText = text
    }
    val visibleText = if (followEnd) renderedText else text
    val markdown = remember(visibleText) { MathMarkdown.normalize(visibleText) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var userScrolledAway by remember { mutableStateOf(false) }
    val isDragged by scrollState.interactionSource.collectIsDraggedAsState()

    LaunchedEffect(isDragged, scrollState.value, scrollState.maxValue) {
        if (isDragged && scrollState.maxValue > 0) {
            userScrolledAway = scrollState.value < scrollState.maxValue - 28
        }
    }
    LaunchedEffect(followEnd) {
        if (!followEnd) userScrolledAway = false
    }
    LaunchedEffect(visibleText.length, followEnd, userScrolledAway) {
        if (followEnd && !userScrolledAway) {
            delay(56)
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
    val jumpVisible = followEnd && userScrolledAway

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
            factory = { viewContext ->
                TextView(viewContext).apply {
                    setTextColor(textColor)
                    typeface = Typeface.DEFAULT
                    setTextIsSelectable(true)
                    setLineSpacing(0f, 1.5f)
                    setPadding(0, 10, 0, 24)
                }
            },
            onRelease = { AsyncDrawableScheduler.unschedule(it) },
            update = { view ->
                val identity = listOf(visibleText, rawMode, sizePx, textColor)
                if (view.tag != identity) {
                    view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx)
                    view.setTextColor(textColor)
                    AsyncDrawableScheduler.unschedule(view)
                    if (rawMode) view.text = visibleText else renderer.setMarkdown(view, markdown)
                    view.tag = identity
                }
            }
        )
        if (jumpVisible) {
            FilledIconButton(
                onClick = {
                    userScrolledAway = false
                    scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            ) {
                Icon(Icons.Default.ArrowDownward, "跳到最新内容")
            }
        }
    }
}
