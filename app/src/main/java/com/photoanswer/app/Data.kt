package com.photoanswer.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.database.sqlite.transaction
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SolveTask(
    val id: String, val createdAt: Long, val imagePath: String, val status: String,
    val answer: String = "", val provider: String = "", val model: String = "",
    val error: String = "", val attempts: Int = 0, val generation: Int = 0,
    val draft: String = "", val progress: String = "", val verification: String = "NONE",
    val solutionJson: String = "", val suggestion: String = ""
)

class TaskDatabase(context: Context) : SQLiteOpenHelper(context, "photoanswer.db", null, 3) {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    private fun changed() { revision.value += 1 }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE tasks (
            id TEXT PRIMARY KEY, createdAt INTEGER NOT NULL, imagePath TEXT NOT NULL,
            status TEXT NOT NULL, answer TEXT NOT NULL DEFAULT '', provider TEXT NOT NULL DEFAULT '',
            model TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '', attempts INTEGER NOT NULL DEFAULT 0,
            generation INTEGER NOT NULL DEFAULT 0, draft TEXT NOT NULL DEFAULT '', progress TEXT NOT NULL DEFAULT '',
            verification TEXT NOT NULL DEFAULT 'NONE', solutionJson TEXT NOT NULL DEFAULT '', suggestion TEXT NOT NULL DEFAULT ''
        )""")
        createAttempts(db)
        createTaskIndexes(db)
    }
    private fun createTaskIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS tasks_history ON tasks(createdAt DESC, id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS tasks_queue ON tasks(status, createdAt, id)")
    }
    private fun createAttempts(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS attempts (
            id TEXT PRIMARY KEY, taskId TEXT NOT NULL, generation INTEGER NOT NULL,
            stage TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL,
            startedAt INTEGER NOT NULL, endedAt INTEGER, status TEXT NOT NULL,
            diagnostics TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '', draft TEXT NOT NULL DEFAULT ''
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS attempts_task ON attempts(taskId, startedAt)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN generation INTEGER NOT NULL DEFAULT 0")
            for (column in listOf("draft", "progress", "solutionJson", "suggestion")) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN $column TEXT NOT NULL DEFAULT ''")
            }
            db.execSQL("ALTER TABLE tasks ADD COLUMN verification TEXT NOT NULL DEFAULT 'NONE'")
            createAttempts(db)
        }
        if (oldVersion < 3) createTaskIndexes(db)
    }
    @Synchronized fun insert(task: SolveTask) {
        writableDatabase.execSQL("INSERT INTO tasks(id,createdAt,imagePath,status) VALUES(?,?,?,?)",
            arrayOf<Any>(task.id, task.createdAt, task.imagePath, task.status))
        changed()
    }
    @Synchronized fun all(): List<SolveTask> = readableDatabase.rawQuery("SELECT * FROM tasks ORDER BY createdAt DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(c.toTask()) }
    }
    @Synchronized fun get(id: String): SolveTask? = readableDatabase.rawQuery("SELECT * FROM tasks WHERE id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) c.toTask() else null
    }
    /** List rows deliberately exclude full bodies; only the selected record loads those. */
    @Synchronized fun history(limit: Int): List<SolveTask> = readableDatabase.rawQuery(
        "SELECT id,createdAt,imagePath,status,substr(answer,1,120),verification FROM tasks ORDER BY createdAt DESC,id DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 100_000).toString())
    ).use { c -> buildList { while (c.moveToNext()) add(SolveTask(c.getString(0), c.getLong(1), c.getString(2),
        c.getString(3), answer = c.getString(4), verification = c.getString(5))) } }
    @Synchronized fun queued(limit: Int = 4): List<SolveTask> = readableDatabase.rawQuery(
        "SELECT * FROM tasks WHERE status='QUEUED' ORDER BY createdAt,id LIMIT ?", arrayOf(limit.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.toTask()) } }
    @Synchronized fun count(status: String? = null): Int = readableDatabase.rawQuery(
        if (status == null) "SELECT COUNT(*) FROM tasks" else "SELECT COUNT(*) FROM tasks WHERE status=?",
        status?.let { arrayOf(it) }
    ).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun activeCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM tasks WHERE status IN ('RUNNING','SWITCHING','VERIFYING')", null
    ).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun failQueued(message: String) {
        writableDatabase.execSQL("UPDATE tasks SET status='FAILED',error=? WHERE status='QUEUED'", arrayOf(message))
        changed()
    }
    private fun android.database.Cursor.toTask(): SolveTask {
        fun str(name: String) = getString(getColumnIndexOrThrow(name))
        fun int(name: String) = getInt(getColumnIndexOrThrow(name))
        return SolveTask(str("id"), getLong(getColumnIndexOrThrow("createdAt")), str("imagePath"), str("status"),
            str("answer"), str("provider"), str("model"), str("error"), int("attempts"), int("generation"),
            str("draft"), str("progress"), str("verification"), str("solutionJson"), str("suggestion"))
    }
    @Synchronized fun beginRun(id: String): SolveTask? {
        val task = get(id)?.takeIf { it.status == "QUEUED" } ?: return null
        update(id, "RUNNING", answer = "", draft = "", error = "", progress = "正在连接模型",
            verification = "NONE", solutionJson = "", suggestion = "", generation = task.generation + 1)
        return get(id)
    }
    @Synchronized fun requeue(id: String) {
        val task = get(id) ?: return
        if (task.status !in setOf("FAILED", "INTERRUPTED", "CANCELLED", "NEEDS_REVIEW")) return
        update(id, "QUEUED", answer = "", draft = "", error = "", progress = "", verification = "NONE",
            solutionJson = "", suggestion = "", generation = task.generation + 1)
    }
    @Synchronized fun update(
        id: String, status: String, answer: String? = null, provider: String? = null,
        model: String? = null, error: String? = null, attempts: Int? = null, activeOnly: Boolean = false,
        expectedGeneration: Int? = null, generation: Int? = null, draft: String? = null,
        progress: String? = null, verification: String? = null, solutionJson: String? = null, suggestion: String? = null
    ): Boolean {
        val current = get(id) ?: return false
        if (expectedGeneration != null && current.generation != expectedGeneration) return false
        if (activeOnly && current.status !in setOf("RUNNING", "SWITCHING", "VERIFYING")) return false
        val next = current.copy(status = status, answer = answer ?: current.answer, provider = provider ?: current.provider,
            model = model ?: current.model, error = error ?: current.error, attempts = attempts ?: current.attempts,
            generation = generation ?: current.generation, draft = draft ?: current.draft, progress = progress ?: current.progress,
            verification = verification ?: current.verification, solutionJson = solutionJson ?: current.solutionJson, suggestion = suggestion ?: current.suggestion)
        if (next == current) return true
        writableDatabase.execSQL("""UPDATE tasks SET status=?,answer=?,provider=?,model=?,error=?,attempts=?,
            generation=?,draft=?,progress=?,verification=?,solutionJson=?,suggestion=? WHERE id=?""",
            arrayOf<Any>(status, answer ?: current.answer, provider ?: current.provider, model ?: current.model,
                error ?: current.error, attempts ?: current.attempts, generation ?: current.generation,
                draft ?: current.draft, progress ?: current.progress, verification ?: current.verification,
                solutionJson ?: current.solutionJson, suggestion ?: current.suggestion, id))
        changed()
        return true
    }
    @Synchronized fun startAttempt(id: String, task: SolveTask, stage: String, provider: ProviderConfig) {
        writableDatabase.execSQL("""INSERT INTO attempts(id,taskId,generation,stage,provider,model,startedAt,status)
            SELECT ?,?,?,?,?,?,?,'RUNNING' WHERE EXISTS (SELECT 1 FROM tasks WHERE id=? AND generation=?)""",
            arrayOf<Any>(id, task.id, task.generation, stage, provider.name, provider.model,
                System.currentTimeMillis(), task.id, task.generation))
    }
    @Synchronized fun finishAttempt(id: String, status: String, diagnostics: RequestDiagnostics, error: String = "", draft: String = "") {
        writableDatabase.execSQL("UPDATE attempts SET endedAt=?,status=?,diagnostics=?,error=?,draft=? WHERE id=? AND status='RUNNING'",
            arrayOf<Any>(System.currentTimeMillis(), status, diagnostics.json(), error, draft, id))
    }
    @Synchronized fun attemptDetails(taskId: String): String = readableDatabase.rawQuery(
        "SELECT stage,provider,model,status,diagnostics,error FROM attempts WHERE taskId=? ORDER BY startedAt DESC LIMIT 30", arrayOf(taskId)
    ).use { c -> buildList {
        while (c.moveToNext()) add("${c.getString(0)} · ${c.getString(1)} · ${c.getString(2)} · ${c.getString(3)}\n${c.getString(4)}\n${c.getString(5)}")
    }.joinToString("\n\n") }
    @Synchronized fun delete(id: String) {
        writableDatabase.transaction {
            writableDatabase.delete("attempts", "taskId=?", arrayOf(id))
            writableDatabase.delete("tasks", "id=?", arrayOf(id))
        }
        changed()
    }
    @Synchronized fun recoverAbandoned() {
        interruptActive("应用进程曾中断，请手动重试")
        writableDatabase.execSQL("UPDATE attempts SET status='INTERRUPTED', endedAt=? WHERE status='RUNNING'", arrayOf<Any>(System.currentTimeMillis()))
    }
    @Synchronized fun interruptActive(message: String) {
        writableDatabase.execSQL("""UPDATE tasks SET generation=generation+1, progress='',
            error=CASE WHEN status IN ('RUNNING','SWITCHING','VERIFYING') THEN ? ELSE error END,
            status=CASE WHEN status IN ('RUNNING','SWITCHING','VERIFYING') THEN 'INTERRUPTED' ELSE status END,
            verification=CASE WHEN verification IN ('PENDING','RUNNING') THEN 'UNAVAILABLE' ELSE verification END
            WHERE status IN ('RUNNING','SWITCHING','VERIFYING') OR verification IN ('PENDING','RUNNING')""", arrayOf<Any>(message))
        changed()
    }
    @Synchronized fun interruptRun(id: String, generation: Int, message: String) {
        val task = get(id)?.takeIf { it.generation == generation } ?: return
        if (task.status in setOf("RUNNING", "SWITCHING", "VERIFYING")) {
            update(id, "INTERRUPTED", error = message, progress = "", generation = generation + 1)
        } else if (task.verification in setOf("PENDING", "RUNNING")) {
            update(id, task.status, verification = "UNAVAILABLE", generation = generation + 1)
        }
    }
}

object SecurePrefs {
    private const val KEY_ALIAS = "photoanswer.providers.aes"
    private lateinit var prefs: android.content.SharedPreferences
    private fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.applicationContext.getSharedPreferences("providers", Context.MODE_PRIVATE)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    internal fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        return try {
        val raw = Base64.decode(value, Base64.NO_WRAP)
        require(raw.size >= 28)
        val iv = raw.copyOfRange(0, 12)
        val body = raw.copyOfRange(12, raw.size)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(doFinal(body), Charsets.UTF_8)
        }
        } catch (_: Exception) { throw SettingsStorageException("保存的密钥无法读取，请重新输入后保存") }
    }

    @Synchronized fun load(context: Context): List<ProviderConfig> {
        init(context)
        val arr = try { JSONArray(prefs.getString("list", "[]")) }
            catch (_: Exception) { throw SettingsStorageException("保存的连接配置无法读取，请恢复备份或重新配置") }
        return (0 until arr.length()).map { i ->
            try {
                val o = arr.getJSONObject(i)
                ProviderConfig(o.getString("id"), o.optString("name"), o.optString("baseUrl"),
                    o.optString("model"), decrypt(prefs.getString("key_${o.getString("id")}", "") ?: ""),
                    o.optInt("priority"), o.optBoolean("enabled", true),
                    o.optBoolean("allowFallback", true), o.optInt("maxConcurrent", 1).coerceIn(1, 4),
                    o.optBoolean("fallbackOnly", false), o.optBoolean("allowVerification", true), o.optBoolean("streaming", true),
                    o.optInt("imageMaxEdge", 4096).coerceIn(2048, 6144), o.optInt("progressTimeoutSeconds", 300).coerceIn(30, 900))
            } catch (e: SettingsStorageException) { throw e }
            catch (_: Exception) { throw SettingsStorageException("保存的连接配置格式无效，请恢复备份或重新配置") }
        }.sortedBy { it.priority }
    }

    @Synchronized fun save(context: Context, providers: List<ProviderConfig>) {
        init(context)
        // Read old key names without decrypting: re-entering credentials can repair unreadable keys.
        val old = prefs.all.keys.filter { it.startsWith("key_") }.map { it.removePrefix("key_") }.toSet()
        val arr = JSONArray()
        val editor = prefs.edit()
        providers.forEachIndexed { index, p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("baseUrl", p.baseUrl)
                .put("model", p.model).put("priority", index).put("enabled", p.enabled)
                .put("allowFallback", p.allowFallback).put("maxConcurrent", p.maxConcurrent.coerceIn(1, 4))
                .put("fallbackOnly", p.fallbackOnly).put("allowVerification", p.allowVerification).put("streaming", p.streaming)
                .put("imageMaxEdge", p.imageMaxEdge).put("progressTimeoutSeconds", p.progressTimeoutSeconds))
            editor.putString("key_${p.id}", encrypt(p.apiKey))
        }
        editor.putString("list", arr.toString())
        (old - providers.map { it.id }.toSet()).forEach { editor.remove("key_$it") }
        if (!editor.commit()) throw SettingsStorageException("连接配置未能写入存储，请释放空间后重试")
        changes.value += 1
    }
    val changes = MutableStateFlow(0L)
}

class SettingsStorageException(message: String) : Exception(message)

object TaskStore {
    lateinit var db: TaskDatabase
        private set
    fun init(context: Context) { if (!::db.isInitialized) db = TaskDatabase(context.applicationContext) }
}

class PhotoAnswerApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        TaskStore.init(this)
        AppRuntime.scope.launch { TaskRepository.initialize(this@PhotoAnswerApplication) }
        if (com.nic.photoanswer.BuildConfig.DEBUG) android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
    }
}
