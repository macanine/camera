package com.photoanswer.app

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

internal object AppRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

internal object LocalDiagnostics {
    fun record(stage: String, error: Throwable): String {
        val id = UUID.randomUUID().toString().take(8)
        // Exception messages may contain credentials or model content; retain frames and types only.
        android.util.Log.e("LocalFailure", "$id $stage ${error.javaClass.name}\n" +
            error.stackTrace.take(12).joinToString("\n") { it.toString() })
        return id
    }
}

internal data class HistorySnapshot(
    val tasks: List<SolveTask> = emptyList(), val selected: SolveTask? = null,
    val total: Int = 0, val active: Int = 0, val queued: Int = 0
)

/** The only UI boundary for durable task reads, recovery and file deletion. */
internal object TaskRepository {
    private val initMutex = Mutex()
    private var initialized = false
    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (!initialized) {
                TaskStore.init(context)
                TaskStore.db.recoverAbandoned()
                initialized = true
            }
        }
    }

    fun observe(limit: Int, selectedId: String): Flow<HistorySnapshot> = TaskStore.db.changes.map {
        val db = TaskStore.db
        val tasks = db.history(limit)
        val selected = selectedId.takeIf(String::isNotBlank)?.let(db::get) ?: tasks.firstOrNull()?.let { db.get(it.id) }
        HistorySnapshot(tasks, selected, db.count(), db.activeCount(), db.count("QUEUED"))
    }.flowOn(Dispatchers.IO).conflate()

    suspend fun resumeQueue(context: Context, providers: () -> List<ProviderConfig> = { SecurePrefs.load(context) },
        start: () -> Unit = { SolveService.start(context) }) = withContext(Dispatchers.IO) {
        initialize(context)
        if (TaskStore.db.count("QUEUED") > 0 &&
            providers().any { it.enabled && !it.fallbackOnly && it.isConfigured }) {
            start()
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val task = TaskStore.db.get(id) ?: return@withContext
        // Invalidate callbacks before touching the file. Keep the row if file deletion
        // fails so its path remains available for a later user retry.
        TaskStore.db.update(id, if (task.status in setOf("QUEUED", "RUNNING", "SWITCHING", "VERIFYING")) "CANCELLED" else task.status,
            generation = task.generation + 1, verification = if (task.verification in setOf("PENDING", "RUNNING")) "CANCELLED" else task.verification)
        val file = File(task.imagePath)
        check(!file.exists() || file.delete()) { "Private file could not be removed" }
        TaskStore.db.delete(id)
    }
}
