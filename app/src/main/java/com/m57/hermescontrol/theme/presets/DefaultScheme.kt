package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.buildTheme

// ---------------------------------------------------------------------
// Slate Dark — named swatches
// ---------------------------------------------------------------------

private val SlateDarkBg0 = Color(0xFF0F1416)
private val SlateDarkBg1 = Color(0xFF171C1E)
private val SlateDarkBg2 = Color(0xFF1B2022)
private val SlateDarkBg3 = Color(0xFF252B2D)
private val SlateDarkBg4 = Color(0xFF303638)
private val SlateDarkFg1 = Color(0xFFDEE3E5)
private val SlateDarkFg2 = Color(0xFFCEE7EC)
private val SlateDarkFg3 = Color(0xFFBEC8CA)
private val SlateGray = Color(0xFF889294) // same neutral gray in both modes

// "Bright" accents — high-visibility variants against dark surfaces.
private val SlateBrightTeal = Color(0xFF80D5D2)
private val SlateBrightBlue = Color(0xFFA3C9EC)
private val SlateBrightCyan = Color(0xFFB2CBD0)
private val SlateBrightGreen = Color(0xFF81D692)
private val SlateBrightYellow = Color(0xFFECC248)
private val SlateBrightRed = Color(0xFFFFB4AB)
private val SlateBrightInfo = Color(0xFF8ECEFF)

// ---------------------------------------------------------------------
// Slate Light — named swatches
// ---------------------------------------------------------------------

private val SlateLightBg0 = Color(0xFFF5FAFC)
private val SlateLightBg1 = Color(0xFFEFF4F6)
private val SlateLightBg2 = Color(0xFFE9EEF0)
private val SlateLightBg3 = Color(0xFFE3E8EA)
private val SlateLightBg4 = Color(0xFFDEE3E5)
private val SlateLightFg1 = Color(0xFF171C1E)
private val SlateLightFg2 = Color(0xFF051F23)
private val SlateLightFg3 = Color(0xFF3F484A)

// "Faded" accents — muted variants for light background contrast.
private val SlateFadedTeal = Color(0xFF006A68)
private val SlateFadedBlue = Color(0xFF3B6184)
private val SlateFadedCyan = Color(0xFF4A6367)
private val SlateFadedGreen = Color(0xFF126D2B)
private val SlateFadedYellow = Color(0xFF725C00)
private val SlateFadedRed = Color(0xFFBA1A1A)
private val SlateFadedInfo = Color(0xFF006393)

/** Default theme (Slate) — modern slate blues with teal accent notes. */
val DefaultTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = SlateBrightTeal,
                onPrimary = SlateDarkBg0,
                primaryContainer = SlateDarkBg4,
                onPrimaryContainer = SlateDarkFg1,
                secondary = SlateBrightCyan,
                onSecondary = SlateDarkBg0,
                secondaryContainer = SlateDarkBg3,
                onSecondaryContainer = SlateDarkFg2,
                tertiary = SlateBrightBlue,
                onTertiary = SlateDarkBg0,
                tertiaryContainer = SlateDarkBg4,
                onTertiaryContainer = SlateDarkFg1,
                background = SlateDarkBg0,
                onBackground = SlateDarkFg1,
                surface = SlateDarkBg0,
                onSurface = SlateDarkFg1,
                surfaceVariant = SlateDarkBg1,
                onSurfaceVariant = SlateDarkFg3,
                surfaceContainerLowest = SlateDarkBg0,
                surfaceContainerLow = SlateDarkBg1,
                surfaceContainer = SlateDarkBg2,
                surfaceContainerHigh = SlateDarkBg3,
                surfaceContainerHighest = SlateDarkBg4,
                inverseSurface = SlateDarkFg1,
                inverseOnSurface = SlateDarkBg0,
                inversePrimary = SlateFadedTeal,
                outline = SlateGray,
                outlineVariant = SlateDarkBg3,
                scrim = SlateDarkBg0,
                status =
                    HermesStatusColors(
                        success = SlateBrightGreen,
                        successContainer = SlateDarkBg3,
                        onSuccess = SlateDarkBg0,
                        warning = SlateBrightYellow,
                        warningContainer = SlateDarkBg3,
                        onWarning = SlateDarkBg0,
                        error = SlateBrightRed,
                        errorContainer = SlateDarkBg3,
                        onError = SlateDarkBg0,
                        onErrorContainer = SlateBrightRed,
                        info = SlateBrightInfo,
                        infoContainer = SlateDarkBg3,
                        onInfo = SlateDarkBg0,
                    ),
            ),
        light =
            PaletteColors(
                primary = SlateFadedTeal,
                onPrimary = SlateLightBg0,
                primaryContainer = SlateLightBg4,
                onPrimaryContainer = SlateLightFg1,
                secondary = SlateFadedCyan,
                onSecondary = SlateLightBg0,
                secondaryContainer = SlateLightBg3,
                onSecondaryContainer = SlateLightFg2,
                tertiary = SlateFadedBlue,
                onTertiary = SlateLightBg0,
                tertiaryContainer = SlateLightBg4,
                onTertiaryContainer = SlateLightFg1,
                background = SlateLightBg0,
                onBackground = SlateLightFg1,
                surface = SlateLightBg0,
                onSurface = SlateLightFg1,
                surfaceVariant = SlateLightBg1,
                onSurfaceVariant = SlateLightFg3,
                surfaceContainerLowest = SlateLightBg0,
                surfaceContainerLow = SlateLightBg1,
                surfaceContainer = SlateLightBg2,
                surfaceContainerHigh = SlateLightBg3,
                surfaceContainerHighest = SlateLightBg4,
                inverseSurface = SlateLightFg1,
                inverseOnSurface = SlateLightBg0,
                inversePrimary = SlateBrightTeal,
                outline = SlateGray,
                outlineVariant = SlateLightBg3,
                scrim = SlateLightBg0,
                status =
                    HermesStatusColors(
                        success = SlateFadedGreen,
                        successContainer = SlateLightBg3,
                        onSuccess = SlateLightBg0,
                        warning = SlateFadedYellow,
                        warningContainer = SlateLightBg3,
                        onWarning = SlateLightBg0,
                        error = SlateFadedRed,
                        errorContainer = SlateLightBg3,
                        onError = SlateLightBg0,
                        onErrorContainer = SlateFadedRed,
                        info = SlateFadedInfo,
                        infoContainer = SlateLightBg3,
                        onInfo = SlateLightBg0,
                    ),
            ),
    )
