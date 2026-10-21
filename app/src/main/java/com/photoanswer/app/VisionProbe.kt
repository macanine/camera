package com.photoanswer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import java.io.File
import java.security.SecureRandom

data class VisionProbe(val file: File, val code: String) {
    val expected: String get() = "校验码 $code；左边蓝色长方形，右边红色圆形"
    fun matches(answer: String): Boolean {
        val compact = answer.replace(Regex("\\s"), "")
        return compact.contains(code) &&
            Regex("左[^右]*(蓝[^右]*(长方形|矩形)|(长方形|矩形)[^右]*蓝)").containsMatchIn(compact) &&
            Regex("右[^左]*(红[^左]*圆|圆[^左]*红)").containsMatchIn(compact)
    }

    companion object {
        const val PROMPT = "请只根据图片回答：第一行的校验码是什么？左边和右边各是什么颜色、什么形状？用一行中文回答。看不清请写无法确定。"
        fun create(context: Context): VisionProbe {
            val code = (100000 + SecureRandom().nextInt(900000)).toString()
            val bitmap = createBitmap(640, 400, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 60f }
                canvas.drawText("CODE: $code", 60f, 85f, paint)
                paint.color = Color.BLUE
                canvas.drawRect(55f, 165f, 275f, 325f, paint)
                paint.color = Color.RED
                canvas.drawCircle(465f, 245f, 82f, paint)
                val file = File.createTempFile("vision_probe_", ".jpg", context.cacheDir)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                return VisionProbe(file, code)
            } finally { bitmap.recycle() }
        }
    }
}
