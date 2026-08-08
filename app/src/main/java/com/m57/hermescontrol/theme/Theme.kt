package com.m57.hermescontrol.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.m57.hermescontrol.theme.presets.AmoledTheme
import com.m57.hermescontrol.theme.presets.CatppuccinTheme
import com.m57.hermescontrol.theme.presets.DefaultTheme
import com.m57.hermescontrol.theme.presets.GruvboxTheme
import com.m57.hermescontrol.theme.presets.MonochromeTheme
import com.m57.hermescontrol.theme.presets.NordTheme
import kotlinx.serialization.Serializable

@Serializable
enum class ThemePreference { SYSTEM, LIGHT, DARK }

@Serializable
enum class ThemePreset { DEFAULT, MONOCHROME, GRUVBOX, CATPPUCCIN, AMOLED, NORD }

val LocalThemePreference = compositionLocalOf { ThemePreference.SYSTEM }
val LocalThemePreset = compositionLocalOf { ThemePreset.DEFAULT }

/**
 * The 6 preset themes — one file each, all built from the same
 * [PaletteTemplate] shape (see `PaletteTemplate.kt`).
 */
private fun themeFor(preset: ThemePreset): ThemePalette =
    when (preset) {
        ThemePreset.DEFAULT -> DefaultTheme
        ThemePreset.MONOCHROME -> MonochromeTheme
        ThemePreset.GRUVBOX -> GruvboxTheme
        ThemePreset.CATPPUCCIN -> CatppuccinTheme
        ThemePreset.AMOLED -> AmoledTheme
        ThemePreset.NORD -> NordTheme
    }

/**
 * Resolve the Material 3 [ColorScheme] for a preset + dark flag.
 *
 * A preset that doesn't ship the requested mode (DARK_ONLY / LIGHT_ONLY —
 * e.g. AMOLED has no light palette) falls back to the default theme's
 * palette for that mode.
 */
internal fun resolveColorScheme(
    preset: ThemePreset,
    darkTheme: Boolean,
): ColorScheme {
    val theme = themeFor(preset)
    // DefaultTheme is FULL — its scheme is never null for either mode.
    return theme.schemeFor(darkTheme) ?: requireNotNull(DefaultTheme.schemeFor(darkTheme))
}

/**
 * Resolve the semantic status colors for a preset + dark flag.
 * Same fallback rule as the scheme.
 */
internal fun resolveStatusColors(
    preset: ThemePreset,
    darkTheme: Boolean,
): HermesStatusColors {
    val theme = themeFor(preset)
    // DefaultTheme is FULL — its status colors are never null for either mode.
    return theme.statusFor(darkTheme) ?: requireNotNull(DefaultTheme.statusFor(darkTheme))
}

@Composable
fun HermesControlTheme(
    themePreference: ThemePreference = LocalThemePreference.current,
    useDynamicColors: Boolean = true,
    themePreset: ThemePreset = ThemePreset.DEFAULT,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themePreference) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }

    val context = LocalContext.current
    val dynamicAvailable =
        useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme =
        if (dynamicAvailable) {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        } else {
            resolveColorScheme(themePreset, darkTheme)
        }

    val statusColors = resolveStatusColors(themePreset, darkTheme)

    CompositionLocalProvider(
        LocalThemePreference provides themePreference,
        LocalThemePreset provides themePreset,
        LocalHermesStatusColors provides statusColors,
        LocalSpacing provides SpacingDefaults,
        LocalMotion provides MotionDefaults,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = HermesShapes,
            content = content,
        )
    }
}
