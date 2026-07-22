package com.m57.hermescontrol.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors

/** Neon Noir color scheme (dark). */
val NeonNoirDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFFF6BF4),
        onPrimary = Color(0xFF1A001A),
        primaryContainer = Color(0xFF3A003A),
        onPrimaryContainer = Color(0xFFFFCCF2),
        inversePrimary = Color(0xFFC440A8),
        secondary = Color(0xFFA78BFA),
        onSecondary = Color(0xFF0F0B1A),
        secondaryContainer = Color(0xFF2B1F59),
        onSecondaryContainer = Color(0xFFD9CCFF),
        tertiary = Color(0xFF60F0FF),
        onTertiary = Color(0xFF003B40),
        tertiaryContainer = Color(0xFF004F59),
        onTertiaryContainer = Color(0xFFBFFAFF),
        background = Color(0xFF0F0B1A),
        onBackground = Color(0xFFE8E0F0),
        surface = Color(0xFF1A1530),
        onSurface = Color(0xFFE8E0F0),
        surfaceVariant = Color(0xFF2B2540),
        onSurfaceVariant = Color(0xFF9A90B0),
        surfaceTint = Color(0xFFFF6BF4),
        surfaceContainerLowest = Color(0xFF0A0712),
        surfaceContainerLow = Color(0xFF140F22),
        surfaceContainer = Color(0xFF1A1530),
        surfaceContainerHigh = Color(0xFF252040),
        surfaceContainerHighest = Color(0xFF2F2A50),
        inverseSurface = Color(0xFFE8E0F0),
        inverseOnSurface = Color(0xFF0F0B1A),
        error = Color(0xFFFF3D68),
        onError = Color.White,
        errorContainer = Color(0xFF3A0A14),
        onErrorContainer = Color(0xFFFFB3C0),
        outline = Color(0xFF3A3060),
        outlineVariant = Color(0xFF2E2545),
        scrim = Color(0xFF000000),
    )

/** Neon Noir color scheme (light). */
val NeonNoirLightColorScheme =
    lightColorScheme(
        primary = Color(0xFFC440A8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFD9F2),
        onPrimaryContainer = Color(0xFF3A003A),
        inversePrimary = Color(0xFFFF6BF4),
        secondary = Color(0xFF7C4DFF),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFEDE0FF),
        onSecondaryContainer = Color(0xFF1E0066),
        tertiary = Color(0xFF0098A6),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFBFFAFF),
        onTertiaryContainer = Color(0xFF003B40),
        background = Color(0xFFF5F0FA),
        onBackground = Color(0xFF0F0B1A),
        surface = Color.White,
        onSurface = Color(0xFF0F0B1A),
        surfaceVariant = Color(0xFFEDE6F5),
        onSurfaceVariant = Color(0xFF7A7090),
        surfaceTint = Color(0xFFC440A8),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFAF5FF),
        surfaceContainer = Color(0xFFFDF8FF),
        surfaceContainerHigh = Color(0xFFF0EDF5),
        surfaceContainerHighest = Color(0xFFE6E0ED),
        inverseSurface = Color(0xFF0F0B1A),
        inverseOnSurface = Color(0xFFE8E0F0),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        outline = Color(0xFFBFB8CC),
        outlineVariant = Color(0xFFD9D2E3),
        scrim = Color(0xFF000000),
    )

/** Neon Noir status colors (dark). */
val NeonNoirDarkStatusColors =
    HermesStatusColors(
        success = Color(0xFF6FEA8B),
        successContainer = Color(0xFF0A3A14),
        onSuccess = Color(0xFF0F0B1A),
        warning = Color(0xFFFFB347),
        warningContainer = Color(0xFF3A2000),
        onWarning = Color(0xFF0F0B1A),
        error = Color(0xFFFF3D68),
        errorContainer = Color(0xFF3A0A14),
        onError = Color(0xFF0F0B1A),
        info = Color(0xFF60F0FF),
        infoContainer = Color(0xFF003B40),
        onInfo = Color(0xFF0F0B1A),
    )

/** Neon Noir status colors (light). */
val NeonNoirLightStatusColors =
    HermesStatusColors(
        success = Color(0xFF1B873A),
        successContainer = Color(0xFFD7F5E0),
        onSuccess = Color(0xFFFDF8FF),
        warning = Color(0xFFB87800),
        warningContainer = Color(0xFFFFEAB3),
        onWarning = Color(0xFFFDF8FF),
        error = Color(0xFFB3261E),
        errorContainer = Color(0xFFF9DEDC),
        onError = Color(0xFFFDF8FF),
        info = Color(0xFF2E6FBD),
        infoContainer = Color(0xFFD4E7FF),
        onInfo = Color(0xFFFDF8FF),
    )
