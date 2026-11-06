package com.example.teethree.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.teethree.data.threadSearchOffsets
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.ThematicBreak

data class MarkdownFind(val query: String, val occurrence: Int)

internal data class MarkdownFindTarget(val node: Node, val start: Int, val end: Int)

private data class ParsedMessage(
    val source: String,
    val document: Node,
    val find: MarkdownFind?,
    val target: MarkdownFindTarget?,
)

private class MarkdownFindPosition(private val onPosition: (Float?) -> Unit) {
    var root: LayoutCoordinates? = null
    var child: LayoutCoordinates? = null
    var line: Float? = null

    fun report() {
        val parent = root ?: return
        val target = child ?: return
        val top = line ?: return
        if (parent.isAttached && target.isAttached)
            onPosition(parent.localPositionOf(target, Offset.Zero).y + top)
    }
}

internal fun findMarkdownTarget(document: Node, find: MarkdownFind?): MarkdownFindTarget? {
    if (find == null || find.query.isEmpty() || find.occurrence < 0) return null
    var occurrence = find.occurrence
    fun visit(node: Node): MarkdownFindTarget? {
        val text =
            when (node) {
                is Heading,
                is Paragraph -> buildAnnotatedString {
                        node.children().forEach {
                            appendMarkdownInline(it, Color.Unspecified, Color.Unspecified) {}
                        }
                    }
                        .text
                is FencedCodeBlock -> node.literal.trimEnd('\n')
                is IndentedCodeBlock -> node.literal.trimEnd('\n')
                is HtmlBlock -> node.literal
                is MarkdownMathBlock -> node.source
                else -> null
            }
        if (text != null) {
            val offsets =
                threadSearchOffsets(
                    if (node is FencedCodeBlock || node is IndentedCodeBlock) text
                    else text.replace('\n', ' '),
                    find.query,
                )
            if (occurrence < offsets.size)
                return MarkdownFindTarget(
                    node,
                    offsets[occurrence],
                    offsets[occurrence] + find.query.length,
                )
            occurrence -= offsets.size
        } else
            node.children().forEach { child ->
                visit(child)?.let {
                    return it
                }
            }
        return null
    }
    return visit(document)
}

@Composable
fun MarkdownMessage(
    text: String,
    modifier: Modifier = Modifier,
    find: MarkdownFind? = null,
    onFindPosition: (Float?) -> Unit = {},
) {
    val mathEnabled = LocalClientAppearance.current.mathRenderingEnabled
    val parser = remember(mathEnabled) { markdownParser(mathEnabled) }
    val parsed by
        produceState<ParsedMessage?>(null, text, find, parser) {
            value =
                withContext(Dispatchers.Default) {
                    ensureActive()
                    val document = parser.parse(text)
                    ensureActive()
                    ParsedMessage(text, document, find, findMarkdownTarget(document, find))
                }
        }
    val latestPosition = rememberUpdatedState(onFindPosition)
    val current = parsed
    val matching = current?.takeIf { it.source == text && it.find == find }
    val position = remember(matching) { MarkdownFindPosition { latestPosition.value(it) } }
    LaunchedEffect(matching, find) {
        if (find != null && matching != null && matching.target == null) latestPosition.value(null)
    }
    SelectionContainer(modifier) {
        Column(
            Modifier.onGloballyPositioned {
                position.root = it
                position.report()
            }
        ) {
            if (current == null) Text(text, style = MaterialTheme.typography.bodyLarge)
            else MarkdownBlocks(current.document, matching?.target, position)
        }
    }
}

private fun Node.children() = generateSequence(firstChild) { it.next }

@Composable
private fun MarkdownBlocks(
    parent: Node,
    target: MarkdownFindTarget?,
    position: MarkdownFindPosition,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        parent.children().forEach { node ->
            when (node) {
                is Heading ->
                    MarkdownBlockText(
                        markdownInline(node),
                        node,
                        target,
                        position,
                        modifier = Modifier.semantics { heading() },
                        style =
                            when (node.level) {
                                1 -> MaterialTheme.typography.headlineMedium
                                2 -> MaterialTheme.typography.headlineSmall
                                3 -> MaterialTheme.typography.titleLarge
                                else -> MaterialTheme.typography.titleMedium
                            },
                        fontWeight = FontWeight.SemiBold,
                    )
                is Paragraph ->
                    MarkdownBlockText(
                        markdownInline(node),
                        node,
                        target,
                        position,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                is MarkdownMathBlock ->
                    MarkdownBlockText(
                        buildAnnotatedString {
                            pushStringAnnotation(DISPLAY_MATH, node.latex)
                            append(node.source)
                            pop()
                        },
                        node,
                        target,
                        position,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        centered = true,
                    )
                is FencedCodeBlock -> MarkdownCode(node.literal, node, target, position, node.info)
                is IndentedCodeBlock -> MarkdownCode(node.literal, node, target, position)
                is BlockQuote ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("│", color = MaterialTheme.colorScheme.outline)
                        Column(Modifier.weight(1f)) { MarkdownBlocks(node, target, position) }
                    }
                is BulletList,
                is OrderedList ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        node.children().forEachIndexed { index, item ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (node is OrderedList)
                                        "${(node.markerStartNumber ?: 1) + index}."
                                    else "•",
                                    modifier = Modifier.width(28.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Column(Modifier.weight(1f)) {
                                    MarkdownBlocks(item, target, position)
                                }
                            }
                        }
                    }
                is ThematicBreak -> HorizontalDivider()
                is HtmlBlock ->
                    MarkdownBlockText(
                        AnnotatedString(node.literal),
                        node,
                        target,
                        position,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                is LinkReferenceDefinition -> Unit
                else -> MarkdownBlocks(node, target, position)
            }
        }
    }
}

@Composable
private fun MarkdownCode(
    code: String,
    node: Node,
    target: MarkdownFindTarget?,
    position: MarkdownFindPosition,
    language: String = "",
) {
    val scroll = rememberScrollState()
    val appearance = LocalClientAppearance.current
    var findBounds by
        remember(node, target) { androidx.compose.runtime.mutableStateOf<Rect?>(null) }
    LaunchedEffect(findBounds, appearance.codeWordBreak) {
        if (!appearance.codeWordBreak)
            findBounds?.let { scroll.scrollTo(it.left.toInt().coerceAtLeast(0)) }
    }
    Column(
        Modifier.fillMaxWidth()
            .background(
                T3Colors.codeSurface,
                MaterialTheme.shapes.medium,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (language.isNotBlank())
            Text(
                language,
                style = MaterialTheme.typography.labelSmall,
                color = T3Colors.onCodeSurfaceVariant,
            )
        MarkdownBlockText(
            AnnotatedString(code.trimEnd('\n')),
            node,
            target,
            position,
            style =
                MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = T3MonoFontFamily,
                    color = T3Colors.onCodeSurface,
                    fontSize = appearance.codeFontSize.sp,
                    lineHeight = (appearance.codeFontSize + 6).sp,
                ),
            modifier =
                if (appearance.codeWordBreak) Modifier.fillMaxWidth()
                else Modifier.horizontalScroll(scroll),
            onFindBounds = { findBounds = it },
        )
    }
}

@Composable
private fun MarkdownBlockText(
    text: AnnotatedString,
    node: Node,
    target: MarkdownFindTarget?,
    position: MarkdownFindPosition,
    modifier: Modifier = Modifier,
    style: TextStyle,
    fontWeight: FontWeight? = null,
    onFindBounds: (Rect) -> Unit = {},
    centered: Boolean = false,
) {
    val selected = target?.takeIf { it.node === node }
    val color = MaterialTheme.colorScheme.tertiaryContainer
    val foreground = MaterialTheme.colorScheme.onTertiaryContainer
    val marked =
        remember(text, selected, color, foreground) {
            if (selected == null) text
            else
                AnnotatedString.Builder(text)
                    .apply {
                        addStyle(
                            SpanStyle(background = color, color = foreground),
                            selected.start,
                            selected.end,
                        )
                    }
                    .toAnnotatedString()
        }
    val textModifier =
        modifier.then(
            if (selected == null) Modifier
            else
                Modifier.onGloballyPositioned {
                    position.child = it
                    position.report()
                }
        )
    val findBounds: (Rect) -> Unit = { bounds ->
        if (selected != null) {
            position.line = bounds.top
            position.report()
            onFindBounds(bounds)
        }
    }
    if (
        LocalClientAppearance.current.mathRenderingEnabled &&
            marked.getStringAnnotations(0, marked.length).any {
                it.tag == INLINE_MATH || it.tag == DISPLAY_MATH
            }
    ) {
        NativeMathText(
            marked,
            style,
            textModifier,
            fontWeight,
            centered,
            selected?.start,
            findBounds,
        )
    } else
        Text(
            marked,
            textModifier,
            style = style,
            fontWeight = fontWeight,
            onTextLayout = { layout: TextLayoutResult ->
                if (selected != null) findBounds(layout.getBoundingBox(selected.start))
            },
        )
}

@Composable
private fun markdownInline(parent: Node): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    return remember(parent, linkColor, codeBackground, uriHandler, context) {
        buildAnnotatedString {
            parent.children().forEach { node ->
                appendMarkdownInline(node, linkColor, codeBackground) { destination ->
                    try {
                        uriHandler.openUri(destination)
                    } catch (_: IllegalArgumentException) {
                        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownInline(
    node: Node,
    linkColor: Color,
    codeBackground: Color,
    openLink: (String) -> Unit,
) {
    fun children() {
        node.children().forEach { appendMarkdownInline(it, linkColor, codeBackground, openLink) }
    }
    when (node) {
        is MarkdownMathInline -> {
            pushStringAnnotation(if (node.display) DISPLAY_MATH else INLINE_MATH, node.latex)
            append(node.source)
            pop()
        }
        is MarkdownText -> append(node.literal)
        is Code ->
            withStyle(SpanStyle(fontFamily = T3MonoFontFamily, background = codeBackground)) {
                append(node.literal)
            }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
        is Link -> {
            if (markdownLinkAllowed(node.destination)) {
                withLink(
                    LinkAnnotation.Url(
                        node.destination,
                        TextLinkStyles(
                            SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                        ),
                        linkInteractionListener = { openLink(node.destination) },
                    )
                ) {
                    children()
                }
            } else children()
        }
        is Image -> {
            append("[Image: ")
            children()
            append("]")
        }
        is SoftLineBreak,
        is HardLineBreak -> append('\n')
        is HtmlInline -> append(node.literal)
        else -> children()
    }
}

internal fun markdownLinkAllowed(destination: String): Boolean = runCatching {
    URI(destination).scheme?.lowercase() in setOf("http", "https", "mailto")
}
    .getOrDefault(false)
