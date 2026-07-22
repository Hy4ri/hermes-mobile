package com.m57.hermescontrol.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors

/** Catppuccin color scheme (dark). */
val CatppuccinDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFCBA6F7),
        onPrimary = Color(0xFF11111B),
        primaryContainer = Color(0xFF585B70),
        onPrimaryContainer = Color(0xFFF5E0DC),
        inversePrimary = Color(0xFF8839EF),
        secondary = Color(0xFF89B4FA),
        onSecondary = Color(0xFF11111B),
        secondaryContainer = Color(0xFF313244),
        onSecondaryContainer = Color(0xFFBAC2DE),
        tertiary = Color(0xFFF5C2E7),
        onTertiary = Color(0xFF11111B),
        tertiaryContainer = Color(0xFF45475A),
        onTertiaryContainer = Color(0xFFF5E0DC),
        background = Color(0xFF1E1E2E),
        onBackground = Color(0xFFCDD6F4),
        surface = Color(0xFF252538),
        onSurface = Color(0xFFCDD6F4),
        surfaceVariant = Color(0xFF313244),
        onSurfaceVariant = Color(0xFFA6ADC8),
        surfaceTint = Color(0xFFCBA6F7),
        surfaceContainerLowest = Color(0xFF11111B),
        surfaceContainerLow = Color(0xFF181825),
        surfaceContainer = Color(0xFF1E1E2E),
        surfaceContainerHigh = Color(0xFF252538),
        surfaceContainerHighest = Color(0xFF313244),
        inverseSurface = Color(0xFFCDD6F4),
        inverseOnSurface = Color(0xFF1E1E2E),
        error = Color(0xFFF38BA8),
        onError = Color(0xFF11111B),
        errorContainer = Color(0xFF45475A),
        onErrorContainer = Color(0xFFF38BA8),
        outline = Color(0xFF585B70),
        outlineVariant = Color(0xFF45475A),
        scrim = Color(0xFF11111B),
    )

/** Catppuccin color scheme (light). */
val CatppuccinLightColorScheme =
    lightColorScheme(
        primary = Color(0xFF8839EF),
        onPrimary = Color(0xFFEFF1F5),
        primaryContainer = Color(0xFFCCD0DA),
        onPrimaryContainer = Color(0xFF4C4F69),
        inversePrimary = Color(0xFFCBA6F7),
        secondary = Color(0xFF1E66F5),
        onSecondary = Color(0xFFEFF1F5),
        secondaryContainer = Color(0xFFE6E9EF),
        onSecondaryContainer = Color(0xFF4C4F69),
        tertiary = Color(0xFFEA76CB),
        onTertiary = Color(0xFFEFF1F5),
        tertiaryContainer = Color(0xFFBCC0CC),
        onTertiaryContainer = Color(0xFF4C4F69),
        background = Color(0xFFEFF1F5),
        onBackground = Color(0xFF4C4F69),
        surface = Color(0xFFE6E9EF),
        onSurface = Color(0xFF4C4F69),
        surfaceVariant = Color(0xFFCCD0DA),
        onSurfaceVariant = Color(0xFF6C6F85),
        surfaceTint = Color(0xFF8839EF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFE6E9EF),
        surfaceContainer = Color(0xFFEFF1F5),
        surfaceContainerHigh = Color(0xFFE6E9EF),
        surfaceContainerHighest = Color(0xFFCCD0DA),
        inverseSurface = Color(0xFF4C4F69),
        inverseOnSurface = Color(0xFFEFF1F5),
        error = Color(0xFFD20F39),
        onError = Color(0xFFEFF1F5),
        errorContainer = Color(0xFFF2D5CF),
        onErrorContainer = Color(0xFFD20F39),
        outline = Color(0xFF9CA0B0),
        outlineVariant = Color(0xFFBCC0CC),
        scrim = Color(0xFFDCE0E8),
    )

/** Catppuccin status colors (dark). */
val CatppuccinDarkStatusColors =
    HermesStatusColors(
        success = Color(0xFFA6E3A1),
        successContainer = Color(0xFF313244),
        onSuccess = Color(0xFF11111B),
        warning = Color(0xFFF9E2AF),
        warningContainer = Color(0xFF313244),
        onWarning = Color(0xFF11111B),
        error = Color(0xFFF38BA8),
        errorContainer = Color(0xFF313244),
        onError = Color(0xFF11111B),
        info = Color(0xFF89B4FA),
        infoContainer = Color(0xFF313244),
        onInfo = Color(0xFF11111B),
    )

/** Catppuccin status colors (light). */
val CatppuccinLightStatusColors =
    HermesStatusColors(
        success = Color(0xFF40A02B),
        successContainer = Color(0xFFE6E9EF),
        onSuccess = Color(0xFFE6E9EF),
        warning = Color(0xFFDF8E1D),
        warningContainer = Color(0xFFE6E9EF),
        onWarning = Color(0xFFE6E9EF),
        error = Color(0xFFD20F39),
        errorContainer = Color(0xFFE6E9EF),
        onError = Color(0xFFE6E9EF),
        info = Color(0xFF1E66F5),
        infoContainer = Color(0xFFE6E9EF),
        onInfo = Color(0xFFE6E9EF),
    )
