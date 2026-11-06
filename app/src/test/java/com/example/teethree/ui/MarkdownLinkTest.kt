package com.example.teethree.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownLinkTest {
    @Test
    fun `only valid external web and mail links are interactive`() {
        listOf(
                "https://example.com/path?q=test#section",
                "http://192.168.1.2:3000",
                "HTTPS://example.com",
                "mailto:person@example.com",
            )
            .forEach { assertTrue(markdownLinkAllowed(it), it) }

        listOf(
                "javascript:alert(1)",
                "JaVaScRiPt:alert(1)",
                "data:text/html,<script>alert(1)</script>",
                "file:///private/file",
                "content://contacts/1",
                "intent://example.com#Intent;scheme=https;end",
                "t3code:/threads/1",
                "ftp://example.com",
                "//example.com",
                "/relative/path",
                "#section",
                " https://example.com",
                "https://example.com/with a space",
                "https://example.com/\npath",
            )
            .forEach { assertFalse(markdownLinkAllowed(it), it) }
    }
}
