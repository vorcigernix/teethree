package com.example.teethree.ui

import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Paragraph
import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFindTest {
    @Test
    fun findSpansInlineFormattingAndCountsOccurrencesAcrossNativeBlocks() {
        val document =
            Parser.builder().build().parse("A **needle** and `needle`.\n\n```text\nneedle\n```")
        val first = findMarkdownTarget(document, MarkdownFind("needle", 0))!!
        val second = findMarkdownTarget(document, MarkdownFind("needle", 1))!!
        val code = findMarkdownTarget(document, MarkdownFind("needle", 2))!!
        assertTrue(first.node is Paragraph)
        assertEquals(2, first.start)
        assertEquals(13, second.start)
        assertEquals(first.node, second.node)
        assertTrue(code.node is FencedCodeBlock)
        assertEquals(0, code.start)
        assertNull(findMarkdownTarget(document, MarkdownFind("needle", 3)))
    }

    @Test
    fun hiddenLinkDestinationsDoNotStealTheVisibleOccurrenceAndSoftBreaksKeepOffsets() {
        val parser = Parser.builder().build()
        val links = parser.parse("[visible](https://needle.invalid) **nee**dle")
        assertEquals(8, findMarkdownTarget(links, MarkdownFind("needle", 0))!!.start)
        assertNull(findMarkdownTarget(links, MarkdownFind("invalid", 0)))
        val line = findMarkdownTarget(parser.parse("first\nline"), MarkdownFind("first line", 0))!!
        assertEquals(0, line.start)
        assertEquals(10, line.end)
    }
}
