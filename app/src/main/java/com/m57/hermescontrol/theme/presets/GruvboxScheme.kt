package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.buildTheme

// ---------------------------------------------------------------------
// Gruvbox Dark (medium contrast) — named swatches
// ---------------------------------------------------------------------

private val GruvboxDarkBg0 = Color(0xFF282828)
private val GruvboxDarkBg1 = Color(0xFF3C3836)
private val GruvboxDarkBg2 = Color(0xFF504945)
private val GruvboxDarkBg3 = Color(0xFF665C54)
private val GruvboxDarkBg4 = Color(0xFF7C6F64)
private val GruvboxDarkFg1 = Color(0xFFEBDBB2)
private val GruvboxDarkFg2 = Color(0xFFD5C4A1)
private val GruvboxDarkFg3 = Color(0xFFBDAE93)
private val GruvboxGray = Color(0xFF928374) // same neutral gray in both modes

// "Bright" accents — the variants gruvbox uses against a dark background.
private val GruvboxBrightRed = Color(0xFFFB4934)
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
private val GruvboxLightBg4 = Color(0xFFA89984)
private val GruvboxLightFg1 = Color(0xFF3C3836)
private val GruvboxLightFg2 = Color(0xFF504945)
private val GruvboxLightFg3 = Color(0xFF665C54)

// "Faded" accents — the muted variants gruvbox uses against a light
// background, so contrast holds up without the neon dark-mode saturation.
private val GruvboxFadedRed = Color(0xFF9D0006)
private val GruvboxFadedGreen = Color(0xFF79740E)
private val GruvboxFadedYellow = Color(0xFFB57614)
private val GruvboxFadedBlue = Color(0xFF076678)
private val GruvboxFadedPurple = Color(0xFF8F3F71)
private val GruvboxFadedOrange = Color(0xFFAF3A03)

/** Gruvbox theme — bright accents on dark bg0–bg4, faded accents on light bg0–bg4. */
val GruvboxTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = GruvboxBrightOrange,
                onPrimary = GruvboxDarkBg0,
                primaryContainer = GruvboxDarkBg4,
                onPrimaryContainer = GruvboxDarkFg1,
                secondary = GruvboxBrightBlue,
                onSecondary = GruvboxDarkBg0,
                secondaryContainer = GruvboxDarkBg3,
                onSecondaryContainer = GruvboxDarkFg2,
                tertiary = GruvboxBrightPurple,
                onTertiary = GruvboxDarkBg0,
                tertiaryContainer = GruvboxDarkBg4,
                onTertiaryContainer = GruvboxDarkFg1,
                background = GruvboxDarkBg0,
                onBackground = GruvboxDarkFg1,
                surface = GruvboxDarkBg0,
                onSurface = GruvboxDarkFg1,
                surfaceVariant = GruvboxDarkBg1,
                onSurfaceVariant = GruvboxDarkFg3,
                surfaceContainerLowest = GruvboxDarkBg0,
                surfaceContainerLow = GruvboxDarkBg1,
                surfaceContainer = GruvboxDarkBg2,
                surfaceContainerHigh = GruvboxDarkBg3,
                surfaceContainerHighest = GruvboxDarkBg4,
                inverseSurface = GruvboxDarkFg1,
                inverseOnSurface = GruvboxDarkBg0,
                inversePrimary = GruvboxFadedOrange,
                outline = GruvboxGray,
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
                        error = GruvboxBrightRed,
                        errorContainer = GruvboxDarkBg3,
                        onError = GruvboxDarkBg0,
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
                primaryContainer = GruvboxLightBg4,
                onPrimaryContainer = GruvboxLightFg1,
                secondary = GruvboxFadedBlue,
                onSecondary = GruvboxLightBg0,
                secondaryContainer = GruvboxLightBg3,
                onSecondaryContainer = GruvboxLightFg2,
                tertiary = GruvboxFadedPurple,
                onTertiary = GruvboxLightBg0,
                tertiaryContainer = GruvboxLightBg4,
                onTertiaryContainer = GruvboxLightFg1,
                background = GruvboxLightBg0,
                onBackground = GruvboxLightFg1,
                surface = GruvboxLightBg0,
                onSurface = GruvboxLightFg1,
                surfaceVariant = GruvboxLightBg1,
                onSurfaceVariant = GruvboxLightFg3,
                surfaceContainerLowest = GruvboxLightBg0,
                surfaceContainerLow = GruvboxLightBg1,
                surfaceContainer = GruvboxLightBg2,
                surfaceContainerHigh = GruvboxLightBg3,
                surfaceContainerHighest = GruvboxLightBg4,
                inverseSurface = GruvboxLightFg1,
                inverseOnSurface = GruvboxLightBg0,
                inversePrimary = GruvboxBrightOrange,
                outline = GruvboxGray,
                outlineVariant = GruvboxLightBg3,
                scrim = GruvboxLightBg0,
                status =
                    HermesStatusColors(
                        success = GruvboxFadedGreen,
                        successContainer = GruvboxLightBg3,
                        onSuccess = GruvboxLightBg0,
                        warning = GruvboxFadedYellow,
                        warningContainer = GruvboxLightBg3,
                        onWarning = GruvboxLightBg0,
                        error = GruvboxFadedRed,
                        errorContainer = GruvboxLightBg3,
                        onError = GruvboxLightBg0,
                        onErrorContainer = GruvboxFadedRed,
                        info = GruvboxFadedBlue,
                        infoContainer = GruvboxLightBg3,
                        onInfo = GruvboxLightBg0,
                    ),
            ),
    )
