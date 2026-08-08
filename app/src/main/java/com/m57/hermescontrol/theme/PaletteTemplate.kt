package com.m57.hermescontrol.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Theme template — the ONE shape every preset follows.
 *
 * A theme preset is a single [ThemePalette] built from raw [PaletteColors]
 * specs. Every preset file has the same skeleton: fill in the colors, done.
 * The mapping from raw colors to the Material 3 [ColorScheme] slots and the
 * [HermesStatusColors] model lives here — no per-preset behavior.
 *
 * A theme can declare which modes it ships via [ThemeMode]:
 * - [ThemeMode.FULL] — both dark and light palettes.
 * - [ThemeMode.DARK_ONLY] — dark palette only; the dispatcher falls back to
 *   the default light palette in light mode (e.g. AMOLED — a light AMOLED
 *   makes no sense).
 * - [ThemeMode.LIGHT_ONLY] — light palette only; symmetric fallback.
 */

enum class ThemeMode { FULL, DARK_ONLY, LIGHT_ONLY }

/**
 * Raw color spec for ONE mode (dark or light) of a theme.
 *
 * The Material error slots are derived from [status] — the theme author
 * defines semantic colors once and the scheme inherits them.
 */
data class PaletteColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val status: StatusColors,
)

/** Semantic status colors for ONE mode. */
data class StatusColors(
    val success: Color,
    val successContainer: Color,
    val onSuccess: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarning: Color,
    val error: Color,
    val errorContainer: Color,
    val onError: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfo: Color,
    val neutral: Color = StatusGrey,
)

/** A complete theme — one per preset file, same shape everywhere. */
data class ThemePalette(
    val mode: ThemeMode,
    val dark: PaletteColors?,
    val light: PaletteColors?,
) {
    init {
        require(mode != ThemeMode.FULL || (dark != null && light != null)) {
            "FULL theme must provide both dark and light palettes"
        }
        require(mode != ThemeMode.DARK_ONLY || (dark != null && light == null)) {
            "DARK_ONLY theme must provide a dark palette and no light palette"
        }
        require(mode != ThemeMode.LIGHT_ONLY || (dark == null && light != null)) {
            "LIGHT_ONLY theme must provide a light palette and no dark palette"
        }
    }

    val darkScheme: ColorScheme? get() = dark?.let(::darkSchemeOf)
    val lightScheme: ColorScheme? get() = light?.let(::lightSchemeOf)
    val darkStatus: HermesStatusColors? get() = dark?.let(::statusColorsOf)
    val lightStatus: HermesStatusColors? get() = light?.let(::statusColorsOf)
}

/** Full theme: bespoke dark + light palettes. */
fun buildTheme(
    dark: PaletteColors,
    light: PaletteColors,
): ThemePalette = ThemePalette(ThemeMode.FULL, dark, light)

/** Dark-only theme (e.g. AMOLED). Light mode falls back to the default theme. */
fun buildThemeDarkOnly(dark: PaletteColors): ThemePalette = ThemePalette(ThemeMode.DARK_ONLY, dark, null)

/** Light-only theme. Dark mode falls back to the default theme. */
fun buildThemeLightOnly(light: PaletteColors): ThemePalette = ThemePalette(ThemeMode.LIGHT_ONLY, null, light)

private fun darkSchemeOf(c: PaletteColors): ColorScheme =
    darkColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer,
        onPrimaryContainer = c.onPrimaryContainer,
        secondary = c.secondary,
        onSecondary = c.onSecondary,
        secondaryContainer = c.secondaryContainer,
        onSecondaryContainer = c.onSecondaryContainer,
        tertiary = c.tertiary,
        onTertiary = c.onTertiary,
        tertiaryContainer = c.tertiaryContainer,
        onTertiaryContainer = c.onTertiaryContainer,
        background = c.background,
        onBackground = c.onBackground,
        surface = c.surface,
        onSurface = c.onSurface,
        surfaceVariant = c.surfaceVariant,
        onSurfaceVariant = c.onSurfaceVariant,
        surfaceTint = c.primary,
        surfaceContainerLowest = c.surfaceContainerLowest,
        surfaceContainerLow = c.surfaceContainerLow,
        surfaceContainer = c.surfaceContainer,
        surfaceContainerHigh = c.surfaceContainerHigh,
        surfaceContainerHighest = c.surfaceContainerHighest,
        inverseSurface = c.inverseSurface,
        inverseOnSurface = c.inverseOnSurface,
        inversePrimary = c.inversePrimary,
        error = c.status.error,
        onError = c.status.onError,
        errorContainer = c.status.errorContainer,
        onErrorContainer = c.status.onError,
        outline = c.outline,
        outlineVariant = c.outlineVariant,
        scrim = c.scrim,
    )

private fun lightSchemeOf(c: PaletteColors): ColorScheme =
    lightColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.primaryContainer,
        onPrimaryContainer = c.onPrimaryContainer,
        secondary = c.secondary,
        onSecondary = c.onSecondary,
        secondaryContainer = c.secondaryContainer,
        onSecondaryContainer = c.onSecondaryContainer,
        tertiary = c.tertiary,
        onTertiary = c.onTertiary,
        tertiaryContainer = c.tertiaryContainer,
        onTertiaryContainer = c.onTertiaryContainer,
        background = c.background,
        onBackground = c.onBackground,
        surface = c.surface,
        onSurface = c.onSurface,
        surfaceVariant = c.surfaceVariant,
        onSurfaceVariant = c.onSurfaceVariant,
        surfaceTint = c.primary,
        surfaceContainerLowest = c.surfaceContainerLowest,
        surfaceContainerLow = c.surfaceContainerLow,
        surfaceContainer = c.surfaceContainer,
        surfaceContainerHigh = c.surfaceContainerHigh,
        surfaceContainerHighest = c.surfaceContainerHighest,
        inverseSurface = c.inverseSurface,
        inverseOnSurface = c.inverseOnSurface,
        inversePrimary = c.inversePrimary,
        error = c.status.error,
        onError = c.status.onError,
        errorContainer = c.status.errorContainer,
        onErrorContainer = c.status.onError,
        outline = c.outline,
        outlineVariant = c.outlineVariant,
        scrim = c.scrim,
    )

private fun statusColorsOf(c: PaletteColors): HermesStatusColors =
    HermesStatusColors(
        success = c.status.success,
        successContainer = c.status.successContainer,
        onSuccess = c.status.onSuccess,
        warning = c.status.warning,
        warningContainer = c.status.warningContainer,
        onWarning = c.status.onWarning,
        error = c.status.error,
        errorContainer = c.status.errorContainer,
        onError = c.status.onError,
        info = c.status.info,
        infoContainer = c.status.infoContainer,
        onInfo = c.status.onInfo,
        neutral = c.status.neutral,
    )
