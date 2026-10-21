package com.photoanswer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.noties.markwon.image.AsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run against both debug and optimized release; catches reflection stripped by R8. */
@RunWith(AndroidJUnit4::class)
class FormulaReleaseTest {
    @Test fun inlineIntegralAlignedAndMatrixFormulasRender() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.targetContext.getSharedPreferences("updates", 0).edit().putBoolean("automatic", false).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var view: TextView
            lateinit var formulas: Array<AsyncDrawableSpan>
            val bitmap = Bitmap.createBitmap(1080, 1800, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            fun draw() {
                view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, 1080, 1800)
                view.viewTreeObserver.dispatchOnPreDraw()
                view.draw(canvas)
            }
            scenario.onActivity { activity ->
                view = TextView(activity).apply { textSize = 22f }
                activity.setContentView(view)
                val content = """
                    Inline: \(x=\frac{-b\pm\sqrt{b^2-4ac}}{2a}\).

                    \[\int_0^1 x^2\,dx=\frac{1}{3}\]

                    \[\begin{aligned}x+y&=3\\x-y&=1\end{aligned}\]

                    \[\begin{pmatrix}1&2\\3&4\end{pmatrix}\]
                """.trimIndent()
                answerRenderer(activity, view.textSize).setMarkdown(view, MathMarkdown.normalize(content))
                formulas = (view.text as android.text.Spanned).getSpans(0, view.text.length, AsyncDrawableSpan::class.java)
                draw()
            }
            assertEquals(4, formulas.size)
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (formulas.any { !it.drawable.hasResult() } && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25)
                instrumentation.waitForIdleSync()
                scenario.onActivity { draw() }
            }
            assertTrue("All formula drawables must render, including reflection-based matrix environments", formulas.all { it.drawable.hasResult() })
            bitmap.recycle()
        }
    }
}
