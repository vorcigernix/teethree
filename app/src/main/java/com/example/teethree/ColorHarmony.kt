package com.example.teethree

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Rotates this color's hue toward [source] by half the difference, capped at 15 degrees, keeping
 * lightness and chroma. This is how Material You fits a fixed semantic color (success green) to the
 * wallpaper palette; it works in Oklab, a close stand-in for the HCT space MDC uses.
 */
internal fun Color.harmonizeWith(source: Color): Color {
    if (this == Color.White || this == Color.Black) return this
    val lab = convert(ColorSpaces.Oklab)
    val target = source.convert(ColorSpaces.Oklab)
    val chroma = hypot(lab.green, lab.blue)
    if (chroma < 0.02f || hypot(target.green, target.blue) < 0.02f) return this
    val hue = atan2(lab.blue, lab.green)
    var difference = atan2(target.blue, target.green) - hue
    if (difference > PI) difference -= (2 * PI).toFloat()
    if (difference < -PI) difference += (2 * PI).toFloat()
    val rotation = sign(difference) * min(abs(difference) * 0.5f, (15 * PI / 180).toFloat())
    val rotated = hue + rotation
    return Color(lab.red, chroma * cos(rotated), chroma * sin(rotated), alpha, ColorSpaces.Oklab)
        .convert(ColorSpaces.Srgb)
}

/** [harmonizeWith] for platform ARGB colors, such as notification tints. */
internal fun harmonizeArgb(color: Int, source: Int): Int =
    Color(color).harmonizeWith(Color(source)).toArgb()
