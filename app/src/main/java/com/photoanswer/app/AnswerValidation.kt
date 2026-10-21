package com.photoanswer.app

import org.json.JSONArray
import org.json.JSONObject

data class QuestionAnswer(val number: String, val answer: String, val uncertain: Boolean, val type: String = "other")
data class ValidatedAnswer(val text: String, val json: String, val uncertain: Boolean, val questions: List<QuestionAnswer>)

internal object AnswerValidation {
    fun parse(raw: String): ValidatedAnswer {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (trimmed.isBlank()) invalid("模型没有返回可用内容")
        if (trimmed.startsWith("{")) {
            val obj = try { JSONObject(trimmed) } catch (_: Exception) { invalid("内容结构不完整") }
            val list = obj.optJSONArray("questions") ?: invalid("内容缺少条目列表")
            if (list.length() == 0) invalid("未返回任何条目")
            if (obj.has("total_questions") && obj.optInt("total_questions", -1) != list.length()) invalid("内容条目数量不一致，可能存在遗漏")
            val seen = mutableSetOf<String>()
            val questions = (0 until list.length()).map { index ->
                val item = list.optJSONObject(index) ?: invalid("条目格式无效")
                val number = item.optString("number").trim().takeUnless { it == "null" }.orEmpty()
                if (number.isBlank() || !seen.add(number)) invalid("条目编号缺失或重复")
                if (item.opt("answer") !is String) invalid("条目内容格式无效")
                var answer = item.optString("answer").trim()
                val status = item.optString("status")
                val type = item.optString("type", "other")
                if (type !in setOf("choice", "other")) invalid("条目类型无效")
                if (status !in setOf("answered", "uncertain")) invalid("条目缺少有效的处理状态")
                if (answer.isBlank() && status == "answered") invalid("条目内容为空")
                val uncertain = status == "uncertain" || uncertainText(answer)
                if (type == "choice" && !uncertain) {
                    val choices = answer.uppercase().replace(Regex("[\\s,，、;/；/]+"), "")
                    if (!choices.matches(Regex("[A-Z]+"))) invalid("选项内容包含无效选项或解释文字")
                    answer = choices.toSet().sorted().joinToString("")
                }
                QuestionAnswer(number, answer.ifBlank { "无法确定" }, uncertain, type)
            }
            val normalized = JSONArray()
            questions.forEach { q -> normalized.put(JSONObject().put("number", q.number)
                .put("answer", q.answer).put("type", q.type).put("status", if (q.uncertain) "uncertain" else "answered")) }
            return ValidatedAnswer(questions.joinToString("\n\n") { "${it.number}. ${it.answer}" },
                JSONObject().put("questions", normalized).toString(), questions.any { it.uncertain }, questions)
        }
        // Preserve answers from providers that do not follow JSON instructions; never infer missing questions.
        val labels = Regex("(?m)^\\s*(\\d+)\\s*[.、．:：)]\\s*").findAll(trimmed).map { it.groupValues[1] }.toList()
        if (labels.distinct().size != labels.size) invalid("内容包含重复编号")
        return ValidatedAnswer(raw.trim(), "", uncertainText(trimmed), emptyList())
    }

    private fun uncertainText(text: String) = Regex("无法确定|无法识别|看不清|不能确定|无法解答|无法读取|无法看|无法查看|无法看到|不支持.*图片|cannot (determine|read|view)|uncertain", RegexOption.IGNORE_CASE).containsMatchIn(text)
    private fun invalid(message: String): Nothing = throw ModelException(0, message, FailureKind.INVALID_ANSWER)
}

internal sealed class ReviewVerdict {
    data object Verified : ReviewVerdict()
    data object Uncertain : ReviewVerdict()
    data class Corrected(val answer: ValidatedAnswer) : ReviewVerdict()
}

internal object ReviewValidation {
    fun parse(raw: String): ReviewVerdict {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (text.equals("VERIFIED", true)) return ReviewVerdict.Verified
        if (text.startsWith("CORRECTED:", true)) return ReviewVerdict.Corrected(AnswerValidation.parse(text.substringAfter(':')))
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return ReviewVerdict.Uncertain
        return when (obj.optString("verdict").lowercase()) {
            "verified" -> ReviewVerdict.Verified
            "corrected" -> ReviewVerdict.Corrected(AnswerValidation.parse(JSONObject().put("questions", obj.optJSONArray("questions")).toString()))
            else -> ReviewVerdict.Uncertain
        }
    }
}

/** A correction only replaces the primary result after another independent review confirms it. */
internal class VerificationState(val original: ValidatedAnswer) {
    var candidate = original
        private set
    var candidateProvider: ModelAnswer? = null
        private set
    var confirmed = false
        private set
    var sawUncertainty = false
        private set
    fun accept(verdict: ReviewVerdict, review: ModelAnswer) {
        when (verdict) {
            ReviewVerdict.Verified -> confirmed = !candidate.uncertain
            ReviewVerdict.Uncertain -> { sawUncertainty = true; confirmed = false }
            is ReviewVerdict.Corrected -> {
                val originalNumbers = original.questions.map { it.number }.toSet()
                val correctedNumbers = verdict.answer.questions.map { it.number }.toSet()
                if (!correctedNumbers.containsAll(originalNumbers)) {
                    sawUncertainty = true
                    confirmed = false
                    return
                }
                candidate = verdict.answer
                candidateProvider = review
                confirmed = false
            }
        }
    }
}
