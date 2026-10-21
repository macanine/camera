package com.photoanswer.app

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.TextView
import io.noties.markwon.image.AsyncDrawableSpan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidRenderingTest {
    @Test fun `model math produces inline and display drawables and readable answer screenshot`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val view = TextView(activity).apply {
                textSize = 22f
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.WHITE)
                setPadding(32, 32, 32, 32)
            }
            activity.setContentView(view)
            val answer = """
                # Solution
                Inline: \(x=\frac{-b\pm\sqrt{b^2-4ac}}{2a}\).

                Integral:
                \[\int_0^1 x^2\,dx=\frac{1}{3}\]

                Aligned steps:
                $$
                \begin{aligned}x+y&=3\\x-y&=1\\x&=2,\quad y=1\end{aligned}
                $$

                Matrix:
                \[\begin{pmatrix}1&2\\3&4\end{pmatrix}\]
                Final answer: x = 2, y = 1.
            """.trimIndent()
            answerRenderer(activity, view.textSize).setMarkdown(view, MathMarkdown.normalize(answer))
            val spans = (view.text as android.text.Spanned).getSpans(0, view.text.length, AsyncDrawableSpan::class.java)
            assertEquals(4, spans.size)
            val bitmap = Bitmap.createBitmap(1000, 1500, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            fun draw() {
                view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 1000, 1500)
                view.viewTreeObserver.dispatchOnPreDraw()
                view.draw(canvas)
            }
            draw()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (spans.any { !it.drawable.hasResult() } && System.nanoTime() < deadline) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
                draw()
            }
            val diagnostics = spans.joinToString("\n") { it.drawable.toString() } +
                org.robolectric.shadows.ShadowLog.getLogsForTag("MathRenderer").joinToString("\n") { it.throwable?.stackTraceToString().orEmpty() }
            assertTrue("All four formulas must render successfully: $diagnostics", spans.all { it.drawable.hasResult() })
            val output = File("build/reports/rendering/math-answer.png").apply { parentFile!!.mkdirs() }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun `launcher and service resolve to real classes`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(context.packageName)!!
        val activity = pm.resolveActivity(intent, 0)!!.activityInfo
        assertEquals(MainActivity::class.java.name, activity.name)
        val service = pm.getServiceInfo(android.content.ComponentName(context, SolveService::class.java), 0)
        assertEquals(SolveService::class.java.name, service.name)
        assertEquals("相机", context.getString(com.nic.photoanswer.R.string.app_name))
    }

    @Test fun `vision probe checks random code colors shapes and positions`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val probe = VisionProbe.create(context)
        try {
            assertTrue(probe.file.length() > 0)
            assertTrue(probe.matches(probe.expected))
            assertFalse(probe.matches("校验码 000000；左边蓝色长方形，右边红色圆形"))
            assertFalse(probe.matches("校验码 ${probe.code}；左边红色圆形，右边蓝色长方形"))
        } finally { probe.file.delete() }
    }

    @Test fun `image encoding retains page detail and respects an explicit edge limit`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val file = File.createTempFile("page-detail", ".jpg", context.cacheDir)
        try {
            val page = Bitmap.createBitmap(3000, 1800, Bitmap.Config.ARGB_8888)
            page.eraseColor(Color.WHITE)
            file.outputStream().use { page.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            page.recycle()
            fun dimensions(maxEdge: Int): Pair<Int, Int> {
                val bytes = android.util.Base64.decode(ModelClient.encodeImage(file.absolutePath, maxEdge), android.util.Base64.NO_WRAP)
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                return bounds.outWidth to bounds.outHeight
            }
            assertEquals(3000 to 1800, dimensions(4096))
            assertEquals(2048 to 1228, dimensions(2048))
        } finally { file.delete() }
    }

    @Test fun `image encoding respects EXIF rotation`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val file = File.createTempFile("rotation", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            androidx.exifinterface.media.ExifInterface(file.absolutePath).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val bytes = android.util.Base64.decode(ModelClient.encodeImage(file.absolutePath), android.util.Base64.NO_WRAP)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            assertEquals(60, bounds.outWidth)
            assertEquals(120, bounds.outHeight)
        } finally { file.delete() }
    }
}
