package com.example.teethree.ui

import org.commonmark.node.*
import org.junit.Assert.*
import org.junit.Test

class MarkdownMathParserTest {
    private fun descendants(node: Node): List<Node> =
        listOf(node) +
            generateSequence(node.firstChild) { it.next }
                .flatMap { descendants(it).asSequence() }
                .toList()

    @Test
    fun inlineMathPreservesLatexAndSkipsEscapedDollarsCodeAndUnmatchedDelimiters() {
        val source =
            """Text $\frac{1}{2} + x_1$ and `not ${'$'}math${'$'}` plus \$5. Unclosed ${'$'}value."""
        val document = markdownParser(true).parse(source)
        val math = descendants(document).filterIsInstance<MarkdownMathInline>()
        assertEquals(listOf("""\frac{1}{2} + x_1"""), math.map { it.latex })
        assertFalse(math.single().display)
        assertTrue(
            descendants(document)
                .filterIsInstance<Code>()
                .single()
                .literal
                .contains("${'$'}math${'$'}")
        )
        assertTrue(
            descendants(markdownParser(false).parse(source)).none {
                it is MarkdownMathInline || it is MarkdownMathBlock
            }
        )
        assertTrue(
            descendants(
                    markdownParser(true)
                        .parse(
                            """```latex
${'$'}code${'$'}
```"""
                        )
                )
                .none { it is MarkdownMathInline || it is MarkdownMathBlock }
        )
    }

    @Test
    fun displayMathPromotesOnlyIsolatedSpansAndPreservesSurroundingParagraphs() {
        val document =
            markdownParser(true)
                .parse(
                    """Before
$$\begin{aligned}x&=1\\y&=2\end{aligned}$$
After"""
                )
        val children = generateSequence(document.firstChild) { it.next }.toList()
        assertEquals(
            listOf(Paragraph::class.java, MarkdownMathBlock::class.java, Paragraph::class.java),
            children.map { it.javaClass },
        )
        assertEquals(
            """\begin{aligned}x&=1\\y&=2\end{aligned}""",
            (children[1] as MarkdownMathBlock).latex,
        )
        val inlineDisplay =
            descendants(markdownParser(true).parse("Before ${'$'}${'$'}x^2${'$'}${'$'} after"))
                .filterIsInstance<MarkdownMathInline>()
        assertEquals(1, inlineDisplay.size)
        assertTrue(inlineDisplay.single().display)
        assertTrue(
            descendants(markdownParser(true).parse("word${'$'}x${'$'}tail")).none {
                it is MarkdownMathInline || it is MarkdownMathBlock
            }
        )
    }
}
