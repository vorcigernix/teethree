package com.example.teethree.ui

import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner

internal class MarkdownMathInline(val latex: String, val display: Boolean) : CustomNode() {
    val source: String
        get() = if (display) "\$\$$latex\$\$" else "\$$latex\$"
}

internal class MarkdownMathBlock(val latex: String) : CustomBlock() {
    val source: String
        get() = "\$\$$latex\$\$"
}

internal fun markdownParser(mathEnabled: Boolean): Parser {
    val builder = Parser.builder()
    if (mathEnabled)
        builder
            .customInlineContentParserFactory(
                object : InlineContentParserFactory {
                    override fun getTriggerCharacters() = setOf('$')

                    override fun create() = InlineContentParser { state -> parseMathInline(state) }
                }
            )
            .postProcessor { document ->
                promoteDisplayMath(document)
                document
            }
    return builder.build()
}

private fun mathBoundary(codePoint: Int): Boolean =
    codePoint == Scanner.END.code ||
        codePoint < 0 ||
        Character.isWhitespace(codePoint) ||
        (codePoint < 128 &&
            (codePoint in 33..47 ||
                codePoint in 58..64 ||
                codePoint in 91..96 ||
                codePoint in 123..126)) ||
        Character.getType(codePoint) in
            setOf(
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
            )

private fun parseMathInline(state: InlineParserState): ParsedInline? {
    val scanner = state.scanner()
    if (!mathBoundary(scanner.peekPreviousCodePoint())) return ParsedInline.none()
    val count = scanner.matchMultiple('$')
    if (count !in 1..2) return ParsedInline.none()
    val start = scanner.position()
    while (scanner.hasNext()) {
        when (scanner.peek()) {
            '\\' -> {
                scanner.next()
                if (scanner.hasNext()) scanner.next()
            }
            '$' -> {
                val end = scanner.position()
                val closing = scanner.matchMultiple('$')
                if (closing == count && mathBoundary(scanner.peekCodePoint())) {
                    val latex = scanner.getSource(start, end).content
                    if (latex.isNotBlank())
                        return ParsedInline.of(
                            MarkdownMathInline(latex, count == 2),
                            scanner.position(),
                        )
                }
            }
            else -> scanner.next()
        }
    }
    return ParsedInline.none()
}

private fun separator(node: Node?) =
    node == null ||
        node is SoftLineBreak ||
        node is HardLineBreak ||
        node is Text && node.literal.isBlank()

private fun promoteDisplayMath(parent: Node) {
    val children = generateSequence(parent.firstChild) { it.next }.toList()
    children.forEach(::promoteDisplayMath)
    if (parent !is Paragraph) return
    val isolated =
        children
            .filterIsInstance<MarkdownMathInline>()
            .filter { it.display && (separator(it.previous) || separator(it.next)) }
            .toSet()
    if (isolated.isEmpty()) return
    var paragraph = Paragraph()
    var skipSeparators = false
    fun flush() {
        while (paragraph.lastChild != null && separator(paragraph.lastChild)) paragraph.lastChild
            .unlink()
        if (paragraph.firstChild != null) parent.insertBefore(paragraph)
        paragraph = Paragraph()
    }
    children.forEach { node ->
        if (node in isolated) {
            flush()
            parent.insertBefore(MarkdownMathBlock((node as MarkdownMathInline).latex))
            node.unlink()
            skipSeparators = true
        } else if (skipSeparators && separator(node)) node.unlink()
        else {
            skipSeparators = false
            paragraph.appendChild(node)
        }
    }
    flush()
    parent.unlink()
}
