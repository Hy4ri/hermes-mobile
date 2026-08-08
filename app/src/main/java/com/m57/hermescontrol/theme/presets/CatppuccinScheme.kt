package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.buildTheme

// ---------------------------------------------------------------------
// Mocha (dark) — named swatches, so repeated hexes (Crust, Surface0/1,
// Base, Text, Blue...) live in exactly one place.
// ---------------------------------------------------------------------

private val MochaMauve = Color(0xFFCBA6F7)
private val MochaBlue = Color(0xFF89B4FA)
private val MochaPink = Color(0xFFF5C2E7)
private val MochaGreen = Color(0xFFA6E3A1)
private val MochaYellow = Color(0xFFF9E2AF)
private val MochaRed = Color(0xFFF38BA8)
private val MochaText = Color(0xFFCDD6F4)
private val MochaSubtext1 = Color(0xFFBAC2DE)
private val MochaSubtext0 = Color(0xFFA6ADC8)
private val MochaOverlay0 = Color(0xFF6C7086)
private val MochaSurface1 = Color(0xFF45475A)
private val MochaSurface0 = Color(0xFF313244)
private val MochaBase = Color(0xFF1E1E2E)
private val MochaMantle = Color(0xFF181825)
private val MochaCrust = Color(0xFF11111B)

// ---------------------------------------------------------------------
// Latte (light) — named swatches
// ---------------------------------------------------------------------

private val LatteMauve = Color(0xFF8839EF)
private val LatteBlue = Color(0xFF1E66F5)
private val LattePink = Color(0xFFEA76CB)
private val LatteGreen = Color(0xFF40A02B)
private val LatteYellow = Color(0xFFDF8E1D)
private val LatteRed = Color(0xFFD20F39)
private val LatteText = Color(0xFF4C4F69)
private val LatteSubtext0 = Color(0xFF6C6F85)
private val LatteOverlay0 = Color(0xFF9CA0B0)
private val LatteSurface1 = Color(0xFFBCC0CC)
private val LatteSurface0 = Color(0xFFCCD0DA)
private val LatteBase = Color(0xFFEFF1F5)
private val LatteMantle = Color(0xFFE6E9EF)
private val LatteCrust = Color(0xFFDCE0E8)

// Bespoke tint used only for the light primary container — not part of
// the official Catppuccin swatch set, kept local to this file.
private val LattePrimaryContainer = Color(0xFFE6CAFA)
private val LatteOnPrimaryContainer = Color(0xFF380075)

/** Catppuccin theme — Mocha (dark) / Latte (light). */
val CatppuccinTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = MochaMauve,
                onPrimary = MochaCrust,
                primaryContainer = MochaSurface1,
                onPrimaryContainer = MochaText,
                secondary = MochaBlue,
                onSecondary = MochaCrust,
                secondaryContainer = MochaSurface0,
                onSecondaryContainer = MochaSubtext1,
                tertiary = MochaPink,
                onTertiary = MochaCrust,
                tertiaryContainer = MochaSurface1,
                onTertiaryContainer = MochaText,
                background = MochaBase,
                onBackground = MochaText,
                surface = MochaBase,
                onSurface = MochaText,
                surfaceVariant = MochaSurface0,
                onSurfaceVariant = MochaSubtext0,
                surfaceContainerLowest = MochaCrust,
                surfaceContainerLow = MochaMantle,
                surfaceContainer = MochaBase,
                surfaceContainerHigh = MochaSurface0,
                surfaceContainerHighest = MochaSurface1,
                inverseSurface = MochaText,
                inverseOnSurface = MochaBase,
                inversePrimary = LatteMauve,
                outline = MochaOverlay0,
                outlineVariant = MochaSurface1,
                scrim = MochaCrust,
                status =
                    HermesStatusColors(
                        success = MochaGreen,
                        successContainer = MochaSurface0,
                        onSuccess = MochaCrust,
                        warning = MochaYellow,
                        warningContainer = MochaSurface0,
                        onWarning = MochaCrust,
                        error = MochaRed,
                        errorContainer = MochaSurface0,
                        onError = MochaCrust,
                        onErrorContainer = MochaRed,
                        info = MochaBlue,
                        infoContainer = MochaSurface0,
                        onInfo = MochaCrust,
                    ),
            ),
        light =
            PaletteColors(
                primary = LatteMauve,
                onPrimary = LatteBase,
                primaryContainer = LattePrimaryContainer,
                onPrimaryContainer = LatteOnPrimaryContainer,
                secondary = LatteBlue,
                onSecondary = LatteBase,
                secondaryContainer = LatteMantle,
                onSecondaryContainer = LatteText,
                tertiary = LattePink,
                onTertiary = LatteBase,
                tertiaryContainer = LatteSurface1,
                onTertiaryContainer = LatteText,
                background = LatteBase,
                onBackground = LatteText,
                surface = LatteBase,
                onSurface = LatteText,
                surfaceVariant = LatteSurface0,
                onSurfaceVariant = LatteSubtext0,
                surfaceContainerLowest = LatteCrust,
                surfaceContainerLow = LatteMantle,
                surfaceContainer = LatteBase,
                surfaceContainerHigh = LatteSurface0,
                surfaceContainerHighest = LatteSurface1,
                inverseSurface = LatteText,
                inverseOnSurface = LatteBase,
                inversePrimary = MochaMauve,
                outline = LatteOverlay0,
                outlineVariant = LatteSurface1,
                scrim = LatteCrust,
                status =
                    HermesStatusColors(
                        success = LatteGreen,
                        successContainer = LatteSurface0,
                        onSuccess = LatteBase,
                        warning = LatteYellow,
                        warningContainer = LatteSurface0,
                        onWarning = LatteBase,
                        error = LatteRed,
                        errorContainer = LatteSurface0,
                        onError = LatteBase,
                        onErrorContainer = LatteRed,
                        info = LatteBlue,
                        infoContainer = LatteSurface0,
                        onInfo = LatteBase,
                    ),
            ),
    )
