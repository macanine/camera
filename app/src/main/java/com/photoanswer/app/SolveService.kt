package com.photoanswer.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal interface SolveEngine {
    suspend fun solve(task: SolveTask, provider: ProviderConfig, progress: (String) -> Unit, partial: (String) -> Unit): ModelAnswer
    suspend fun verify(task: SolveTask, answer: ModelAnswer, provider: ProviderConfig): ModelAnswer
}

class SolveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val coordinator = RequestCoordinator()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val runs = ConcurrentHashMap<String, Int>()
    @Volatile private var latestStartId = 0
    private var lastNotification = ""
    private val dispatchMutex = Mutex()
    private var targetParallelism = 1
    private var successes = 0
    private var schedulerJob: Job? = null
    @Volatile private var providerRevision = -1L
    private var cachedProviders = emptyList<ProviderConfig>()
    private data class ReviewPlan(val task: SolveTask, val producer: ProviderConfig, val providers: List<ProviderConfig>, val queuedAt: Long = monotonicMs())
    private val reviews = ConcurrentHashMap<String, ReviewPlan>()
    internal var providerSource: () -> List<ProviderConfig> = { SecurePrefs.load(this) }
        set(value) { field = value; providerRevision = -1L; wakeups.trySend(Unit) }
    internal var engine: SolveEngine = object : SolveEngine {
        override suspend fun solve(task: SolveTask, provider: ProviderConfig, progress: (String) -> Unit, partial: (String) -> Unit) =
            ModelClient.solve(task, provider, progress, partial)
        override suspend fun verify(task: SolveTask, answer: ModelAnswer, provider: ProviderConfig) = ModelClient.verify(task, answer, provider)
    }

    override fun onCreate() {
        super.onCreate()
        TaskStore.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "后台处理", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("等待任务"))
        scope.launch { TaskStore.db.changes.collect { wakeups.trySend(Unit) } }
        scope.launch { SecurePrefs.changes.collect { wakeups.trySend(Unit) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        scope.launch {
            TaskRepository.initialize(this@SolveService)
            dispatchMutex.withLock {
                val id = intent?.getStringExtra(EXTRA_ID).orEmpty()
                when (intent?.action) {
                    ACTION_CANCEL -> cancelTask(id)
                    ACTION_RETRY -> {
                        if (TaskStore.db.get(id)?.status in setOf("FAILED", "INTERRUPTED", "CANCELLED", "NEEDS_REVIEW")) {
                            jobs[id]?.cancel(); reviews.remove(id); TaskStore.db.requeue(id)
                        }
                    }
                }
                if (schedulerJob?.isActive != true) schedulerJob = scope.launch {
                    try { scheduler() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val reference = LocalDiagnostics.record("scheduler", e)
                        TaskStore.db.failQueued(if (e is SettingsStorageException) e.message.orEmpty()
                            else "后台暂时无法运行，诊断编号 $reference")
                        stopSelf()
                    }
                }
                wakeups.trySend(Unit)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun scheduler() {
        while (scope.isActive) {
            var nextWakeMs = 60_000L
            dispatchMutex.withLock {
                val tickStartId = latestStartId
                val revision = SecurePrefs.changes.value
                if (providerRevision != revision) {
                    cachedProviders = ProviderRouting.ordered(providerSource())
                    providerRevision = revision
                }
                val providers = cachedProviders
                val initial = RetryPolicy.candidates(providers, emptyMap(), emptySet())
                for (queued in TaskStore.db.queued()) {
                    val solving = TaskStore.db.activeCount()
                    if (solving >= targetParallelism || coordinator.size >= 4) break
                    if (jobs.containsKey(queued.id)) continue
                    val owner = UUID.randomUUID().toString()
                    val first = initial.firstOrNull {
                        coordinator.acquire(owner, it, providers)
                    } ?: continue
                    val task = TaskStore.db.beginRun(queued.id)
                    if (task == null) { coordinator.release(owner); continue }
                    launchManaged(task, owner) { solveTask(task, providers, first, owner) }
                }
                // Reviews have lower priority and do not hold up publication of a primary answer.
                for ((id, plan) in reviews.entries.toList()) {
                    if (coordinator.size >= 4) break
                    val task = TaskStore.db.get(id)
                    if (task?.generation != plan.task.generation || task.verification != "PENDING") { reviews.remove(id, plan); continue }
                    if (jobs.containsKey(id)) continue
                    val verifiers = ProviderRouting.verifiers(plan.providers, plan.producer.id).filter(coordinator::usable)
                    if (verifiers.isEmpty() || monotonicMs() - plan.queuedAt > VERIFICATION_TIMEOUT_MS) {
                        TaskStore.db.update(id, task.status, verification = "UNAVAILABLE", expectedGeneration = task.generation)
                        reviews.remove(id, plan)
                        continue
                    }
                    val owner = UUID.randomUUID().toString()
                    val first = verifiers.firstOrNull { coordinator.acquire(owner, it, plan.providers) } ?: continue
                    reviews.remove(id, plan)
                    launchManaged(task, owner) { reviewTask(plan.copy(task = task), verifiers, first, owner) }
                }
                updateNotification()
                // Standby connections cannot start a new task. A usable standby must not
                // hide the fact that every normal connection is permanently blocked.
                if (jobs.isEmpty() && initial.isNotEmpty() && initial.none(coordinator::usable) && TaskStore.db.count("QUEUED") > 0) {
                    TaskStore.db.failQueued("常规连接配置或权限异常，请在设置中检查后重试")
                }
                if (jobs.isEmpty() && reviews.isEmpty() && (TaskStore.db.count("QUEUED") == 0 || initial.isEmpty())) {
                    if (stopSelfResult(tickStartId)) return
                }
                val now = monotonicMs()
                nextWakeMs = providers.filter(coordinator::usable).map { coordinator.readyAt(it) - now }
                    .filter { it > 0 }.minOrNull()?.coerceIn(1, 60_000) ?: 60_000
                reviews.values.minOfOrNull { (VERIFICATION_TIMEOUT_MS - (now - it.queuedAt)).coerceAtLeast(1) }
                    ?.let { nextWakeMs = minOf(nextWakeMs, it) }
            }
            // Database changes, incoming commands and job completion wake scheduling.
            // A timer is only needed for cooldown/verification deadlines.
            withTimeoutOrNull(nextWakeMs) { wakeups.receive() }
        }
    }

    /** Caller owns dispatchMutex. Cleanup only removes the exact job that installed this entry. */
    private fun launchManaged(task: SolveTask, owner: String, block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reference = LocalDiagnostics.record("task", e)
                val live = TaskStore.db.get(task.id)
                if (live?.generation == task.generation) {
                    if (live.status in setOf("RUNNING", "SWITCHING")) TaskStore.db.update(task.id, "FAILED",
                        error = "本地处理失败，诊断编号 $reference", expectedGeneration = task.generation)
                    else TaskStore.db.update(task.id, live.status, verification = "UNAVAILABLE", expectedGeneration = task.generation)
                }
            }
            finally { coordinator.release(owner) }
        }
        jobs[task.id] = job
        runs[task.id] = task.generation
        job.invokeOnCompletion {
            coordinator.release(owner)
            if (jobs.remove(task.id, job) && !reviews.containsKey(task.id)) runs.remove(task.id, task.generation)
            wakeups.trySend(Unit)
        }
        job.start()
    }

    private suspend fun awaitProvider(task: SolveTask, owner: String, candidates: () -> List<ProviderConfig>, providers: List<ProviderConfig>): ProviderConfig? {
        while (true) {
            currentCoroutineContext().ensureActive()
            val available = candidates().filter(coordinator::usable)
            if (available.isEmpty()) return null
            val selected = available.firstOrNull { coordinator.acquire(owner, it, providers) }
            if (selected != null) return selected
            val live = TaskStore.db.get(task.id)
            if (live?.generation != task.generation) throw CancellationException("Task generation changed")
            TaskStore.db.update(task.id, live.status, progress = "等待模型冷却或空闲", expectedGeneration = task.generation)
            delay(250)
        }
    }

    private suspend fun solveTask(task: SolveTask, providers: List<ProviderConfig>, first: ProviderConfig, owner: String) {
        val counts = mutableMapOf<String, Int>()
        val retryable = mutableSetOf<String>()
        var current: ProviderConfig? = first
        var lastError = "没有可用模型"
        try {
            withTimeout(TOTAL_TASK_TIMEOUT_MS) {
                repeat(ProviderRouting.MAX_ATTEMPTS) { index ->
                    val provider = current ?: return@withTimeout
                    counts[provider.id] = (counts[provider.id] ?: 0) + 1
                    val status = if (index == 0) "RUNNING" else "SWITCHING"
                    val attempt = UUID.randomUUID().toString()
                    TaskStore.db.update(task.id, status, draft = "", error = "", progress = "正在连接模型",
                        provider = provider.name, model = provider.model, attempts = task.attempts + index + 1,
                        expectedGeneration = task.generation, activeOnly = true)
                    TaskStore.db.startAttempt(attempt, task, "SOLVE", provider)
                    var draft = ""
                    var lastWrite = 0L
                    var lastProgress = ""
                    try {
                        val result = engine.solve(task, provider, { progress ->
                            if (progress != lastProgress) {
                                lastProgress = progress
                                TaskStore.db.update(task.id, status, progress = progress, expectedGeneration = task.generation, activeOnly = true)
                            }
                        }, { partial ->
                            draft = partial
                            val now = System.nanoTime()
                            if (now - lastWrite >= 200_000_000) {
                                lastWrite = now
                                val preview = answerPreview(partial)
                                TaskStore.db.update(task.id, status, draft = preview, expectedGeneration = task.generation, activeOnly = true)
                            }
                        })
                        val validated = try { AnswerValidation.parse(result.text) }
                            catch (e: ModelException) { throw ModelException(0, e.message.orEmpty(), e.kind, draft = result.text, diagnostics = result.diagnostics) }
                        TaskStore.db.finishAttempt(attempt, "SUCCEEDED", result.diagnostics, draft = result.text)
                        coordinator.success(provider)
                        coordinator.release(owner)
                        dispatchMutex.withLock {
                            ensureActive()
                            val needsReview = validated.uncertain
                            val verify = ProviderRouting.verifiers(providers, provider.id).isNotEmpty()
                            val saved = TaskStore.db.update(task.id, if (needsReview) "NEEDS_REVIEW" else "SUCCEEDED",
                                answer = validated.text, solutionJson = validated.json, draft = "", progress = "",
                                error = if (needsReview) "部分内容无法确定，请核对原图" else "",
                                verification = if (verify) "PENDING" else "NONE", expectedGeneration = task.generation, activeOnly = true)
                            if (saved && verify) reviews[task.id] = ReviewPlan(TaskStore.db.get(task.id)!!, provider, providers)
                            successes++
                            if (successes >= 3) { targetParallelism = (targetParallelism + 1).coerceAtMost(4); successes = 0 }
                        }
                        return@withTimeout
                    } catch (e: CancellationException) {
                        TaskStore.db.finishAttempt(attempt, "CANCELLED", RequestDiagnostics(), draft = draft)
                        throw e
                    } catch (e: ModelException) {
                        e.diagnostics.errorKind = e.kind.name
                        e.diagnostics.durationMs = e.diagnostics.elapsedMs()
                        lastError = "${provider.name}: ${redact(e.message.orEmpty(), provider.apiKey)}"
                        TaskStore.db.finishAttempt(attempt, "FAILED", e.diagnostics, lastError, e.draft.ifBlank { draft })
                        coordinator.failure(provider, e)
                        if (e.retryable) retryable += provider.id else retryable -= provider.id
                        if (e.code == 429) dispatchMutex.withLock { targetParallelism = (targetParallelism / 2).coerceAtLeast(1); successes = 0 }
                        TaskStore.db.update(task.id, status, error = lastError,
                            draft = answerPreview(e.draft.ifBlank { draft }), expectedGeneration = task.generation, activeOnly = true)
                        if (e.kind == FailureKind.INPUT) return@withTimeout
                    } catch (e: Exception) {
                        lastError = "本地处理失败，诊断编号 ${LocalDiagnostics.record("solve", e)}"
                        TaskStore.db.finishAttempt(attempt, "FAILED", RequestDiagnostics(errorKind = "INTERNAL"), lastError, draft)
                        return@withTimeout
                    } finally { coordinator.release(owner) }
                    current = if (index + 1 < ProviderRouting.MAX_ATTEMPTS) awaitProvider(task, owner,
                        { RetryPolicy.candidates(providers, counts, retryable) }, providers) else null
                }
            }
        } catch (_: TimeoutCancellationException) { lastError = "本轮处理超过 25 分钟，请重试" }
        catch (e: CancellationException) { throw e }
        TaskStore.db.update(task.id, "FAILED", error = lastError, progress = "", expectedGeneration = task.generation, activeOnly = true)
    }

    private suspend fun reviewTask(plan: ReviewPlan, verifiers: List<ProviderConfig>, first: ProviderConfig, owner: String) {
        val task = plan.task
        val state = VerificationState(AnswerValidation.parse(task.solutionJson.ifBlank { task.answer }))
        var verifier: ProviderConfig? = first
        var finished = 0
        val used = mutableSetOf<String>()
        TaskStore.db.update(task.id, task.status, verification = "RUNNING", expectedGeneration = task.generation)
        try {
            withTimeout(VERIFICATION_TIMEOUT_MS) {
                for (index in 0 until minOf(2, verifiers.size)) {
                    val provider = verifier ?: break
                    used += provider.id
                    val attempt = UUID.randomUUID().toString()
                    TaskStore.db.startAttempt(attempt, task, "VERIFY", provider)
                    try {
                        val candidate = state.candidate
                        val result = withTimeout(60_000) {
                            engine.verify(task, ModelAnswer(candidate.json.ifBlank { candidate.text }, task.provider, task.model), provider)
                        }
                        val verdict = ReviewValidation.parse(result.text)
                        state.accept(verdict, result)
                        finished++
                        coordinator.success(provider)
                        TaskStore.db.finishAttempt(attempt, "SUCCEEDED", result.diagnostics, draft = result.text)
                        if (state.confirmed) break
                    } catch (e: TimeoutCancellationException) {
                        val error = ModelException(0, "校验超时", FailureKind.NETWORK)
                        coordinator.failure(provider, error)
                        TaskStore.db.finishAttempt(attempt, "FAILED", error.diagnostics, error.message.orEmpty())
                        currentCoroutineContext().ensureActive()
                    } catch (e: CancellationException) {
                        TaskStore.db.finishAttempt(attempt, "CANCELLED", RequestDiagnostics())
                        throw e
                    } catch (e: ModelException) {
                        e.diagnostics.errorKind = e.kind.name
                        coordinator.failure(provider, e)
                        TaskStore.db.finishAttempt(attempt, "FAILED", e.diagnostics, redact(e.message.orEmpty(), provider.apiKey), e.draft)
                    } catch (e: Exception) {
                        TaskStore.db.finishAttempt(attempt, "FAILED", RequestDiagnostics(errorKind = "INVALID_REVIEW"), redact(e.message.orEmpty(), provider.apiKey))
                    } finally { coordinator.release(owner) }
                    verifier = if (index + 1 < minOf(2, verifiers.size)) awaitProvider(task, owner,
                        { verifiers.filter { it.id !in used } }, plan.providers) else null
                }
            }
        } catch (_: TimeoutCancellationException) { /* Keep the primary answer and any unconfirmed suggestion. */ }
        catch (e: CancellationException) { throw e }
        finally { coordinator.release(owner) }
        dispatchMutex.withLock {
            currentCoroutineContext().ensureActive()
            val corrected = state.confirmed && state.candidateProvider != null
            val source = state.candidateProvider
            val result = if (corrected) state.candidate else state.original
            TaskStore.db.update(task.id, if (result.uncertain) "NEEDS_REVIEW" else "SUCCEEDED",
                answer = result.text, solutionJson = result.json, error = if (result.uncertain) "部分内容无法确定，请核对原图" else "",
                provider = if (corrected) "${task.provider} / ${source!!.provider}" else task.provider,
                model = if (corrected) "${task.model} × ${source!!.model}" else task.model,
                verification = when { state.confirmed -> "VERIFIED"; state.candidateProvider != null -> "SUGGESTED"; finished > 0 -> "UNCERTAIN"; else -> "UNAVAILABLE" },
                suggestion = if (!state.confirmed && state.candidateProvider != null) state.candidate.text else "",
                progress = "", expectedGeneration = task.generation)
        }
    }

    private fun cancelTask(id: String) {
        jobs[id]?.cancel()
        reviews.remove(id)
        val task = TaskStore.db.get(id) ?: return
        if (task.status in setOf("QUEUED", "RUNNING", "SWITCHING", "VERIFYING")) {
            TaskStore.db.update(id, "CANCELLED", progress = "", error = "", generation = task.generation + 1)
        } else if (task.verification in setOf("PENDING", "RUNNING")) {
            TaskStore.db.update(id, task.status, verification = "CANCELLED", generation = task.generation + 1)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(com.nic.photoanswer.R.drawable.ic_stat_camera)
            .setContentTitle(getString(com.nic.photoanswer.R.string.app_name)).setContentText(text).setContentIntent(open)
            .setOngoing(jobs.isNotEmpty()).setCategory(NotificationCompat.CATEGORY_PROGRESS).build()
    }
    private fun updateNotification() {
        val text = "运行 ${jobs.size} · 等待 ${TaskStore.db.count("QUEUED")}"
        if (text != lastNotification) {
            lastNotification = text
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        }
    }
    override fun onTimeout(startId: Int, foregroundServiceType: Int) { interruptTasks("后台执行时间达到系统限制，请返回应用重试"); stopSelf() }
    private fun interruptTasks(message: String) {
        val owned = runs.toMap()
        scope.cancel()
        AppRuntime.scope.launch { owned.forEach { (id, generation) -> TaskStore.db.interruptRun(id, generation, message) } }
        reviews.clear()
    }
    private fun answerPreview(raw: String): String {
        val structured = raw.trimStart().startsWith("{") || raw.trimStart().startsWith("```json")
        return if (structured) runCatching { AnswerValidation.parse(raw).text }.getOrDefault("") else raw
    }
    override fun onDestroy() { interruptTasks("后台服务已停止，请重试"); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_CANCEL = "com.photoanswer.CANCEL"
        const val ACTION_RETRY = "com.photoanswer.RETRY"
        const val EXTRA_ID = "task_id"
        private const val CHANNEL = "photoanswer_tasks"
        private const val NOTIFICATION_ID = 41
        private const val VERIFICATION_TIMEOUT_MS = 120_000L
        private const val TOTAL_TASK_TIMEOUT_MS = 1_500_000L
        internal fun monotonicMs() = System.nanoTime() / 1_000_000
        fun start(context: android.content.Context) { context.startForegroundService(Intent(context, SolveService::class.java)) }
    }
}
