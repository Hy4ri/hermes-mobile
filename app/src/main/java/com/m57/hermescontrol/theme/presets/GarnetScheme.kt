package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.buildTheme

// ---------------------------------------------------------------------
// Default slate surfaces retained; accents use the Garnet red family.
// Garnet Dark — named swatches
// ---------------------------------------------------------------------

private val GarnetDarkBg0 = Color(0xFF0F1416)
private val GarnetDarkBg1 = Color(0xFF171C1E)
private val GarnetDarkBg2 = Color(0xFF1B2022)
private val GarnetDarkBg3 = Color(0xFF252B2D)
private val GarnetDarkBg4 = Color(0xFF303638)
private val GarnetDarkFg1 = Color(0xFFDEE3E5)
private val GarnetDarkFg3 = Color(0xFFBEC8CA)
private val GarnetGray = Color(0xFF889294) // same neutral gray in both modes

// "Bright" accents — high-visibility variants against dark surfaces.
private val GarnetBrightCopper = Color(0xFFEABD96)
private val GarnetBrightRose = Color(0xFFE5BDB8)
private val GarnetBrightGreen = Color(0xFF81D692)
private val GarnetBrightYellow = Color(0xFFECC248)
private val GarnetBrightRed = Color(0xFFFFB4AB)
private val GarnetBrightInfo = Color(0xFF8ECEFF)

// ---------------------------------------------------------------------
// Garnet Light — named swatches
// ---------------------------------------------------------------------

private val GarnetLightBg0 = Color(0xFFF5FAFC)
private val GarnetLightBg1 = Color(0xFFEFF4F6)
private val GarnetLightBg2 = Color(0xFFE9EEF0)
private val GarnetLightBg3 = Color(0xFFE3E8EA)
private val GarnetLightBg4 = Color(0xFFDEE3E5)
private val GarnetLightFg1 = Color(0xFF171C1E)
private val GarnetLightFg3 = Color(0xFF3F484A)

// "Faded" accents — muted variants for light background contrast.
private val GarnetAccent = Color(0xFF990000)
private val GarnetFadedCopper = Color(0xFF805531)
private val GarnetFadedRose = Color(0xFF775651)
private val GarnetFadedGreen = Color(0xFF126D2B)
private val GarnetFadedYellow = Color(0xFF725C00)
private val GarnetFadedRed = Color(0xFFBA1A1A)
private val GarnetFadedInfo = Color(0xFF006393)

// #990000 is the seed and light primary. Its contrast on the dark canvas is only
// 2.08:1; #FFB4AB supplies readable dark-mode text/icons (10.93:1).
private val GarnetLightRedContainer = Color(0xFFFFDAD5)
private val GarnetDarkRoseContainer = Color(0xFF573935)
private val GarnetDarkCopperContainer = Color(0xFF634025)
private val GarnetLightCopperContainer = Color(0xFFFFDCC1)

/** Garnet — Default slate surfaces with a #990000 red accent and matching warm tones. */
val GarnetTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = GarnetBrightRed,
                onPrimary = GarnetDarkBg0,
                primaryContainer = GarnetAccent,
                onPrimaryContainer = GarnetLightBg0,
                secondary = GarnetBrightRose,
                onSecondary = GarnetDarkBg0,
                secondaryContainer = GarnetDarkRoseContainer,
                onSecondaryContainer = GarnetBrightRose,
                tertiary = GarnetBrightCopper,
                onTertiary = GarnetDarkBg0,
                tertiaryContainer = GarnetDarkCopperContainer,
                onTertiaryContainer = GarnetBrightCopper,
                background = GarnetDarkBg0,
                onBackground = GarnetDarkFg1,
                surface = GarnetDarkBg0,
                onSurface = GarnetDarkFg1,
                surfaceVariant = GarnetDarkBg1,
                onSurfaceVariant = GarnetDarkFg3,
                surfaceDim = GarnetDarkBg0,
                surfaceBright = GarnetDarkBg4,
                primaryFixed = GarnetBrightRed,
                primaryFixedDim = GarnetBrightRed,
                onPrimaryFixed = GarnetDarkBg0,
                onPrimaryFixedVariant = GarnetDarkBg0,
                secondaryFixed = GarnetBrightRose,
                secondaryFixedDim = GarnetBrightRose,
                onSecondaryFixed = GarnetDarkBg0,
                onSecondaryFixedVariant = GarnetDarkBg0,
                tertiaryFixed = GarnetBrightCopper,
                tertiaryFixedDim = GarnetBrightCopper,
                onTertiaryFixed = GarnetDarkBg0,
                onTertiaryFixedVariant = GarnetDarkBg0,
                surfaceContainerLowest = GarnetDarkBg0,
                surfaceContainerLow = GarnetDarkBg1,
                surfaceContainer = GarnetDarkBg2,
                surfaceContainerHigh = GarnetDarkBg3,
                surfaceContainerHighest = GarnetDarkBg4,
                inverseSurface = GarnetDarkFg1,
                inverseOnSurface = GarnetDarkBg0,
                inversePrimary = GarnetAccent,
                outline = GarnetGray,
                outlineVariant = GarnetDarkBg3,
                scrim = GarnetDarkBg0,
                status =
                    HermesStatusColors(
                        success = GarnetBrightGreen,
                        successContainer = GarnetDarkBg3,
                        onSuccess = GarnetDarkBg0,
                        warning = GarnetBrightYellow,
                        warningContainer = GarnetDarkBg3,
                        onWarning = GarnetDarkBg0,
                        error = GarnetBrightRed,
                        errorContainer = GarnetDarkBg3,
                        onError = GarnetDarkBg0,
                        onErrorContainer = GarnetBrightRed,
                        info = GarnetBrightInfo,
                        infoContainer = GarnetDarkBg3,
                        onInfo = GarnetDarkBg0,
                    ),
            ),
        light =
            PaletteColors(
                primary = GarnetAccent,
                onPrimary = GarnetLightBg0,
                primaryContainer = GarnetLightRedContainer,
                onPrimaryContainer = GarnetAccent,
                secondary = GarnetFadedRose,
                onSecondary = GarnetLightBg0,
                secondaryContainer = GarnetLightRedContainer,
                onSecondaryContainer = GarnetFadedRose,
                tertiary = GarnetFadedCopper,
                onTertiary = GarnetLightBg0,
                tertiaryContainer = GarnetLightCopperContainer,
                onTertiaryContainer = GarnetFadedCopper,
                background = GarnetLightBg0,
                onBackground = GarnetLightFg1,
                surface = GarnetLightBg0,
                onSurface = GarnetLightFg1,
                surfaceVariant = GarnetLightBg1,
                onSurfaceVariant = GarnetLightFg3,
                surfaceDim = GarnetLightBg4,
                surfaceBright = GarnetLightBg0,
                primaryFixed = GarnetBrightRed,
                primaryFixedDim = GarnetBrightRed,
                onPrimaryFixed = GarnetDarkBg0,
                onPrimaryFixedVariant = GarnetDarkBg0,
                secondaryFixed = GarnetBrightRose,
                secondaryFixedDim = GarnetBrightRose,
                onSecondaryFixed = GarnetDarkBg0,
                onSecondaryFixedVariant = GarnetDarkBg0,
                tertiaryFixed = GarnetBrightCopper,
                tertiaryFixedDim = GarnetBrightCopper,
                onTertiaryFixed = GarnetDarkBg0,
                onTertiaryFixedVariant = GarnetDarkBg0,
                surfaceContainerLowest = GarnetLightBg0,
                surfaceContainerLow = GarnetLightBg1,
                surfaceContainer = GarnetLightBg2,
                surfaceContainerHigh = GarnetLightBg3,
                surfaceContainerHighest = GarnetLightBg4,
                inverseSurface = GarnetLightFg1,
                inverseOnSurface = GarnetLightBg0,
                inversePrimary = GarnetBrightRed,
                outline = GarnetLightFg3,
                outlineVariant = GarnetLightBg3,
                scrim = GarnetLightBg0,
                status =
                    HermesStatusColors(
                        success = GarnetFadedGreen,
                        successContainer = GarnetLightBg3,
                        onSuccess = GarnetLightBg0,
                        warning = GarnetFadedYellow,
                        warningContainer = GarnetLightBg3,
                        onWarning = GarnetLightBg0,
                        error = GarnetFadedRed,
                        errorContainer = GarnetLightBg3,
                        onError = GarnetLightBg0,
                        onErrorContainer = GarnetFadedRed,
                        info = GarnetFadedInfo,
                        infoContainer = GarnetLightBg3,
                        onInfo = GarnetLightBg0,
                    ),
            ),
    )
