package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.buildTheme

// ---------------------------------------------------------------------
// Gruvbox Dark (medium contrast) — named swatches
// ---------------------------------------------------------------------

// Official hard-contrast bg0; medium bg0/red only reaches 4.29:1.
private val GruvboxDarkInk = Color(0xFF1D2021)
private val GruvboxDarkBg0 = Color(0xFF282828)
private val GruvboxDarkBg1 = Color(0xFF3C3836)
private val GruvboxDarkBg2 = Color(0xFF504945)
private val GruvboxDarkBg3 = Color(0xFF665C54)
private val GruvboxDarkFg1 = Color(0xFFEBDBB2)
private val GruvboxDarkFg2 = Color(0xFFD5C4A1)
private val GruvboxDarkFg3 = Color(0xFFBDAE93)

// "Bright" accents — the variants gruvbox uses against a dark background.
// Derived role, not an upstream swatch: bright red/bg0 is only 4.29:1.
// Lighter red reaches 4.92:1 for error text and field labels on bg0.
private val GruvboxErrorText = Color(0xFFFF5F4F)
private val GruvboxBrightGreen = Color(0xFFB8BB26)
private val GruvboxBrightYellow = Color(0xFFFABD2F)
private val GruvboxBrightBlue = Color(0xFF83A598)
private val GruvboxBrightPurple = Color(0xFFD3869B)
private val GruvboxBrightOrange = Color(0xFFFE8019)

// ---------------------------------------------------------------------
// Gruvbox Light (medium contrast) — named swatches
// ---------------------------------------------------------------------

private val GruvboxLightBg0 = Color(0xFFFBF1C7)
private val GruvboxLightBg1 = Color(0xFFEBDBB2)
private val GruvboxLightBg2 = Color(0xFFD5C4A1)
private val GruvboxLightBg3 = Color(0xFFBDAE93)
private val GruvboxLightFg1 = Color(0xFF3C3836)
private val GruvboxLightFg2 = Color(0xFF504945)
private val GruvboxLightFg3 = Color(0xFF665C54)

// "Faded" accents — the muted variants gruvbox uses against a light
// background, so contrast holds up without the neon dark-mode saturation.
private val GruvboxFadedRed = Color(0xFF9D0006)

// Derived roles, not upstream Gruvbox swatches: light bg0/faded green is 4.29:1;
// white/green reaches 4.86:1. Hard dark bg0/faded yellow is below 4.5:1;
// deeper neutral ink/yellow reaches 4.97:1.
private val GruvboxOnFadedGreen = Color(0xFFFFFFFF)
private val GruvboxOnFadedYellow = Color(0xFF121212)
private val GruvboxFadedGreen = Color(0xFF79740E)
private val GruvboxFadedYellow = Color(0xFFB57614)
private val GruvboxFadedBlue = Color(0xFF076678)
private val GruvboxFadedPurple = Color(0xFF8F3F71)
private val GruvboxFadedOrange = Color(0xFFAF3A03)

// Regression theme-contract: fixed roles reuse bright accents in both modes;
// Fixed/Dim share a swatch. Containers stop at bg3 for >= 4.5:1 body text.
// Upstream: https://github.com/morhetz/gruvbox/blob/master/colors/gruvbox.vim

/** Gruvbox theme — bright accents on dark bg0–bg3, faded accents on light bg0–bg3. */
val GruvboxTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = GruvboxBrightOrange,
                onPrimary = GruvboxDarkBg0,
                primaryContainer = GruvboxDarkBg2,
                onPrimaryContainer = GruvboxDarkFg1,
                secondary = GruvboxBrightBlue,
                onSecondary = GruvboxDarkBg0,
                secondaryContainer = GruvboxDarkBg2,
                onSecondaryContainer = GruvboxDarkFg2,
                tertiary = GruvboxBrightPurple,
                onTertiary = GruvboxDarkBg0,
                tertiaryContainer = GruvboxDarkBg2,
                onTertiaryContainer = GruvboxDarkFg1,
                background = GruvboxDarkBg0,
                onBackground = GruvboxDarkFg1,
                surface = GruvboxDarkBg0,
                onSurface = GruvboxDarkFg1,
                surfaceVariant = GruvboxDarkBg1,
                onSurfaceVariant = GruvboxDarkFg3,
                surfaceDim = GruvboxDarkBg0,
                surfaceBright = GruvboxDarkBg3,
                primaryFixed = GruvboxBrightOrange,
                primaryFixedDim = GruvboxBrightOrange,
                onPrimaryFixed = GruvboxDarkBg0,
                onPrimaryFixedVariant = GruvboxDarkBg0,
                secondaryFixed = GruvboxBrightBlue,
                secondaryFixedDim = GruvboxBrightBlue,
                onSecondaryFixed = GruvboxDarkBg0,
                onSecondaryFixedVariant = GruvboxDarkBg0,
                tertiaryFixed = GruvboxBrightPurple,
                tertiaryFixedDim = GruvboxBrightPurple,
                onTertiaryFixed = GruvboxDarkBg0,
                onTertiaryFixedVariant = GruvboxDarkBg0,
                surfaceContainerLowest = GruvboxDarkBg0,
                surfaceContainerLow = GruvboxDarkBg1,
                surfaceContainer = GruvboxDarkBg2,
                surfaceContainerHigh = GruvboxDarkBg3,
                surfaceContainerHighest = GruvboxDarkBg3,
                inverseSurface = GruvboxDarkFg1,
                inverseOnSurface = GruvboxDarkBg0,
                inversePrimary = GruvboxFadedOrange,
                outline = GruvboxDarkFg2,
                outlineVariant = GruvboxDarkBg3,
                scrim = GruvboxDarkBg0,
                status =
                    HermesStatusColors(
                        success = GruvboxBrightGreen,
                        successContainer = GruvboxDarkBg3,
                        onSuccess = GruvboxDarkBg0,
                        warning = GruvboxBrightYellow,
                        warningContainer = GruvboxDarkBg3,
                        onWarning = GruvboxDarkBg0,
                        error = GruvboxErrorText,
                        errorContainer = GruvboxDarkBg3,
                        onError = GruvboxDarkInk,
                        onErrorContainer = GruvboxDarkFg1,
                        info = GruvboxBrightBlue,
                        infoContainer = GruvboxDarkBg3,
                        onInfo = GruvboxDarkBg0,
                    ),
            ),
        light =
            PaletteColors(
                primary = GruvboxFadedOrange,
                onPrimary = GruvboxLightBg0,
                primaryContainer = GruvboxLightBg2,
                onPrimaryContainer = GruvboxLightFg1,
                secondary = GruvboxFadedBlue,
                onSecondary = GruvboxLightBg0,
                secondaryContainer = GruvboxLightBg2,
                onSecondaryContainer = GruvboxLightFg2,
                tertiary = GruvboxFadedPurple,
                onTertiary = GruvboxLightBg0,
                tertiaryContainer = GruvboxLightBg2,
                onTertiaryContainer = GruvboxLightFg1,
                background = GruvboxLightBg0,
                onBackground = GruvboxLightFg1,
                surface = GruvboxLightBg0,
                onSurface = GruvboxLightFg1,
                surfaceVariant = GruvboxLightBg1,
                onSurfaceVariant = GruvboxLightFg3,
                surfaceDim = GruvboxLightBg3,
                surfaceBright = GruvboxLightBg0,
                primaryFixed = GruvboxBrightOrange,
                primaryFixedDim = GruvboxBrightOrange,
                onPrimaryFixed = GruvboxDarkBg0,
                onPrimaryFixedVariant = GruvboxDarkBg0,
                secondaryFixed = GruvboxBrightBlue,
                secondaryFixedDim = GruvboxBrightBlue,
                onSecondaryFixed = GruvboxDarkBg0,
                onSecondaryFixedVariant = GruvboxDarkBg0,
                tertiaryFixed = GruvboxBrightPurple,
                tertiaryFixedDim = GruvboxBrightPurple,
                onTertiaryFixed = GruvboxDarkBg0,
                onTertiaryFixedVariant = GruvboxDarkBg0,
                surfaceContainerLowest = GruvboxLightBg0,
                surfaceContainerLow = GruvboxLightBg1,
                surfaceContainer = GruvboxLightBg2,
                surfaceContainerHigh = GruvboxLightBg3,
                surfaceContainerHighest = GruvboxLightBg3,
                inverseSurface = GruvboxLightFg1,
                inverseOnSurface = GruvboxLightBg0,
                inversePrimary = GruvboxBrightOrange,
                outline = GruvboxLightFg2,
                outlineVariant = GruvboxLightBg3,
                scrim = GruvboxLightBg0,
                status =
                    HermesStatusColors(
                        success = GruvboxFadedGreen,
                        successContainer = GruvboxLightBg3,
                        onSuccess = GruvboxOnFadedGreen,
                        warning = GruvboxFadedYellow,
                        warningContainer = GruvboxLightBg3,
                        onWarning = GruvboxOnFadedYellow,
                        error = GruvboxFadedRed,
                        errorContainer = GruvboxLightBg3,
                        onError = GruvboxLightBg0,
                        onErrorContainer = GruvboxLightFg1,
                        info = GruvboxFadedBlue,
                        infoContainer = GruvboxLightBg3,
                        onInfo = GruvboxLightBg0,
                    ),
            ),
    )
