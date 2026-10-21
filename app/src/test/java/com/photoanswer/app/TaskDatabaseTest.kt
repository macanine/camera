package com.photoanswer.app

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TaskDatabaseTest {
    @Test fun `late result cannot overwrite cancellation or recreate deleted task`() {
        TaskDatabase(RuntimeEnvironment.getApplication()).use { db ->
            db.insert(SolveTask("cancel", 1, "/image.jpg", "RUNNING"))
            db.update("cancel", "CANCELLED")
            db.update("cancel", "SUCCEEDED", answer = "late answer", activeOnly = true)
            assertEquals("CANCELLED", db.get("cancel")!!.status)
            assertEquals("", db.get("cancel")!!.answer)
            db.delete("cancel")
            db.update("cancel", "FAILED", error = "late error")
            assertNull(db.get("cancel"))
        }
    }

    @Test fun `process recovery interrupts only in flight tasks`() {
        TaskDatabase(RuntimeEnvironment.getApplication()).use { db ->
            db.insert(SolveTask("running", 1, "/image.jpg", "RUNNING"))
            db.insert(SolveTask("queued", 2, "/image.jpg", "QUEUED"))
            db.recoverAbandoned()
            assertEquals("INTERRUPTED", db.get("running")!!.status)
            assertEquals("QUEUED", db.get("queued")!!.status)
        }
    }

    @Test fun `new run rejects old callbacks and keeps completed primary on recovery`() {
        TaskDatabase(RuntimeEnvironment.getApplication()).use { db ->
            db.insert(SolveTask("generations", 1, "/image.jpg", "QUEUED"))
            val old = db.beginRun("generations")!!
            db.update(old.id, "CANCELLED")
            db.requeue(old.id)
            val next = db.beginRun(old.id)!!
            assertTrue(next.generation > old.generation)
            assertFalse(db.update(old.id, "RUNNING", draft = "OLD", expectedGeneration = old.generation, activeOnly = true))
            assertEquals("", db.get(old.id)!!.draft)
            db.update(next.id, "SUCCEEDED", answer = "B", verification = "RUNNING", expectedGeneration = next.generation)
            db.recoverAbandoned()
            assertEquals("B", db.get(next.id)!!.answer)
            assertEquals("SUCCEEDED", db.get(next.id)!!.status)
            assertEquals("UNAVAILABLE", db.get(next.id)!!.verification)
        }
    }

    @Test fun `version one migration preserves history and enables attempt records`() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("photoanswer.db")
        context.openOrCreateDatabase("photoanswer.db", 0, null).use { sql ->
            sql.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, createdAt INTEGER NOT NULL, imagePath TEXT NOT NULL, status TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '', provider TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '', attempts INTEGER NOT NULL DEFAULT 0)")
            sql.execSQL("INSERT INTO tasks VALUES('history',1,'/image.jpg','SUCCEEDED','B','old','model','',1)")
            sql.version = 1
        }
        TaskDatabase(context).use { db ->
            assertEquals("B", db.get("history")!!.answer)
            assertEquals(0, db.get("history")!!.generation)
            db.startAttempt("attempt", db.get("history")!!, "SOLVE", ProviderConfig("p", "provider", "https://example.com/v1", "m", "not persisted"))
            db.finishAttempt("attempt", "FAILED", RequestDiagnostics(finishReason = "length"), "truncated", "draft")
            assertTrue(db.attemptDetails("history").contains("length"))
            assertFalse(db.attemptDetails("history").contains("not persisted"))
            db.delete("history")
            db.finishAttempt("attempt", "SUCCEEDED", RequestDiagnostics())
            assertEquals("", db.attemptDetails("history"))
        }
    }
}
