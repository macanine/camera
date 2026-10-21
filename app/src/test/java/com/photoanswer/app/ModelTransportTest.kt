package com.photoanswer.app

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelTransportTest {
    private val provider = ProviderConfig("a", "test provider", "https://example.com/v1", "requested-model", "test-secret")
    private fun chunk(content: String, finish: String? = null) = JSONObject()
        .put("model", "returned-model")
        .put("choices", org.json.JSONArray().put(JSONObject()
            .put("delta", JSONObject().put("content", content))
            .put("finish_reason", finish ?: JSONObject.NULL)))
        .toString()
    private fun stream(vararg events: String) = events.joinToString("\n\n", postfix = "\n\n") { "data: $it" }

    @Test fun `stream preserves math model id and final event without newline`() {
        val buffer = Buffer().writeUtf8("data: ${chunk("解：\\(x=2\\)")}\n\ndata: ${chunk("", "stop")}")
        val answer = ModelStream.read(buffer, provider)
        assertEquals("解：\\(x=2\\)", answer.text)
        assertEquals("returned-model", answer.model)
    }

    @Test fun `incomplete empty malformed truncated and error streams fail`() {
        val invalid = listOf(
            stream(chunk("partial")), stream("[DONE]"), stream("oops"),
            stream(chunk("partial", "length"), "[DONE]"),
            stream(chunk("partial"), "{\"error\":{\"message\":\"failed\"}}"),
            "data: " + "a".repeat(1_048_577)
        )
        invalid.forEach { body ->
            assertThrows(ModelException::class.java) { ModelStream.read(Buffer().writeUtf8(body), provider) }
        }
    }

    @Test fun `real transport sends image stream and handles SSE comments`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(": ping\n\n" + stream(chunk("答案 B"), chunk("", "stop"), "[DONE]")))
            val p = provider.copy(baseUrl = server.url("/v1/").toString())
            val answer = ModelTransport(OkHttpClient()).request(p, "dGVzdA==", "read the image")
            assertEquals("答案 B", answer.text)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer test-secret", request.getHeader("Authorization"))
            val json = JSONObject(request.body.readUtf8())
            assertTrue(json.getBoolean("stream"))
            assertFalse(json.has("max_tokens"))
            assertFalse(json.has("max_completion_tokens"))
            assertFalse(json.has("max_output_tokens"))
            assertEquals("requested-model", json.getString("model"))
            assertEquals("data:image/jpeg;base64,dGVzdA==", json.getJSONArray("messages").getJSONObject(0)
                .getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"))
        }
    }

    @Test fun `direct endpoint is not duplicated and HTTP error hides key`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("invalid test-secret"))
            val transport = ModelTransport(OkHttpClient())
            try {
                transport.request(provider.copy(baseUrl = server.url("/v1/chat/completions").toString()), "image", "test")
                fail("Expected an HTTP error")
            } catch (e: ModelException) {
                assertEquals(401, e.code)
                assertFalse(e.message!!.contains("test-secret"))
            }
            assertEquals("/v1/chat/completions", server.takeRequest().path)
        }
    }

    @Test fun `cancelling request cancels network promptly`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
            val job = launch(Dispatchers.Default) {
                ModelTransport(client).request(provider.copy(baseUrl = server.url("/v1").toString()), "image", "test")
            }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
            withTimeout(2_000) { job.cancelAndJoin() }
            withTimeout(2_000) {
                while (client.dispatcher.runningCallsCount() > 0) delay(10)
            }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun `terminal stop returns without waiting for another byte`() {
        val bytes = Buffer().writeUtf8(stream(chunk("答案 B", "stop")))
        val source = object : okio.Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (bytes.size > 0) return bytes.read(sink, byteCount)
                throw java.net.SocketTimeoutException("should not read past stop")
            }
            override fun timeout() = okio.Timeout.NONE
            override fun close() = Unit
        }
        assertEquals("答案 B", ModelStream.read(source.buffer(), provider).text)
    }

    @Test fun `ordinary JSON returned to a streaming request is parsed with diagnostics`() = runBlocking {
        MockWebServer().use { server ->
            val body = JSONObject().put("model", "actual").put("usage", JSONObject().put("completion_tokens", 5000))
                .put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", "1. B"))
                    .put("finish_reason", "stop")))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setHeader("x-request-id", "req-123").setBody(body.toString()))
            val result = ModelTransport(OkHttpClient()).request(provider.copy(baseUrl = server.url("/v1").toString()), "image", "solve")
            assertEquals("1. B", result.text)
            assertEquals("actual", result.model)
            assertEquals("req-123", result.diagnostics.requestId)
            assertEquals(200, result.diagnostics.httpStatus)
            assertTrue(result.diagnostics.usage.contains("5000"))
        }
    }

    @Test fun `reasoning is progress but never substituted for the final answer`() {
        val reasoning = """{"choices":[{"delta":{"content":null,"reasoning_content":"working"},"finish_reason":null}]}"""
        val stages = mutableListOf<String>()
        val answer = ModelStream.read(Buffer().writeUtf8(stream(reasoning, chunk("B", "stop"))), provider,
            onProgress = { stages += it })
        assertEquals("B", answer.text)
        assertEquals(7, answer.diagnostics.reasoningChars.toInt())
        assertTrue(stages.contains("模型正在推理"))
        val error = assertThrows(ModelException::class.java) {
            ModelStream.read(Buffer().writeUtf8(stream(reasoning, chunk("", "stop"))), provider)
        }
        assertEquals(FailureKind.REASONING_ONLY, error.kind)
    }

    @Test fun `truncation and disconnect retain draft and failure type`() {
        val truncated = assertThrows(ModelException::class.java) {
            ModelStream.read(Buffer().writeUtf8(stream(chunk("draft", "length"))), provider)
        }
        assertEquals("draft", truncated.draft)
        assertEquals(FailureKind.TRUNCATED, truncated.kind)
        val broken = assertThrows(ModelException::class.java) {
            ModelStream.read(Buffer().writeUtf8(stream(chunk("draft"))), provider)
        }
        assertEquals("draft", broken.draft)
        assertEquals(FailureKind.PROTOCOL, broken.kind)
    }

    @Test fun `non streaming mode omits token limits and uses the same image contract`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"B"},"finish_reason":"stop"}]}"""))
            ModelTransport(OkHttpClient()).request(provider.copy(baseUrl = server.url("/v1").toString(), streaming = false), "image", "solve")
            val json = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(json.getBoolean("stream"))
            assertFalse(json.has("max_tokens"))
            assertFalse(json.has("max_completion_tokens"))
        }
    }

    @Test fun `Retry After supports seconds and HTTP dates`() {
        assertEquals(7_000L, ModelTransport.retryAfter("7", 0))
        assertEquals(7_000L, ModelTransport.retryAfter("Thu, 01 Jan 1970 00:00:07 GMT", 0))
        assertEquals(0L, ModelTransport.retryAfter("bad", 0))
    }

    @Test fun `heartbeats cannot keep a request alive without model progress`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(": ping\n\n".repeat(100)).throttleBody(8, 100, TimeUnit.MILLISECONDS))
            val error = try {
                withTimeout(5000) {
                    ModelTransport(OkHttpClient()).request(provider.copy(baseUrl = server.url("/v1").toString(), progressTimeoutSeconds = 1), "image", "test")
                }
                error("Expected no-progress timeout")
            } catch (e: ModelException) { e }
            assertEquals(FailureKind.NO_PROGRESS, error.kind)
            assertEquals("NO_PROGRESS", error.diagnostics.errorKind)
        }
    }
    @Test fun `many individually valid events cannot exceed aggregate content or transport limits`() {
        val error = assertThrows(ModelException::class.java) {
            val piece = chunk("x".repeat(32 * 1024))
            ModelStream.read(Buffer().writeUtf8(stream(*Array(5) { piece })), provider)
        }
        assertEquals(FailureKind.RESPONSE_TOO_LARGE, error.kind)
        assertTrue(error.draft.length <= ModelStream.MAX_TEXT_CHARS)
        assertFalse(error.retryable)
        val heartbeat = ": " + "x".repeat(64 * 1024) + "\n\n"
        val wire = assertThrows(ModelException::class.java) {
            ModelStream.read(Buffer().writeUtf8(heartbeat.repeat(130)), provider)
        }
        assertEquals(FailureKind.RESPONSE_TOO_LARGE, wire.kind)
    }

}
