package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.StatusColors
import com.m57.hermescontrol.theme.buildTheme

/** Catppuccin theme — Mocha (dark) / Latte (light). */
val CatppuccinTheme =
    buildTheme(
        dark =
            PaletteColors(
                primary = Color(0xFFCBA6F7), // Mauve
                onPrimary = Color(0xFF11111B), // Crust
                primaryContainer = Color(0xFF45475A), // Surface 1
                onPrimaryContainer = Color(0xFFCDD6F4), // Text
                secondary = Color(0xFF89B4FA), // Blue
                onSecondary = Color(0xFF11111B), // Crust
                secondaryContainer = Color(0xFF313244), // Surface 0
                onSecondaryContainer = Color(0xFFBAC2DE), // Subtext 1
                tertiary = Color(0xFFF5C2E7), // Pink
                onTertiary = Color(0xFF11111B), // Crust
                tertiaryContainer = Color(0xFF45475A), // Surface 1
                onTertiaryContainer = Color(0xFFCDD6F4), // Text
                background = Color(0xFF1E1E2E), // Base
                onBackground = Color(0xFFCDD6F4), // Text
                surface = Color(0xFF1E1E2E), // Base
                onSurface = Color(0xFFCDD6F4), // Text
                surfaceVariant = Color(0xFF313244), // Surface 0
                onSurfaceVariant = Color(0xFFA6ADC8), // Subtext 0
                surfaceContainerLowest = Color(0xFF11111B), // Crust
                surfaceContainerLow = Color(0xFF181825), // Mantle
                surfaceContainer = Color(0xFF1E1E2E), // Base
                surfaceContainerHigh = Color(0xFF313244), // Surface 0
                surfaceContainerHighest = Color(0xFF45475A), // Surface 1
                inverseSurface = Color(0xFFCDD6F4), // Text
                inverseOnSurface = Color(0xFF1E1E2E), // Base
                inversePrimary = Color(0xFF8839EF), // Latte Mauve
                outline = Color(0xFF6C7086), // Overlay 0
                outlineVariant = Color(0xFF45475A), // Surface 1
                scrim = Color(0xFF11111B), // Crust
                status =
                    StatusColors(
                        success = Color(0xFFA6E3A1), // Green
                        successContainer = Color(0xFF313244), // Surface 0
                        onSuccess = Color(0xFF11111B), // Crust
                        warning = Color(0xFFF9E2AF), // Yellow
                        warningContainer = Color(0xFF313244), // Surface 0
                        onWarning = Color(0xFF11111B), // Crust
                        error = Color(0xFFF38BA8), // Red
                        errorContainer = Color(0xFF313244), // Surface 0
                        onError = Color(0xFF11111B), // Crust
                        info = Color(0xFF89B4FA), // Blue
                        infoContainer = Color(0xFF313244), // Surface 0
                        onInfo = Color(0xFF11111B), // Crust
                    ),
            ),
        light =
            PaletteColors(
                primary = Color(0xFF8839EF), // Mauve
                onPrimary = Color(0xFFEFF1F5), // Base
                primaryContainer = Color(0xFFE6CAFA), // Light Mauve Container
                onPrimaryContainer = Color(0xFF380075),
                secondary = Color(0xFF1E66F5), // Blue
                onSecondary = Color(0xFFEFF1F5), // Base
                secondaryContainer = Color(0xFFE6E9EF), // Mantle
                onSecondaryContainer = Color(0xFF4C4F69), // Text
                tertiary = Color(0xFFEA76CB), // Pink
                onTertiary = Color(0xFFEFF1F5), // Base
                tertiaryContainer = Color(0xFFBCC0CC), // Surface 1
                onTertiaryContainer = Color(0xFF4C4F69), // Text
                background = Color(0xFFEFF1F5), // Base
                onBackground = Color(0xFF4C4F69), // Text
                surface = Color(0xFFEFF1F5), // Base
                onSurface = Color(0xFF4C4F69), // Text
                surfaceVariant = Color(0xFFCCD0DA), // Surface 0
                onSurfaceVariant = Color(0xFF6C6F85), // Subtext 0
                surfaceContainerLowest = Color(0xFFDCE0E8), // Crust
                surfaceContainerLow = Color(0xFFE6E9EF), // Mantle
                surfaceContainer = Color(0xFFEFF1F5), // Base
                surfaceContainerHigh = Color(0xFFCCD0DA), // Surface 0
                surfaceContainerHighest = Color(0xFFBCC0CC), // Surface 1
                inverseSurface = Color(0xFF4C4F69), // Text
                inverseOnSurface = Color(0xFFEFF1F5), // Base
                inversePrimary = Color(0xFFCBA6F7), // Mocha Mauve
                outline = Color(0xFF9CA0B0), // Overlay 0
                outlineVariant = Color(0xFFBCC0CC), // Surface 1
                scrim = Color(0xFFDCE0E8), // Crust
                status =
                    StatusColors(
                        success = Color(0xFF40A02B), // Green
                        successContainer = Color(0xFFCCD0DA), // Surface 0
                        onSuccess = Color(0xFFEFF1F5), // Base
                        warning = Color(0xFFDF8E1D), // Yellow
                        warningContainer = Color(0xFFCCD0DA), // Surface 0
                        onWarning = Color(0xFFEFF1F5), // Base
                        error = Color(0xFFD20F39), // Red
                        errorContainer = Color(0xFFCCD0DA), // Surface 0
                        onError = Color(0xFFEFF1F5), // Base
                        info = Color(0xFF1E66F5), // Blue
                        infoContainer = Color(0xFFCCD0DA), // Surface 0
                        onInfo = Color(0xFFEFF1F5), // Base
                    ),
            ),
    )
