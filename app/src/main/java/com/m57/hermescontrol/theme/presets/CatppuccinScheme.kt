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
        secondary = Color(0xFF89B4FA),
        onSecondary = Color(0xFF11111B),
        secondaryContainer = Color(0xFF313244),
        onSecondaryContainer = Color(0xFFBAC2DE),
        background = Color(0xFF1E1E2E),
        onBackground = Color(0xFFCDD6F4),
        surface = Color(0xFF252538),
        onSurface = Color(0xFFCDD6F4),
        surfaceVariant = Color(0xFF313244),
        onSurfaceVariant = Color(0xFFA6ADC8),
        outline = Color(0xFF585B70),
    )

/** Catppuccin color scheme (light). */
val CatppuccinLightColorScheme =
    lightColorScheme(
        primary = Color(0xFF8839EF),
        onPrimary = Color(0xFFEFF1F5),
        primaryContainer = Color(0xFFCCD0DA),
        onPrimaryContainer = Color(0xFF4C4F69),
        secondary = Color(0xFF1E66F5),
        onSecondary = Color(0xFFEFF1F5),
        secondaryContainer = Color(0xFFE6E9EF),
        onSecondaryContainer = Color(0xFF4C4F69),
        background = Color(0xFFEFF1F5),
        onBackground = Color(0xFF4C4F69),
        surface = Color(0xFFE6E9EF),
        onSurface = Color(0xFF4C4F69),
        surfaceVariant = Color(0xFFCCD0DA),
        onSurfaceVariant = Color(0xFF6C6F85),
        outline = Color(0xFF9CA0B0),
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
