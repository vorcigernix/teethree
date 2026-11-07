package com.example.teethree.ui

import android.graphics.Paint
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeMathTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun textViews(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
            (if (view is ViewGroup)
                (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
            else emptyList())

    @Test
    fun nativeFormulaSpansPreserveBaselineSourceSelectionAndLinks() {
        val clicked = AtomicInteger()
        val source = buildAnnotatedString {
            append("Area ")
            pushStringAnnotation(INLINE_MATH, "\\frac{1}{2}")
            append("${'$'}\\frac{1}{2}${'$'}")
            pop()
            append(" ")
            withLink(
                LinkAnnotation.Url(
                    "https://example.com",
                    linkInteractionListener = { clicked.incrementAndGet() },
                )
            ) {
                append("reference")
            }
        }
        val block = buildAnnotatedString {
            pushStringAnnotation(DISPLAY_MATH, "\\sum_{n=1}^{10} n")
            append("${'$'}${'$'}\\sum_{n=1}^{10} n${'$'}${'$'}")
            pop()
        }
        compose.setContent {
            T3Theme(AppTheme.Light) {
                Column(Modifier.width(320.dp)) {
                    NativeMathText(source, MaterialTheme.typography.bodyLarge)
                    NativeMathText(block, MaterialTheme.typography.bodyLarge, centered = true)
                }
            }
        }
        // The formulas typeset off the main thread; a cold emulator can take a few seconds.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.runOnIdle {
                textViews(compose.activity.window.decorView).count { view ->
                    (view.text as? Spanned)
                        ?.getSpans(0, view.text.length, ReplacementSpan::class.java)
                        ?.size == 1
                } == 2
            }
        }
        compose.runOnIdle {
            val views = textViews(compose.activity.window.decorView)
            val inline = views.single { it.text.toString() == source.text }
            assertTrue(inline.isTextSelectable)
            val text = inline.text as Spanned
            val span = text.getSpans(0, text.length, ReplacementSpan::class.java).single()
            val metrics = Paint.FontMetricsInt()
            assertTrue(
                span.getSize(
                    inline.paint,
                    text,
                    text.getSpanStart(span),
                    text.getSpanEnd(span),
                    metrics,
                ) > 0
            )
            assertTrue(metrics.ascent <= inline.paint.fontMetricsInt.ascent)
            assertTrue(metrics.descent >= inline.paint.fontMetricsInt.descent)
            assertTrue(inline.layout.height >= metrics.descent - metrics.ascent)
            text.getSpans(0, text.length, ClickableSpan::class.java).single().onClick(inline)
            assertEquals(1, clicked.get())
            assertEquals(source.text, inline.text.toString())
        }
    }
}
