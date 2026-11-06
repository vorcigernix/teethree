package com.example.teethree

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ClientAppearancePreferencesTest {
    @Test
    fun appearanceDefaultsAndInheritedSizesMatchAndroidClientRules() {
        val defaults = resolveClientAppearance(ClientAppearancePreferences())
        assertEquals(1f, defaults.textScale)
        assertEquals(12, defaults.codeFontSize)
        assertEquals(10.5f, defaults.terminalFontSize)
        assertFalse(defaults.codeWordBreak)
        assertFalse(defaults.mathRenderingEnabled)
        val large = resolveClientAppearance(ClientAppearancePreferences(baseFontSize = 22))
        assertEquals(17, large.codeFontSize)
        assertEquals(14f, large.terminalFontSize)
        val custom =
            resolveClientAppearance(
                ClientAppearancePreferences(
                    baseFontSize = 22,
                    codeFontSize = 10,
                    terminalFontSize = 8.5f,
                )
            )
        assertEquals(10, custom.codeFontSize)
        assertEquals(8.5f, custom.terminalFontSize)
        assertEquals(
            8f,
            resolveClientAppearance(ClientAppearancePreferences(baseFontSize = 12))
                .terminalFontSize,
        )
    }

    @Test
    fun storedSizesClampToSupportedRangesAndInvalidCustomSizesInherit() {
        val low =
            normalizeClientAppearance(
                ClientAppearancePreferences(
                    baseFontSize = -1,
                    codeFontSize = 0,
                    terminalFontSize = 1f,
                )
            )
        assertEquals(11, low.baseFontSize)
        assertEquals(8, low.codeFontSize)
        assertEquals(6f, low.terminalFontSize)
        val high =
            normalizeClientAppearance(
                ClientAppearancePreferences(
                    baseFontSize = 100,
                    codeFontSize = 100,
                    terminalFontSize = 100f,
                )
            )
        assertEquals(22, high.baseFontSize)
        assertEquals(18, high.codeFontSize)
        assertEquals(14f, high.terminalFontSize)
        assertNull(
            normalizeClientAppearance(ClientAppearancePreferences(terminalFontSize = Float.NaN))
                .terminalFontSize
        )
        assertNull(
            normalizeClientAppearance(
                    ClientAppearancePreferences(terminalFontSize = Float.POSITIVE_INFINITY)
                )
                .terminalFontSize
        )
        assertEquals(
            ClientAppearancePreferences(),
            Json.decodeFromString<ClientPreferences>("{}").appearance,
        )
    }
}
