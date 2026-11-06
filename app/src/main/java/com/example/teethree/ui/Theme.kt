package com.example.teethree.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.example.teethree.ClientAppearancePreferences
import com.example.teethree.R
import com.example.teethree.harmonizeWith
import com.example.teethree.resolveClientAppearance

enum class AppTheme {
    System,
    Light,
    Dark,
}

@OptIn(ExperimentalTextApi::class)
val T3UiFontFamily =
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map {
            Font(
                R.font.roboto_flex,
                weight = it,
                variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)),
            )
        }
    )

@OptIn(ExperimentalTextApi::class)
val T3MonoFontFamily =
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.Bold).map {
            Font(
                R.font.jetbrains_mono,
                weight = it,
                variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)),
            )
        }
    )

@Immutable
private data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val codeSurface: Color,
    val onCodeSurface: Color,
    val onCodeSurfaceVariant: Color,
)

// Seed tones for the one role Material You has no slot for. They are harmonized toward the
// wallpaper-derived primary at runtime, the same way MDC harmonizes custom colors.
private val LightSuccessSeeds =
    listOf(Color(0xFF3B6939), Color.White, Color(0xFFBCF0B4), Color(0xFF0B390B))
private val DarkSuccessSeeds =
    listOf(Color(0xFFA1D599), Color(0xFF0F390D), Color(0xFF245023), Color(0xFFBCF0B4))
// Warning, container, on-container. Used for "trying to reconnect", between fine and failed.
private val LightWarningSeeds = listOf(Color(0xFF7C5800), Color(0xFFFFDEA6), Color(0xFF5E4200))
private val DarkWarningSeeds = listOf(Color(0xFFF5BF48), Color(0xFF5E4200), Color(0xFFFFDEA6))

private fun statusColors(scheme: ColorScheme, dark: Boolean): StatusColors {
    val (success, onSuccess, successContainer, onSuccessContainer) =
        (if (dark) DarkSuccessSeeds else LightSuccessSeeds).map {
            it.harmonizeWith(scheme.primary)
        }
    val (warning, warningContainer, onWarningContainer) =
        (if (dark) DarkWarningSeeds else LightWarningSeeds).map { it.harmonizeWith(scheme.primary) }
    // Code and terminal output stay dark in both modes, tinted by the system neutral palette.
    return StatusColors(
        success = success,
        onSuccess = onSuccess,
        successContainer = successContainer,
        onSuccessContainer = onSuccessContainer,
        warning = warning,
        warningContainer = warningContainer,
        onWarningContainer = onWarningContainer,
        codeSurface = if (dark) scheme.surfaceContainerLowest else scheme.inverseSurface,
        onCodeSurface = if (dark) scheme.onSurface else scheme.inverseOnSurface,
        onCodeSurfaceVariant =
            if (dark) scheme.onSurfaceVariant else scheme.inverseOnSurface.copy(alpha = 0.72f),
    )
}

private val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(
        LightSuccessSeeds[0],
        LightSuccessSeeds[1],
        LightSuccessSeeds[2],
        LightSuccessSeeds[3],
        LightWarningSeeds[0],
        LightWarningSeeds[1],
        LightWarningSeeds[2],
        Color(0xFF2E3036),
        Color(0xFFF0F0F7),
        Color(0xFFF0F0F7).copy(alpha = 0.72f),
    )
}

object T3Colors {
    val success: Color
        @Composable get() = LocalStatusColors.current.success

    val onSuccess: Color
        @Composable get() = LocalStatusColors.current.onSuccess

    val successContainer: Color
        @Composable get() = LocalStatusColors.current.successContainer

    val onSuccessContainer: Color
        @Composable get() = LocalStatusColors.current.onSuccessContainer

    val warning: Color
        @Composable get() = LocalStatusColors.current.warning

    val warningContainer: Color
        @Composable get() = LocalStatusColors.current.warningContainer

    val onWarningContainer: Color
        @Composable get() = LocalStatusColors.current.onWarningContainer

    /** Background for code blocks and live command output. */
    val codeSurface: Color
        @Composable get() = LocalStatusColors.current.codeSurface

    val onCodeSurface: Color
        @Composable get() = LocalStatusColors.current.onCodeSurface

    val onCodeSurfaceVariant: Color
        @Composable get() = LocalStatusColors.current.onCodeSurfaceVariant
}

private val AppTypography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 40.sp,
                lineHeight = 48.sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 36.sp,
                lineHeight = 44.sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 32.sp,
                lineHeight = 40.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 28.sp,
                lineHeight = 36.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 24.sp,
                lineHeight = 32.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 22.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = T3UiFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
    )

val LocalClientAppearance = staticCompositionLocalOf {
    resolveClientAppearance(ClientAppearancePreferences())
}

private fun TextStyle.scaled(scale: Float) =
    copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

private fun scaledTypography(scale: Float) =
    AppTypography.copy(
        displayLarge = AppTypography.displayLarge.scaled(scale),
        displayMedium = AppTypography.displayMedium.scaled(scale),
        displaySmall = AppTypography.displaySmall.scaled(scale),
        headlineLarge = AppTypography.headlineLarge.scaled(scale),
        headlineMedium = AppTypography.headlineMedium.scaled(scale),
        headlineSmall = AppTypography.headlineSmall.scaled(scale),
        titleLarge = AppTypography.titleLarge.scaled(scale),
        titleMedium = AppTypography.titleMedium.scaled(scale),
        titleSmall = AppTypography.titleSmall.scaled(scale),
        bodyLarge = AppTypography.bodyLarge.scaled(scale),
        bodyMedium = AppTypography.bodyMedium.scaled(scale),
        bodySmall = AppTypography.bodySmall.scaled(scale),
        labelLarge = AppTypography.labelLarge.scaled(scale),
        labelMedium = AppTypography.labelMedium.scaled(scale),
        labelSmall = AppTypography.labelSmall.scaled(scale),
    )

@Composable
fun T3Theme(
    theme: AppTheme,
    appearance: ClientAppearancePreferences = ClientAppearancePreferences(),
    content: @Composable () -> Unit,
) {
    val resolvedAppearance = remember(appearance) { resolveClientAppearance(appearance) }
    val typography =
        remember(resolvedAppearance.textScale) { scaledTypography(resolvedAppearance.textScale) }
    val dark =
        when (theme) {
            AppTheme.System -> isSystemInDarkTheme()
            AppTheme.Light -> false
            AppTheme.Dark -> true
        }
    val context = LocalContext.current
    val view = LocalView.current
    // Material You: the scheme comes from the system palette (wallpaper or chosen theme colors).
    // The configuration key picks up palette changes delivered without recreating the activity.
    val configuration = LocalConfiguration.current
    val colorScheme =
        remember(dark, context, configuration) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
    val statusColors = remember(colorScheme, dark) { statusColors(colorScheme, dark) }
    val activity =
        remember(context) {
            generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
        }
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalStatusColors provides statusColors,
        LocalClientAppearance provides resolvedAppearance,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes =
                Shapes(
                    small = RoundedCornerShape(12.dp),
                    medium = RoundedCornerShape(16.dp),
                    large = RoundedCornerShape(24.dp),
                    extraLarge = RoundedCornerShape(28.dp),
                ),
            content = content,
        )
    }
}
