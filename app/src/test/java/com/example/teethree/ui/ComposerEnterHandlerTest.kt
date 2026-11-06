package com.example.teethree.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerEnterHandlerTest {
    @Test
    fun sendModeConsumesTheWholePressAndNeverResubmitsHeldReturn() {
        val handler = ComposerEnterHandler()
        var sent = 0
        assertTrue(
            handler.handle(true, true, false, false, false, "send", false, false, false, true) {
                sent++
            }
        )
        assertTrue(
            handler.handle(true, true, false, true, false, "send", false, false, false, false) {
                sent++
            }
        )
        assertTrue(
            handler.handle(true, false, true, false, false, "send", false, false, false, false) {
                sent++
            }
        )
        assertEquals(1, sent)
        assertTrue(
            handler.handle(true, true, false, false, false, "send", false, false, false, true) {
                sent++
            }
        )
        assertEquals(2, sent)
    }

    @Test
    fun newlineModeRequiresControlOrCommandAndShiftSoftKeyboardAndDisabledNeverSend() {
        data class Press(
            val behavior: String = "send",
            val shift: Boolean = false,
            val control: Boolean = false,
            val meta: Boolean = false,
            val soft: Boolean = false,
            val enabled: Boolean = true,
        )
        for (press in
            listOf(
                Press(behavior = "newline"),
                Press(shift = true),
                Press(behavior = "newline", shift = true, control = true),
                Press(soft = true),
                Press(enabled = false),
            )) {
            var sent = false
            assertFalse(
                ComposerEnterHandler().handle(
                    true,
                    true,
                    false,
                    false,
                    press.soft,
                    press.behavior,
                    press.shift,
                    press.control,
                    press.meta,
                    press.enabled,
                ) {
                    sent = true
                }
            )
            assertFalse(sent)
        }
        for ((control, meta) in listOf(true to false, false to true)) {
            var sent = false
            assertTrue(
                ComposerEnterHandler().handle(
                    true,
                    true,
                    false,
                    false,
                    false,
                    "newline",
                    false,
                    control,
                    meta,
                    true,
                ) {
                    sent = true
                }
            )
            assertTrue(sent)
        }
    }
}
