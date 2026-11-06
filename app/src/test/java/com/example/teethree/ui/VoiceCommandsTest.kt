package com.example.teethree.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {
    private val offered = listOf("accept", "acceptForSession", "decline")

    @Test
    fun shortAnswersPickTheOfferedDecision() {
        assertEquals("accept", approvalDecision("Allow", offered))
        assertEquals("accept", approvalDecision("yes please", offered))
        assertEquals("acceptForSession", approvalDecision("Always allow.", offered))
        assertEquals("decline", approvalDecision("No.", offered))
        assertEquals("decline", approvalDecision("decline it", offered))
    }

    @Test
    fun longerSpeechSteersInsteadOfApproving() {
        assertNull(approvalDecision("yes, but rename the helper to clampValue first", offered))
        assertNull(approvalDecision("what does the second file change", offered))
        assertNull(approvalDecision("always", listOf("accept", "decline")))
        assertNull(approvalDecision("stop", offered))
    }

    @Test
    fun trailingCancelDiscardsTheCapture() {
        assertTrue(isDiscardPhrase("Also make sure the backoff caps at thirty seconds cancel"))
        assertTrue(isDiscardPhrase("cancel that"))
        assertTrue(isDiscardPhrase("Cancel."))
        assertFalse(isDiscardPhrase("cancel the retry timer when the socket closes"))
    }

    @Test
    fun spokenChoicesReadNaturally() {
        assertEquals("Say “allow”, “always” or “no”", spokenChoices(listOf("no", "always", "allow")))
        assertEquals("Say “allow”", spokenChoices(listOf("allow")))
    }

    @Test
    fun markdownIsReadAsPlainSpeech() {
        assertEquals(
            "Done clamp() sits next to sum(). See the docs.",
            speechText(
                "## Done\n**clamp()** sits next to `sum()`.\n```ts\nconst x = 1\n```\nSee [the docs](https://example.com)."
            ),
        )
        assertEquals("Reads the manifest Asks first", speechText("- Reads the manifest\n- Asks first"))
    }
}
