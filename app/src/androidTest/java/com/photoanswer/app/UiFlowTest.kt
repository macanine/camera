package com.photoanswer.app

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercise the real navigation, retained editor and asynchronous record detail. No external requests. */
class UiFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Before fun isolateLocalState() {
        val context = ui.activity.applicationContext
        context.getSharedPreferences("updates", 0).edit().putBoolean("automatic", false).commit()
        SecurePrefs.save(context, emptyList())
        TaskStore.db.all().forEach { TaskStore.db.delete(it.id) }
    }

    @Test fun connectionDraftSurvivesRotationAndRequiresExplicitDiscard() {
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onAllNodesWithText("添加模型").onFirst().performClick()
        ui.onNodeWithText("模型 ID").performScrollTo().performTextInput("fixture-vision")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("模型 ID").performScrollTo()
        ui.onNodeWithText("fixture-vision").assertExists()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithText("放弃未保存的更改？").assertIsDisplayed()
        screenshot("settings-discard")
        ui.onNodeWithText("继续编辑").performClick()
        ui.onNodeWithText("fixture-vision").assertExists()
    }

    @Test fun savedConnectionReturnsExpandedAfterRecreation() {
        ui.onNodeWithContentDescription("设置").performClick()
        ui.onAllNodesWithText("添加模型").onFirst().performClick()
        ui.onNodeWithText("模型 ID").performScrollTo().performTextInput("local-fixture")
        ui.onNodeWithText("保存设置").performClick()
        ui.activityRule.scenario.recreate()
        ui.waitUntil(10_000) {
            ui.onAllNodesWithText("设置已保存").fetchSemanticsNodes().isNotEmpty()
        }
        ui.onNodeWithText("模型 ID").performScrollTo()
        ui.onNodeWithText("local-fixture").assertExists()
        assertTrue(SecurePrefs.load(ui.activity).any { it.model == "local-fixture" })
        screenshot("settings-editor")
    }

    @Test fun historySwitchLoadsCompleteContentAndPreservesGeneratedWords() {
        val now = System.currentTimeMillis()
        for ((id, time, body) in listOf(Triple("ui-new", now, "NEW 内容：答案保持原样"),
            Triple("ui-old", now - 60_000, "OLD 内容：题目保持原样"))) {
            TaskStore.db.insert(SolveTask(id, time, "/missing-fixture.jpg", "SUCCEEDED"))
            TaskStore.db.update(id, "SUCCEEDED", answer = body + "\n" + "完整记录内容 ".repeat(80))
        }
        val actions = ui.onNodeWithContentDescription("相册").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        ui.runOnIdle {
            assertTrue(actions.single { it.label == "打开相册" }.action())
        }
        awaitRendered("NEW 内容：答案保持原样")
        ui.onNodeWithContentDescription("上一条记录").performClick()
        awaitRendered("OLD 内容：题目保持原样")
        screenshot("album-content")
        ui.onNodeWithContentDescription("更多操作").performClick()
        ui.onNodeWithText("删除记录").performClick()
        ui.onNodeWithText("删除这条记录？").assertIsDisplayed()
        ui.onNodeWithText("保留记录").performClick()
        awaitRendered("OLD 内容：题目保持原样")
    }

    private fun awaitRendered(prefix: String) {
        ui.waitUntil(10_000) {
            var found = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                fun visit(view: View) {
                    if (view is TextView && view.text.startsWith(prefix) && view.text.length > 500) found = true
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                visit(ui.activity.window.decorView)
            }
            found
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: ui.activity.getExternalFilesDir(null)!!.absolutePath
        val folder = File(output, "ui-review").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
