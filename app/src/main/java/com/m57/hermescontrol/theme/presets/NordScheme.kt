package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.buildTheme

// ---------------------------------------------------------------------
// Nord — named swatches, official numbering (nord0–nord15).
// Polar Night (0–3, dark neutrals) and Snow Storm (4–6, light neutrals)
// supply the surfaces; Frost (7–10) and Aurora (11–15) supply the
// accents. Unlike Gruvbox, Nord defines one accent set — not a
// separate bright/faded pair per mode — so the same accent swatches
// are reused in both palettes below.
// ---------------------------------------------------------------------

private val Nord0 = Color(0xFF2E3440) // Polar Night — darkest
private val Nord1 = Color(0xFF3B4252)
private val Nord2 = Color(0xFF434C5E)
private val Nord3 = Color(0xFF4C566A)
private val Nord4 = Color(0xFFD8DEE9) // Snow Storm
private val Nord5 = Color(0xFFE5E9F0)
private val Nord6 = Color(0xFFECEFF4) // Snow Storm — lightest
private val Nord8 = Color(0xFF88C0D0) // Frost — primary accent
private val Nord9 = Color(0xFF81A1C1) // Frost — secondary / info
private val Nord11 = Color(0xFFBF616A) // Aurora red — error
private val Nord13 = Color(0xFFEBCB8B) // Aurora yellow — warning
private val Nord14 = Color(0xFFA3BE8C) // Aurora green — success
private val Nord15 = Color(0xFFB48EAD) // Aurora purple — tertiary

/**
 * Nord theme.
 *
 * Nord only ships 4 Polar Night tones and 3 Snow Storm tones, one tone
 * short of the 5-tier surface-container ladder, so the two outermost
 * tiers on each side share a value (surfaceContainerHigh ==
 * surfaceContainerHighest) rather than inventing an off-palette hex.
 *
 * Frost/Aurora accents are pastel by design, so onPrimary/onSecondary/
 * onTertiary/status "on" colors use Polar Night (dark text) in *both*
 * modes — reusing Snow Storm for light-mode "on" text (the convention in
 * the Catppuccin/Gruvbox presets) would fail contrast against colors like
 * Aurora yellow, which stays light even without a separate light-mode
 * variant.
 */
val NordTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = Nord8,
                onPrimary = Nord0,
                primaryContainer = Nord3,
                onPrimaryContainer = Nord6,
                secondary = Nord9,
                onSecondary = Nord0,
                secondaryContainer = Nord2,
                onSecondaryContainer = Nord4,
                tertiary = Nord15,
                onTertiary = Nord0,
                tertiaryContainer = Nord3,
                onTertiaryContainer = Nord6,
                background = Nord0,
                onBackground = Nord6,
                surface = Nord0,
                onSurface = Nord6,
                surfaceVariant = Nord1,
                onSurfaceVariant = Nord4,
                surfaceContainerLowest = Nord0,
                surfaceContainerLow = Nord1,
                surfaceContainer = Nord2,
                surfaceContainerHigh = Nord3,
                surfaceContainerHighest = Nord3,
                inverseSurface = Nord6,
                inverseOnSurface = Nord0,
                inversePrimary = Nord8,
                outline = Nord3,
                outlineVariant = Nord2,
                scrim = Nord0,
                status =
                    HermesStatusColors(
                        success = Nord14,
                        successContainer = Nord2,
                        onSuccess = Nord0,
                        warning = Nord13,
                        warningContainer = Nord2,
                        onWarning = Nord0,
                        error = Nord11,
                        errorContainer = Nord2,
                        onError = Nord0,
                        onErrorContainer = Nord6,
                        info = Nord9,
                        infoContainer = Nord2,
                        onInfo = Nord0,
                    ),
            ),
        light =
            PaletteColors(
                primary = Nord8,
                onPrimary = Nord0,
                primaryContainer = Nord4,
                onPrimaryContainer = Nord0,
                secondary = Nord9,
                onSecondary = Nord0,
                secondaryContainer = Nord5,
                onSecondaryContainer = Nord1,
                tertiary = Nord15,
                onTertiary = Nord0,
                tertiaryContainer = Nord4,
                onTertiaryContainer = Nord0,
                background = Nord6,
                onBackground = Nord0,
                surface = Nord6,
                onSurface = Nord0,
                surfaceVariant = Nord5,
                onSurfaceVariant = Nord2,
                surfaceContainerLowest = Nord6,
                surfaceContainerLow = Nord5,
                surfaceContainer = Nord5,
                surfaceContainerHigh = Nord4,
                surfaceContainerHighest = Nord4,
                inverseSurface = Nord0,
                inverseOnSurface = Nord6,
                inversePrimary = Nord8,
                outline = Nord2,
                outlineVariant = Nord4,
                scrim = Nord6,
                status =
                    HermesStatusColors(
                        success = Nord14,
                        successContainer = Nord5,
                        onSuccess = Nord0,
                        warning = Nord13,
                        warningContainer = Nord5,
                        onWarning = Nord0,
                        error = Nord11,
                        errorContainer = Nord5,
                        onError = Nord0,
                        onErrorContainer = Nord11,
                        info = Nord9,
                        infoContainer = Nord5,
                        onInfo = Nord0,
                    ),
            ),
    )
