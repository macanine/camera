package com.photoanswer.app

import android.app.Application
import android.content.Intent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SolveServiceTest {
    private val primary = ProviderConfig("a", "primary", "https://a.example/v1", "vision", "fake", allowVerification = false)
    private val reviewer = ProviderConfig("b", "reviewer", "https://b.example/v1", "review", "fake")
    private val answer = """{"questions":[{"number":"1","answer":"B","status":"answered"}]}"""
    private suspend fun awaitTask(id: String, condition: (SolveTask) -> Boolean): SolveTask = withTimeout(8000) {
        while (true) {
            TaskStore.db.get(id)?.let { if (condition(it)) return@withTimeout it }
            delay(20)
        }
        error("unreachable")
    }
    private fun action(service: SolveService, action: String, id: String) = service.onStartCommand(
        Intent().setAction(action).putExtra(SolveService.EXTRA_ID, id), 0, 1)

    @Test fun `primary is published before review finishes and stopping review preserves it`() = runBlocking {
        val controller = Robolectric.buildService(SolveService::class.java).create()
        val service = controller.get()
        val entered = CompletableDeferred<Unit>()
        try {
            service.providerSource = { listOf(primary, reviewer) }
            service.engine = object : SolveEngine {
                override suspend fun solve(task: SolveTask, provider: ProviderConfig, progress: (String) -> Unit, partial: (String) -> Unit) = ModelAnswer(answer, provider.name, provider.model)
                override suspend fun verify(task: SolveTask, answer: ModelAnswer, provider: ProviderConfig): ModelAnswer {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            TaskStore.db.insert(SolveTask("published", 1, "/fake.jpg", "QUEUED"))
            service.onStartCommand(null, 0, 1)
            withTimeout(8000) { entered.await() }
            val published = awaitTask("published") { it.verification == "RUNNING" }
            assertEquals("SUCCEEDED", published.status)
            assertEquals("1. B", published.answer)
            // A review must not consume the initial one-task solving window.
            TaskStore.db.insert(SolveTask("next-photo", 2, "/fake.jpg", "QUEUED"))
            assertEquals("1. B", awaitTask("next-photo") { it.status == "SUCCEEDED" }.answer)
            action(service, SolveService.ACTION_CANCEL, "published")
            assertEquals("1. B", awaitTask("published") { it.verification == "CANCELLED" }.answer)
        } finally { controller.destroy() }
    }

    @Test fun `single provider transient failure retries and persists both attempts`() = runBlocking {
        val controller = Robolectric.buildService(SolveService::class.java).create()
        val service = controller.get()
        val calls = AtomicInteger()
        try {
            service.providerSource = { listOf(primary) }
            service.engine = object : SolveEngine {
                override suspend fun solve(task: SolveTask, provider: ProviderConfig, progress: (String) -> Unit, partial: (String) -> Unit): ModelAnswer {
                    if (calls.incrementAndGet() == 1) throw ModelException(503, "busy")
                    return ModelAnswer(answer, provider.name, provider.model)
                }
                override suspend fun verify(task: SolveTask, answer: ModelAnswer, provider: ProviderConfig): ModelAnswer = error("Unexpected review")
            }
            TaskStore.db.insert(SolveTask("retry", 1, "/fake.jpg", "QUEUED"))
            service.onStartCommand(null, 0, 1)
            assertEquals(2, awaitTask("retry") { it.status == "SUCCEEDED" }.attempts)
            val records = TaskStore.db.attemptDetails("retry")
            assertTrue(records.contains("FAILED"))
            assertTrue(records.contains("SUCCEEDED"))
        } finally { controller.destroy() }
    }

    @Test fun `cancel retry ignores old partial callbacks and old cleanup`() = runBlocking {
        val controller = Robolectric.buildService(SolveService::class.java).create()
        val service = controller.get()
        val oldCallback = AtomicReference<((String) -> Unit)?>(null)
        val second = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        try {
            service.providerSource = { listOf(primary) }
            service.engine = object : SolveEngine {
                override suspend fun solve(task: SolveTask, provider: ProviderConfig, progress: (String) -> Unit, partial: (String) -> Unit): ModelAnswer {
                    if (calls.incrementAndGet() == 1) {
                        oldCallback.set(partial)
                        awaitCancellation()
                    }
                    second.complete(Unit)
                    finish.await()
                    return ModelAnswer(answer, provider.name, provider.model)
                }
                override suspend fun verify(task: SolveTask, answer: ModelAnswer, provider: ProviderConfig): ModelAnswer = error("Unexpected review")
            }
            TaskStore.db.insert(SolveTask("cancel-retry", 1, "/fake.jpg", "QUEUED"))
            service.onStartCommand(null, 0, 1)
            withTimeout(8000) { while (oldCallback.get() == null) delay(10) }
            action(service, SolveService.ACTION_CANCEL, "cancel-retry")
            awaitTask("cancel-retry") { it.status == "CANCELLED" }
            action(service, SolveService.ACTION_RETRY, "cancel-retry")
            withTimeout(8000) { second.await() }
            oldCallback.get()!!.invoke("OLD ANSWER")
            assertEquals("", TaskStore.db.get("cancel-retry")!!.draft)
            finish.complete(Unit)
            assertEquals("1. B", awaitTask("cancel-retry") { it.status == "SUCCEEDED" }.answer)
        } finally { controller.destroy() }
    }
    @Test fun `blocked normal connection ends the next queued task despite usable standby`() = runBlocking {
        val controller = Robolectric.buildService(SolveService::class.java).create()
        val service = controller.get()
        val standby = reviewer.copy(fallbackOnly = true)
        val calls = AtomicInteger()
        try {
            service.providerSource = { listOf(primary, standby) }
            service.engine = object : SolveEngine {
                override suspend fun solve(task: SolveTask, provider: ProviderConfig, progress: (String) -> Unit, partial: (String) -> Unit): ModelAnswer {
                    calls.incrementAndGet()
                    if (provider.id == primary.id) throw ModelException(401, "invalid credential")
                    return ModelAnswer(answer, provider.name, provider.model)
                }
                override suspend fun verify(task: SolveTask, answer: ModelAnswer, provider: ProviderConfig): ModelAnswer = error("Unexpected review")
            }
            TaskStore.db.insert(SolveTask("first", 1, "/fake.jpg", "QUEUED"))
            TaskStore.db.insert(SolveTask("second", 2, "/fake.jpg", "QUEUED"))
            service.onStartCommand(null, 0, 1)
            awaitTask("first") { it.status == "SUCCEEDED" }
            val next = awaitTask("second") { it.status == "FAILED" }
            assertEquals(0, next.attempts)
            assertEquals(2, calls.get())
            assertTrue(next.error.contains("常规连接"))
        } finally { controller.destroy() }
    }

}
