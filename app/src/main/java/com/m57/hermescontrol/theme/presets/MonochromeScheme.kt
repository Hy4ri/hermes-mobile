package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.buildTheme

// ---------------------------------------------------------------------
// Monochrome Dark — every swatch is R=G=B; roles are distinguished
// purely by lightness, not hue.
// ---------------------------------------------------------------------

private val MonoDarkFloor = Color(0xFF0A0A0A) // surfaceContainerLowest
private val MonoDarkBg = Color(0xFF121212) // background, surface, containerLow, all "on*" ink
private val MonoDarkContainer = Color(0xFF1E1E1E) // surfaceContainer
private val MonoDarkContainerHigh = Color(0xFF2A2A2A) // surfaceContainerHigh, surfaceVariant, status containers
private val MonoDarkContainerHighest = Color(0xFF363636) // surfaceContainerHighest, primaryContainer
private val MonoDarkOutline = Color(0xFF808080)
private val MonoDarkOutlineVariant = Color(0xFF404040)
private val MonoDarkTextDim = Color(0xFFB3B3B3) // onSurfaceVariant
private val MonoDarkTextBright = Color(0xFFF2F2F2) // onBackground/onSurface/inverseSurface/primary/error
private val MonoDarkSecondary = Color(0xFFCCCCCC) // secondary accent + warning
private val MonoDarkTertiary = Color(0xFF999999) // tertiary accent + success
private val MonoDarkInfo = Color(0xFF808080) // info — same value as outline, distinct role

// ---------------------------------------------------------------------
// Monochrome Light — the same ramp, inverted
// ---------------------------------------------------------------------

private val MonoLightCeiling = Color(0xFFFFFFFF) // surfaceContainerLowest
private val MonoLightBg = Color(0xFFFAFAFA) // background, surface, containerLow, all "on*" ink
private val MonoLightContainer = Color(0xFFF0F0F0) // surfaceContainer
private val MonoLightContainerHigh = Color(0xFFE0E0E0) // surfaceContainerHigh, surfaceVariant, status containers
private val MonoLightContainerHighest = Color(0xFFD1D1D1) // surfaceContainerHighest, primaryContainer
private val MonoLightOutline = Color(0xFF808080)
private val MonoLightOutlineVariant = Color(0xFFCCCCCC)
private val MonoLightTextDim = Color(0xFF4D4D4D) // onSurfaceVariant
private val MonoLightTextBright = Color(0xFF121212) // onBackground/onSurface/inverseSurface/primary/error
private val MonoLightSecondary = Color(0xFF333333) // secondary accent + warning
private val MonoLightTertiary = Color(0xFF666666) // tertiary accent + success
private val MonoLightInfo = Color(0xFF808080) // info — same value as outline, distinct role

/**
 * Monochrome theme — pure grayscale, no hue in any slot including status
 * colors. `success`/`warning`/`error`/`info` are separated only by
 * lightness (darkest/most emphasized = error, lightest/most muted = info),
 * so anything that leans on this theme for status meaning should not rely
 * on color alone — pair it with an icon, label, or weight change. This is
 * the one real accessibility trade-off of going fully monochrome; every
 * other preset keeps status colors hued specifically to avoid it.
 */
val MonochromeTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = MonoDarkTextBright,
                onPrimary = MonoDarkBg,
                primaryContainer = MonoDarkContainerHighest,
                onPrimaryContainer = MonoDarkTextBright,
                secondary = MonoDarkSecondary,
                onSecondary = MonoDarkBg,
                secondaryContainer = MonoDarkContainerHigh,
                onSecondaryContainer = MonoDarkSecondary,
                tertiary = MonoDarkTertiary,
                onTertiary = MonoDarkBg,
                tertiaryContainer = MonoDarkContainerHigh,
                onTertiaryContainer = MonoDarkTertiary,
                background = MonoDarkBg,
                onBackground = MonoDarkTextBright,
                surface = MonoDarkBg,
                onSurface = MonoDarkTextBright,
                surfaceVariant = MonoDarkContainerHigh,
                onSurfaceVariant = MonoDarkTextDim,
                surfaceContainerLowest = MonoDarkFloor,
                surfaceContainerLow = MonoDarkBg,
                surfaceContainer = MonoDarkContainer,
                surfaceContainerHigh = MonoDarkContainerHigh,
                surfaceContainerHighest = MonoDarkContainerHighest,
                inverseSurface = MonoDarkTextBright,
                inverseOnSurface = MonoDarkBg,
                inversePrimary = MonoLightTextBright,
                outline = MonoDarkOutline,
                outlineVariant = MonoDarkOutlineVariant,
                scrim = Color.Black,
                status =
                    HermesStatusColors(
                        success = MonoDarkTertiary,
                        successContainer = MonoDarkContainerHigh,
                        onSuccess = MonoDarkBg,
                        warning = MonoDarkSecondary,
                        warningContainer = MonoDarkContainerHigh,
                        onWarning = MonoDarkBg,
                        error = MonoDarkTextBright,
                        errorContainer = MonoDarkContainerHigh,
                        onError = MonoDarkBg,
                        onErrorContainer = MonoDarkTextBright,
                        info = MonoDarkInfo,
                        infoContainer = MonoDarkContainerHigh,
                        onInfo = MonoDarkBg,
                    ),
            ),
        light =
            PaletteColors(
                primary = MonoLightTextBright,
                onPrimary = MonoLightBg,
                primaryContainer = MonoLightContainerHighest,
                onPrimaryContainer = MonoLightTextBright,
                secondary = MonoLightSecondary,
                onSecondary = MonoLightBg,
                secondaryContainer = MonoLightContainerHigh,
                onSecondaryContainer = MonoLightSecondary,
                tertiary = MonoLightTertiary,
                onTertiary = MonoLightBg,
                tertiaryContainer = MonoLightContainerHigh,
                onTertiaryContainer = MonoLightTertiary,
                background = MonoLightBg,
                onBackground = MonoLightTextBright,
                surface = MonoLightBg,
                onSurface = MonoLightTextBright,
                surfaceVariant = MonoLightContainerHigh,
                onSurfaceVariant = MonoLightTextDim,
                surfaceContainerLowest = MonoLightCeiling,
                surfaceContainerLow = MonoLightBg,
                surfaceContainer = MonoLightContainer,
                surfaceContainerHigh = MonoLightContainerHigh,
                surfaceContainerHighest = MonoLightContainerHighest,
                inverseSurface = MonoLightTextBright,
                inverseOnSurface = MonoLightBg,
                inversePrimary = MonoDarkTextBright,
                outline = MonoLightOutline,
                outlineVariant = MonoLightOutlineVariant,
                scrim = Color.Black,
                status =
                    HermesStatusColors(
                        success = MonoLightTertiary,
                        successContainer = MonoLightContainerHigh,
                        onSuccess = MonoLightBg,
                        warning = MonoLightSecondary,
                        warningContainer = MonoLightContainerHigh,
                        onWarning = MonoLightBg,
                        error = MonoLightTextBright,
                        errorContainer = MonoLightContainerHigh,
                        onError = MonoLightBg,
                        onErrorContainer = MonoLightTextBright,
                        info = MonoLightInfo,
                        infoContainer = MonoLightContainerHigh,
                        onInfo = MonoLightBg,
                    ),
            ),
    )
