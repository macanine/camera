package com.photoanswer.app

/** Translate the common model delimiters to Markwon's $$ inline / block notation.
 * Code fences, inline code and escaped dollar signs remain literal.
 */
object MathMarkdown {
    private val tokens = Regex(
        "```[\\s\\S]*?```|~~~[\\s\\S]*?~~~|`[^`\\n]*`|\\\\\\$|" +
            "\\\\\\[[\\s\\S]*?\\\\\\]|\\\\\\([\\s\\S]*?\\\\\\)|" +
            "\\$\\$[\\s\\S]*?\\$\\$|(?<![\\\\$])\\$(?![\\s$])[^$\\n]*?[^\\s\\\\$]\\$(?!\\$)|(?<![\\\\$])\\$[^\\s$]\\$(?!\\$)"
    )

    fun normalize(text: String): String = tokens.replace(text) { match ->
        val token = match.value
        when {
            token.startsWith("```") -> token
            token.startsWith("~~~") || token == "\\$" -> token
            token.startsWith("`") -> token
            token.startsWith("\\[") -> block(token.substring(2, token.length - 2))
            token.startsWith("\\(") -> "\$\$${token.substring(2, token.length - 2).trim()}\$\$"
            token.startsWith("\$\$") -> block(token.substring(2, token.length - 2))
            else -> "\$\$${token.substring(1, token.length - 1)}\$\$"
        }
    }

    private fun block(latex: String): String = "\n\n\$\$\n${latex.trim()}\n\$\$\n\n"
}
