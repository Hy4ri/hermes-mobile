package com.m57.hermescontrol.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors

/** Monochrome color scheme (dark). */
val MonochromeDarkColorScheme =
    darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF2B2B2B),
        onPrimaryContainer = Color.White,
        inversePrimary = Color(0xFF121212),
        secondary = Color(0xFFB0B0B0),
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF222222),
        onSecondaryContainer = Color(0xFFE0E0E0),
        tertiary = Color(0xFFD0D0D0),
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF333333),
        onTertiaryContainer = Color(0xFFE0E0E0),
        background = Color(0xFF121212),
        onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF1E1E1E),
        onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF2B2B2B),
        onSurfaceVariant = Color(0xFFB0B0B0),
        surfaceTint = Color.White,
        surfaceContainerLowest = Color(0xFF0F0F0F),
        surfaceContainerLow = Color(0xFF1A1A1A),
        surfaceContainer = Color(0xFF1E1E1E),
        surfaceContainerHigh = Color(0xFF252525),
        surfaceContainerHighest = Color(0xFF2C2C2C),
        inverseSurface = Color(0xFFE0E0E0),
        inverseOnSurface = Color(0xFF121212),
        error = Color(0xFFE0E0E0),
        onError = Color.Black,
        errorContainer = Color(0xFF404040),
        onErrorContainer = Color(0xFFE0E0E0),
        outline = Color(0xFF666666),
        outlineVariant = Color(0xFF444444),
        scrim = Color.Black,
    )

/** Monochrome color scheme (light). */
val MonochromeLightColorScheme =
    lightColorScheme(
        primary = Color.Black,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE0E0E0),
        onPrimaryContainer = Color.Black,
        inversePrimary = Color(0xFFE0E0E0),
        secondary = Color(0xFF4A4A4A),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF0F0F0),
        onSecondaryContainer = Color(0xFF111111),
        tertiary = Color(0xFF333333),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFE8E8E8),
        onTertiaryContainer = Color(0xFF111111),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF111111),
        surface = Color(0xFFF5F5F5),
        onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFE0E0E0),
        onSurfaceVariant = Color(0xFF4A4A4A),
        surfaceTint = Color.Black,
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF8F8F8),
        surfaceContainer = Color(0xFFF5F5F5),
        surfaceContainerHigh = Color(0xFFEEEEEE),
        surfaceContainerHighest = Color(0xFFE3E3E3),
        inverseSurface = Color(0xFF111111),
        inverseOnSurface = Color(0xFFE0E0E0),
        error = Color(0xFF111111),
        onError = Color.White,
        errorContainer = Color(0xFFD6D6D6),
        onErrorContainer = Color(0xFF111111),
        outline = Color(0xFF888888),
        outlineVariant = Color(0xFFCCCCCC),
        scrim = Color.Black,
    )

/** Monochrome status colors (dark). */
val MonochromeDarkStatusColors =
    HermesStatusColors(
        success = Color(0xFFE0E0E0),
        successContainer = Color(0xFF2A2A2A),
        onSuccess = Color(0xFF121212),
        warning = Color(0xFFCCCCCC),
        warningContainer = Color(0xFF333333),
        onWarning = Color(0xFF121212),
        error = Color(0xFFFFFFFF),
        errorContainer = Color(0xFF404040),
        onError = Color(0xFF121212),
        info = Color(0xFFB0B0B0),
        infoContainer = Color(0xFF242424),
        onInfo = Color(0xFF121212),
    )

/** Monochrome status colors (light). */
val MonochromeLightStatusColors =
    HermesStatusColors(
        success = Color(0xFF222222),
        successContainer = Color(0xFFE5E5E5),
        onSuccess = Color(0xFFFFFFFF),
        warning = Color(0xFF444444),
        warningContainer = Color(0xFFE0E0E0),
        onWarning = Color(0xFFFFFFFF),
        error = Color(0xFF000000),
        errorContainer = Color(0xFFD6D6D6),
        onError = Color(0xFFFFFFFF),
        info = Color(0xFF555555),
        infoContainer = Color(0xFFEAEAEA),
        onInfo = Color(0xFFFFFFFF),
    )
