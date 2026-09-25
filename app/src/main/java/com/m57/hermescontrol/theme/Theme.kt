package com.m57.hermescontrol.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.m57.hermescontrol.theme.presets.AmoledTheme
import com.m57.hermescontrol.theme.presets.CatppuccinTheme
import com.m57.hermescontrol.theme.presets.DefaultTheme
import com.m57.hermescontrol.theme.presets.GruvboxTheme
import com.m57.hermescontrol.theme.presets.MonochromeTheme
import com.m57.hermescontrol.theme.presets.NordTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class ThemePreference { SYSTEM, LIGHT, DARK }

@Serializable
enum class ThemePreset { DEFAULT, MONOCHROME, GRUVBOX, CATPPUCCIN, AMOLED, NORD, CUSTOM }

val LocalThemePreference = compositionLocalOf { ThemePreference.SYSTEM }
val LocalThemePreset = compositionLocalOf { ThemePreset.DEFAULT }
val LocalChatFontScale = compositionLocalOf { 1.0f }
val LocalFontFamily = compositionLocalOf { AppFontFamily.SYSTEM.toFontFamily }

/**
 * Custom theme palette applied from the marketplace (t_f3c6f528).
 * Published by `ThemeApplier`; null when no custom theme is applied.
 * Observed by [HermesControlTheme] so applying a theme recomposes live.
 */
private val _customPaletteFlow = MutableStateFlow<ThemePalette?>(null)
val customPaletteFlow: StateFlow<ThemePalette?> = _customPaletteFlow.asStateFlow()

fun setCustomPalette(palette: ThemePalette?) {
    _customPaletteFlow.value = palette
}

/**
 * The 7 preset themes — one file each, all built from the same
 * [PaletteTemplate] shape (see `PaletteTemplate.kt`). CUSTOM resolves to the
 * applied marketplace palette and falls back to Default when none is set
 * (e.g. preset persisted but tokens failed to restore) — never crashes.
 */
private fun themeFor(preset: ThemePreset, custom: ThemePalette? = _customPaletteFlow.value): ThemePalette =
    when (preset) {
        ThemePreset.DEFAULT -> DefaultTheme
        ThemePreset.MONOCHROME -> MonochromeTheme
        ThemePreset.GRUVBOX -> GruvboxTheme
        ThemePreset.CATPPUCCIN -> CatppuccinTheme
        ThemePreset.AMOLED -> AmoledTheme
        ThemePreset.NORD -> NordTheme
        ThemePreset.CUSTOM -> custom ?: DefaultTheme
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
    custom: ThemePalette? = null,
): ColorScheme {
    val theme = themeFor(preset, custom)
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
    custom: ThemePalette? = null,
): HermesStatusColors {
    val theme = themeFor(preset, custom)
    // DefaultTheme is FULL — its status colors are never null for either mode.
    return theme.statusFor(darkTheme) ?: requireNotNull(DefaultTheme.statusFor(darkTheme))
}

@Composable
fun HermesControlTheme(
    themePreference: ThemePreference = LocalThemePreference.current,
    useDynamicColors: Boolean = false,
    themePreset: ThemePreset = ThemePreset.DEFAULT,
    chatFontScale: Float = LocalChatFontScale.current,
    fontFamily: FontFamily = LocalFontFamily.current,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themePreference) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }

    val context = LocalContext.current
    val customPalette by customPaletteFlow.collectAsState()
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
            resolveColorScheme(themePreset, darkTheme, customPalette)
        }

    val statusColors = resolveStatusColors(themePreset, darkTheme, customPalette)

    val typography = createTypography(fontFamily)

    CompositionLocalProvider(
        LocalThemePreference provides themePreference,
        LocalThemePreset provides themePreset,
        LocalChatFontScale provides chatFontScale,
        LocalFontFamily provides fontFamily,
        LocalHermesStatusColors provides statusColors,
        LocalSpacing provides SpacingDefaults,
        LocalMotion provides MotionDefaults,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = HermesShapes,
            content = content,
        )
    }
}
