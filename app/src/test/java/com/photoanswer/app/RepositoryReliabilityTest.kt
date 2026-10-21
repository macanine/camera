package com.photoanswer.app

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RepositoryReliabilityTest {
    @Before fun cleanHistory() {
        TaskStore.init(RuntimeEnvironment.getApplication())
        TaskStore.db.all().forEach { TaskStore.db.delete(it.id) }
    }
    @Test fun `foreground wake starts queued work without restarting interrupted requests`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        TaskStore.init(context)
        TaskStore.db.insert(SolveTask("queued", 1, "/queued.jpg", "QUEUED"))
        TaskStore.db.insert(SolveTask("running", 2, "/running.jpg", "INTERRUPTED"))
        var starts = 0
        val provider = ProviderConfig("p", "provider", "https://example.com/v1", "m", "fake")
        TaskRepository.resumeQueue(context, { listOf(provider) }, { starts++ })
        assertEquals(1, starts)
        assertEquals("INTERRUPTED", TaskStore.db.get("running")!!.status)
        assertEquals("QUEUED", TaskStore.db.get("queued")!!.status)
        TaskRepository.resumeQueue(context, { emptyList() }, { starts++ })
        assertEquals(1, starts)
    }

    @Test fun `history pages omit bodies while selected detail remains complete and changes are observed`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        TaskRepository.initialize(context)
        val body = "照片内容".repeat(10_000)
        repeat(120) { index ->
            val id = "row-$index"
            TaskStore.db.insert(SolveTask(id, index.toLong(), "/$id.jpg", "QUEUED"))
            TaskStore.db.update(id, "SUCCEEDED", answer = body, solutionJson = "large structure")
        }
        val page = TaskRepository.observe(50, "row-119").first()
        assertEquals(120, page.total)
        assertEquals(50, page.tasks.size)
        assertTrue(page.tasks.all { it.answer.length <= 120 && it.solutionJson.isEmpty() })
        assertEquals(body, page.selected!!.answer)
        val revision = TaskStore.db.changes.value
        val next = async { withTimeout(3000) { TaskStore.db.changes.first { it > revision } } }
        TaskStore.db.update("row-119", "NEEDS_REVIEW")
        next.await()
        assertEquals(100, TaskRepository.observe(100, "row-119").first().tasks.size)
    }

    @Test fun `invalid encrypted credentials and malformed configuration produce explicit errors`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("providers", 0)
        prefs.edit().putString("list", "not json").commit()
        assertThrows(SettingsStorageException::class.java) { SecurePrefs.load(context) }
        SecurePrefs.save(context, emptyList())
        assertTrue(SecurePrefs.load(context).isEmpty())
        assertThrows(SettingsStorageException::class.java) { SecurePrefs.decrypt("broken") }
    }
}
