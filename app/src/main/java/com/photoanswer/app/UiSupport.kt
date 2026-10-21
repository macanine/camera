package com.photoanswer.app

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal fun historyTimeLabel(epoch: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val time = Calendar.getInstance().apply { timeInMillis = epoch }
    val sameYear = now.get(Calendar.YEAR) == time.get(Calendar.YEAR)
    val sameDay = sameYear && now.get(Calendar.DAY_OF_YEAR) == time.get(Calendar.DAY_OF_YEAR)
    val previousDay = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val yesterday = previousDay.get(Calendar.YEAR) == time.get(Calendar.YEAR) && previousDay.get(Calendar.DAY_OF_YEAR) == time.get(Calendar.DAY_OF_YEAR)
    val timePart = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch))
    return when {
        sameDay -> "今天 $timePart"
        yesterday -> "昨天 $timePart"
        sameYear -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(epoch))
        else -> SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(epoch))
    }
}

internal fun historyBucket(epoch: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val time = Calendar.getInstance().apply { timeInMillis = epoch }
    val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay(now, time) -> "今天"
        sameDay(yesterday, time) -> "昨天"
        now.get(Calendar.YEAR) == time.get(Calendar.YEAR) && now.get(Calendar.MONTH) == time.get(Calendar.MONTH) -> "本月"
        now.get(Calendar.YEAR) == time.get(Calendar.YEAR) -> java.text.SimpleDateFormat("M月", Locale.getDefault()).format(Date(epoch))
        else -> java.text.SimpleDateFormat("yyyy年M月", Locale.getDefault()).format(Date(epoch))
    }
}

internal fun neutralUiCopy(text: String): String = text.replace("解题", "处理").replace("答案", "内容")
    .replace("解答", "内容").replace("题目", "条目").replace("原题", "原图").replace("题号", "编号")

internal fun friendlyError(raw: String, status: String): String {
    val text = neutralUiCopy(raw.trim())
    return when {
        text.contains("没有返回有效进展") -> text.take(500)
        text.contains("401") || text.contains("403") ->
            "密钥无效或没有访问权限，请到设置检查接口与密钥。"
        text.contains("404") -> "接口或模型不存在，请检查接口地址和模型名称。"
        text.contains("408") || text.contains("429") ->
            "服务当前繁忙，请稍后重试。"
        text.contains("网络连接失败") || text.contains("timeout", true) ||
            text.contains("超时") -> "网络连接不稳定，请检查网络后重试。"
        text.contains("图片") && (text.contains("无法") || text.contains("无效")) ->
            "照片读取失败，请重新拍摄。"
        status == "INTERRUPTED" -> "处理被中断，可以重新处理。"
        text.isBlank() -> "处理没有完成，请重试。"
        else -> text.take(500)
    }
}

internal fun View.tapHaptic() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
    )
}

internal val Color.answerTextColor: Int
    get() = android.graphics.Color.argb(
        (alpha * 255).toInt().coerceIn(0, 255),
        (red * 255).toInt().coerceIn(0, 255),
        (green * 255).toInt().coerceIn(0, 255),
        (blue * 255).toInt().coerceIn(0, 255)
    )

@Composable
internal fun currentStatusDescription(status: String): String = when (status) {
    "QUEUED" -> "等待可用的模型"
    "RUNNING" -> "正在处理内容"
    "SWITCHING" -> "正在切换到其他模型"
    "VERIFYING" -> "正在由其他模型校验"
    "SUCCEEDED" -> "已完成"
    "CANCELLED" -> "已停止"
    "INTERRUPTED" -> "处理已中断"
    else -> "处理失败"
}

internal val isBusyStatus: (String) -> Boolean = {
    it in setOf("QUEUED", "RUNNING", "SWITCHING", "VERIFYING")
}
