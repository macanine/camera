package com.photoanswer.app

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class FailureKind { NETWORK, NO_PROGRESS, HTTP, EMPTY, REASONING_ONLY, TRUNCATED, FILTERED, PROTOCOL, INPUT, INVALID_ANSWER, RESPONSE_TOO_LARGE }

data class RequestDiagnostics(
    val startedNanos: Long = System.nanoTime(),
    var httpStatus: Int = 0,
    var requestId: String = "",
    var responseModel: String = "",
    var firstEventMs: Long? = null,
    var firstTextMs: Long? = null,
    var durationMs: Long = 0,
    var finishReason: String = "",
    var reasoningChars: Long = 0,
    var usage: String = "",
    var errorKind: String = ""
) {
    fun elapsedMs() = (System.nanoTime() - startedNanos) / 1_000_000
    fun json(): String = JSONObject().put("httpStatus", httpStatus).put("requestId", requestId)
        .put("responseModel", responseModel).put("firstEventMs", firstEventMs)
        .put("firstTextMs", firstTextMs).put("durationMs", durationMs)
        .put("finishReason", finishReason).put("reasoningChars", reasoningChars)
        .put("usage", usage).put("errorKind", errorKind).toString()
}

data class ModelAnswer(val text: String, val provider: String, val model: String,
    val diagnostics: RequestDiagnostics = RequestDiagnostics())
class ModelException(
    val code: Int, message: String,
    val kind: FailureKind = if (code == -1) FailureKind.INPUT else if (code == 0) FailureKind.NETWORK else FailureKind.HTTP,
    val retryAfterMs: Long = 0,
    val draft: String = "",
    val diagnostics: RequestDiagnostics = RequestDiagnostics()
) : Exception(message) {
    val retryable: Boolean get() = kind in setOf(FailureKind.NETWORK, FailureKind.NO_PROGRESS, FailureKind.EMPTY,
        FailureKind.REASONING_ONLY, FailureKind.TRUNCATED, FailureKind.PROTOCOL, FailureKind.INVALID_ANSWER) ||
        code in setOf(408, 429) || code >= 500
}

internal fun redact(value: String, key: String): String =
    (if (key.isBlank()) value else value.replace(key, "[已隐藏密钥]"))
        .replace(Regex("(?i)Bearer\\s+[^\\s\"']+"), "Bearer [已隐藏密钥]").take(500)

/** Both solving and connection tests use this transport. No output-token cap is sent. */
class ModelTransport(private val http: OkHttpClient) {
    suspend fun request(
        provider: ProviderConfig, image: String, prompt: String,
        onProgress: (String) -> Unit = {}, onPartial: (String) -> Unit = {}
    ): ModelAnswer = suspendCancellableCoroutine { continuation ->
        val diagnostics = RequestDiagnostics()
        val base = provider.baseUrl.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:image/jpeg;base64,$image").put("detail", "high")))
        val body = JSONObject().put("model", provider.model).put("stream", provider.streaming)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val request = Request.Builder().url(endpoint).post(body.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Accept", if (provider.streaming) "text/event-stream, application/json" else "application/json").build()
        val call = http.newCall(request)
        val lastProgress = AtomicLong(System.nanoTime())
        val timedOut = AtomicBoolean(false)
        val watchdog = timer.scheduleWithFixedDelay({
            if (System.nanoTime() - lastProgress.get() > provider.progressTimeoutSeconds * 1_000_000_000L) {
                timedOut.set(true)
                call.cancel()
            }
        }, 1, 1, TimeUnit.SECONDS)
        continuation.invokeOnCancellation { watchdog.cancel(false); call.cancel() }
        fun failed(error: ModelException) {
            watchdog.cancel(false)
            val actual = if (timedOut.get()) ModelException(0, "模型长时间没有返回有效进展，请重试或调整无进展超时",
                FailureKind.NO_PROGRESS, draft = error.draft, diagnostics = diagnostics) else error
            diagnostics.durationMs = diagnostics.elapsedMs()
            diagnostics.errorKind = actual.kind.name
            if (continuation.isActive) continuation.resumeWithException(actual)
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = failed(ModelException(0,
                "网络连接失败或长时间没有有效进展：${redact(e.localizedMessage.orEmpty(), provider.apiKey)}",
                diagnostics = diagnostics))
            override fun onResponse(call: Call, response: Response) {
                try {
                    val answer = response.use {
                        diagnostics.httpStatus = it.code
                        diagnostics.requestId = redact(it.header("x-request-id") ?: it.header("request-id").orEmpty(), provider.apiKey)
                        lastProgress.set(System.nanoTime())
                        if (!it.isSuccessful) {
                            val source = it.body?.source()
                            val detail = if (source == null) "" else try { source.readUtf8LineStrict(8192) }
                                catch (_: java.io.EOFException) { source.buffer.readUtf8(minOf(source.buffer.size, 8192)) }
                            throw ModelException(it.code, "HTTP ${it.code}: ${redact(detail, provider.apiKey)}",
                                retryAfterMs = retryAfter(it.header("Retry-After")), diagnostics = diagnostics)
                        }
                        val source = it.body?.source() ?: throw ModelException(0, "服务商返回空响应", FailureKind.EMPTY, diagnostics = diagnostics)
                        // Some gateways ignore stream=true or mislabel Content-Type.
                        val peek = source.peek()
                        var first: Int? = null
                        var prefixBytes = 0
                        while (peek.request(1)) {
                            if (++prefixBytes > 4096) throw ModelException(0, "响应格式无效，请重试", FailureKind.RESPONSE_TOO_LARGE, diagnostics = diagnostics)
                            val byte = peek.readByte().toInt().and(255)
                            if (byte !in listOf(9, 10, 13, 32)) { first = byte; break }
                        }
                        ModelStream.read(source, provider, onPartial, diagnostics,
                            isJson = first == '{'.code,
                            onProgress = { stage -> lastProgress.set(System.nanoTime()); onProgress(stage) })
                    }
                    watchdog.cancel(false)
                    diagnostics.durationMs = diagnostics.elapsedMs()
                    if (continuation.isActive) continuation.resume(answer)
                } catch (e: Exception) {
                    failed(if (e is ModelException) e else ModelException(0,
                        "响应读取失败：${redact(e.localizedMessage.orEmpty(), provider.apiKey)}", diagnostics = diagnostics))
                }
            }
        })
    }

    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "model-progress").apply { isDaemon = true } }
        internal fun retryAfter(value: String?, now: Long = System.currentTimeMillis()): Long {
            if (value == null) return 0
            value.trim().toLongOrNull()?.let { return it.coerceIn(0, 86_400) * 1000 }
            return runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now)
                .coerceIn(0, 86_400_000) }.getOrDefault(0)
        }
    }
}

internal object ModelStream {
    internal const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
    internal const val MAX_TEXT_CHARS = 128 * 1024
    fun read(source: BufferedSource, provider: ProviderConfig, onPartial: (String) -> Unit = {},
        diagnostics: RequestDiagnostics = RequestDiagnostics(), isJson: Boolean = false,
        onProgress: (String) -> Unit = {}): ModelAnswer {
        val text = StringBuilder()
        var done = false
        var lastPartialAt = Long.MIN_VALUE
        var published = ""
        fun fail(message: String, kind: FailureKind): Nothing = throw ModelException(0, message, kind,
            draft = text.toString(), diagnostics = diagnostics)
        fun consume(data: String) {
            if (data == "[DONE]") { done = true; return }
            val json = try { JSONObject(data) } catch (_: Exception) { fail("模型响应不是有效 JSON", FailureKind.PROTOCOL) }
            if (!json.isNull("error")) {
                val error = json.optJSONObject("error")
                val status = error?.optInt("status", 0) ?: 0
                val numericCode = error?.optInt("code", 0) ?: 0
                val code = when {
                    status in 400..599 -> status
                    numericCode in 400..599 -> numericCode
                    error?.optString("code") == "rate_limit_exceeded" || error?.optString("type") == "rate_limit_error" -> 429
                    else -> 0
                }
                throw ModelException(code, "模型流返回错误：${redact(error?.optString("message") ?: json.optString("error"), provider.apiKey)}",
                    if (code == 0) FailureKind.PROTOCOL else FailureKind.HTTP, draft = text.toString(), diagnostics = diagnostics)
            }
            if (diagnostics.firstEventMs == null) diagnostics.firstEventMs = diagnostics.elapsedMs()
            json.optString("model").takeIf { it.isNotBlank() && it != "null" }?.let { diagnostics.responseModel = it }
            json.optJSONObject("usage")?.let { diagnostics.usage = it.toString() }
            val choices = json.optJSONArray("choices") ?: return
            val choice = (0 until choices.length()).mapNotNull { choices.optJSONObject(it) }
                .firstOrNull { it.optInt("index", 0) == 0 } ?: return
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: JSONObject()
            if (readContent(delta.opt("refusal")).isNotBlank()) fail("模型拒绝处理该内容", FailureKind.FILTERED)
            val reasoning = readContent(delta.opt("reasoning_content")) + readContent(delta.opt("reasoning"))
            val content = readContent(delta.opt("content"))
            if (reasoning.isNotEmpty()) {
                diagnostics.reasoningChars += reasoning.length
                onProgress("模型正在推理")
            }
            if (content.isNotEmpty()) {
                if (content.length > MAX_TEXT_CHARS - text.length) fail("返回内容过长，请缩小照片范围后重试", FailureKind.RESPONSE_TOO_LARGE)
                if (diagnostics.firstTextMs == null) diagnostics.firstTextMs = diagnostics.elapsedMs()
                text.append(content)
                onProgress("正在接收内容")
                val now = System.nanoTime()
                if (lastPartialAt == Long.MIN_VALUE || now - lastPartialAt >= 160_000_000) {
                    lastPartialAt = now
                    published = text.toString()
                    onPartial(published)
                }
            }
            val finish = choice.optString("finish_reason").takeUnless { it == "null" }.orEmpty()
            if (finish.isNotBlank()) diagnostics.finishReason = finish
            when (finish) {
                "length" -> fail("服务商截断了输出（客户端未设置 token 上限），请重试或切换模型", FailureKind.TRUNCATED)
                "content_filter" -> fail("模型未能处理此内容", FailureKind.FILTERED)
                "stop" -> done = true
                "tool_calls", "function_call" -> fail("模型返回了工具调用，没有返回可用内容", FailureKind.PROTOCOL)
            }
        }
        try {
            if (isJson) {
                val buffer = okio.Buffer()
                while (source.read(buffer, 8192) != -1L) {
                    if (buffer.size > MAX_RESPONSE_BYTES) fail("返回数据过大，请稍后重试", FailureKind.RESPONSE_TOO_LARGE)
                }
                consume(buffer.readUtf8())
                done = true
            } else {
                var eventData = StringBuilder()
                var totalBytes = 0L
                fun flush() {
                    if (eventData.isNotEmpty()) consume(eventData.toString().trimEnd('\n'))
                    eventData = StringBuilder()
                }
                while (!done && !source.exhausted()) {
                    val line = try { source.readUtf8LineStrict(1_048_576) } catch (_: java.io.EOFException) {
                        if (source.buffer.size > 1_048_576) fail("模型流事件过长", FailureKind.PROTOCOL)
                        source.readUtf8()
                    }
                    totalBytes += line.toByteArray(Charsets.UTF_8).size + 2L
                    if (totalBytes > MAX_RESPONSE_BYTES) fail("返回数据过大，请稍后重试", FailureKind.RESPONSE_TOO_LARGE)
                    if (line.isEmpty()) flush()
                    else if (line.startsWith("data:")) {
                        eventData.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                        if (eventData.length > 1_048_576) fail("模型流事件过长", FailureKind.PROTOCOL)
                    }
                }
                if (!done) flush()
            }
        } catch (e: IOException) {
            fail("响应读取失败：${redact(e.localizedMessage.orEmpty(), provider.apiKey)}", FailureKind.NETWORK)
        }
        if (!done) fail("模型流提前结束", FailureKind.PROTOCOL)
        val answer = text.toString().trim()
        if (answer.isBlank()) {
            if (diagnostics.reasoningChars > 0) fail("模型完成了推理但没有返回最终内容", FailureKind.REASONING_ONLY)
            fail("模型没有返回内容", FailureKind.EMPTY)
        }
        if (published != answer) onPartial(answer)
        diagnostics.durationMs = diagnostics.elapsedMs()
        return ModelAnswer(answer, provider.name, diagnostics.responseModel.ifBlank { provider.model }, diagnostics)
    }

    private fun readContent(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                val part = value.optJSONObject(i) ?: continue
                if (part.optString("type") in listOf("text", "output_text", "")) append(part.optString("text").takeUnless { it == "null" }.orEmpty())
            }
        }
        else -> ""
    }
}
