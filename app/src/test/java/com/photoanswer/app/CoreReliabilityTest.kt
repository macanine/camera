package com.photoanswer.app

import org.junit.Assert.*
import org.junit.Test

class CoreReliabilityTest {
    private val a = ProviderConfig("a", "a", "https://one.example/v1", "m1", "credential")
    private val b = a.copy(id = "b", model = "m2")
    private val c = a.copy(id = "c", baseUrl = "https://two.example/v1")

    @Test fun `reviews and solves share model and credential capacity`() {
        val gate = RequestCoordinator { 1000L }
        assertTrue(gate.acquire("solve", a, listOf(a, b, c)))
        assertFalse(gate.acquire("review-same-model", a, listOf(a, b, c)))
        assertFalse(gate.acquire("review-other-model-same-key", b, listOf(a, b, c)))
        assertTrue(gate.acquire("review-other-provider", c, listOf(a, b, c)))
        gate.release("solve")
        gate.release("solve")
        assertTrue(gate.acquire("review-same-model", a, listOf(a, b, c)))
        assertEquals(2, gate.size)
    }

    @Test fun `cooldown waits until Retry After and authentication blocks shared credentials`() {
        var now = 1000L
        val gate = RequestCoordinator { now }
        gate.failure(a, ModelException(429, "busy", retryAfterMs = 10_000))
        assertFalse(gate.acquire("x", b, listOf(a, b)))
        now += 10_000
        assertTrue(gate.acquire("x", b, listOf(a, b)))
        gate.release("x")
        gate.failure(a, ModelException(401, "invalid"))
        assertFalse(gate.usable(a))
        assertFalse(gate.usable(b))
        assertTrue(gate.usable(a.copy(apiKey = "new credential")))
    }

    @Test fun `single provider can retry once and alternatives are preferred`() {
        assertEquals(listOf(a), RetryPolicy.candidates(listOf(a), mapOf("a" to 1), setOf("a")))
        assertTrue(RetryPolicy.candidates(listOf(a), mapOf("a" to 2), setOf("a")).isEmpty())
        assertEquals(listOf(c, a), RetryPolicy.candidates(listOf(a, c), mapOf("a" to 1), setOf("a")))
        assertTrue(RetryPolicy.candidates(listOf(a), mapOf("a" to 1), emptySet()).isEmpty())
    }

    @Test fun `structured answers preserve math and distinguish uncertainty from success`() {
        val result = AnswerValidation.parse("""{"questions":[{"number":"1","answer":"B","status":"answered"},{"number":"2","answer":"无法确定","status":"uncertain"}]}""")
        assertTrue(result.uncertain)
        assertEquals(listOf("1", "2"), result.questions.map { it.number })
        assertEquals(result, AnswerValidation.parse(result.json))
        assertThrows(ModelException::class.java) {
            AnswerValidation.parse("""{"questions":[{"number":"1","answer":"B","status":"answered"},{"number":"1","answer":"C","status":"answered"}]}""")
        }
        assertThrows(ModelException::class.java) { AnswerValidation.parse("""{"questions":[]}""") }
        assertTrue(AnswerValidation.parse("无法读取图片").uncertain)
        assertEquals("1. B", AnswerValidation.parse("1. B").text)
    }

    @Test fun `correction needs independent confirmation and cannot silently omit questions`() {
        val original = AnswerValidation.parse("""{"questions":[{"number":"1","answer":"B","status":"answered"},{"number":"2","answer":"D","status":"answered"}]}""")
        val state = VerificationState(original)
        state.accept(ReviewValidation.parse("""{"verdict":"corrected","questions":[{"number":"1","answer":"C","status":"answered"},{"number":"2","answer":"D","status":"answered"}]}"""), ModelAnswer("", "reviewer1", "m"))
        assertFalse(state.confirmed)
        assertEquals("C", state.candidate.questions[0].answer)
        state.accept(ReviewVerdict.Verified, ModelAnswer("", "reviewer2", "m"))
        assertTrue(state.confirmed)
        val incomplete = VerificationState(original)
        incomplete.accept(ReviewValidation.parse("""{"verdict":"corrected","questions":[{"number":"1","answer":"C","status":"answered"}]}"""), ModelAnswer("", "reviewer1", "m"))
        assertEquals(original, incomplete.candidate)
        assertTrue(incomplete.sawUncertainty)
        assertEquals(ReviewVerdict.Uncertain, ReviewValidation.parse("Probably VERIFIED, unsure"))
    }

    @Test fun `choice format and declared question coverage are checked`() {
        val result = AnswerValidation.parse("""{"total_questions":1,"questions":[{"number":"1","answer":"c, a a","type":"choice","status":"answered"}]}""")
        assertEquals("AC", result.questions.single().answer)
        assertThrows(ModelException::class.java) {
            AnswerValidation.parse("""{"total_questions":2,"questions":[{"number":"1","answer":"B","type":"choice","status":"answered"}]}""")
        }
        assertThrows(ModelException::class.java) {
            AnswerValidation.parse("""{"questions":[{"number":"1","answer":"B because...","type":"choice","status":"answered"}]}""")
        }
    }
}
