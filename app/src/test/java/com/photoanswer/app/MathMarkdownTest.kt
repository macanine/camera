package com.photoanswer.app

import org.junit.Assert.*
import org.junit.Test

class MathMarkdownTest {
    @Test fun `normalizes inline latex and dollar delimiters`() {
        assertEquals("解：\$\$x=2\$\$，\$\$y^2\$\$ 与 \$\$z\$\$", MathMarkdown.normalize("解：\\(x=2\\)，\$y^2\$ 与 \$z\$"))
    }

    @Test fun `normalizes display math including multiline aligned environment`() {
        val formula = "\\begin{aligned}x&=1\\\\y&=2\\end{aligned}"
        val expected = "\n\n\$\$\n$formula\n\$\$\n\n"
        assertEquals(expected, MathMarkdown.normalize("\\[$formula\\]"))
        assertEquals(expected, MathMarkdown.normalize("\$\$$formula\$\$"))
    }

    @Test fun `leaves code escaped currency and incomplete math readable`() {
        val literal = "```latex\n\\(x\\) \$y\$\n```\n`\$z\$` \\$5 and \\$10; \\(incomplete"
        assertEquals(literal, MathMarkdown.normalize(literal))
    }
}
