package com.example.teethree

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
data class ClientAppearancePreferences(
    val baseFontSize: Int = 16,
    val codeFontSize: Int? = null,
    val terminalFontSize: Float? = null,
    val codeWordBreak: Boolean = false,
    val mathRenderingEnabled: Boolean = false,
)

data class ResolvedClientAppearance(
    val textScale: Float,
    val codeFontSize: Int,
    val terminalFontSize: Float,
    val codeWordBreak: Boolean,
    val mathRenderingEnabled: Boolean,
)

fun normalizeClientAppearance(value: ClientAppearancePreferences): ClientAppearancePreferences =
    value.copy(
        baseFontSize = value.baseFontSize.coerceIn(11, 22),
        codeFontSize = value.codeFontSize?.coerceIn(8, 18),
        terminalFontSize = value.terminalFontSize?.takeIf(Float::isFinite)?.coerceIn(6f, 14f),
    )

fun resolveClientAppearance(value: ClientAppearancePreferences): ResolvedClientAppearance {
    val normalized = normalizeClientAppearance(value)
    val scale = normalized.baseFontSize / 16f
    return ResolvedClientAppearance(
        scale,
        normalized.codeFontSize ?: (12 * scale).roundToInt().coerceIn(8, 18),
        normalized.terminalFontSize ?: (((10.5f * scale * 2).roundToInt()) / 2f).coerceIn(6f, 14f),
        normalized.codeWordBreak,
        normalized.mathRenderingEnabled,
    )
}
