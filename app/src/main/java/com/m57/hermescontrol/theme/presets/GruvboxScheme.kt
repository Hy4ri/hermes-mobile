package com.m57.hermescontrol.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors

/** Gruvbox color scheme (dark). */
val GruvboxDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFFE8019),
        onPrimary = Color(0xFF282828),
        primaryContainer = Color(0xFFD65D0E),
        onPrimaryContainer = Color(0xFFFBF1C7),
        inversePrimary = Color(0xFFD65D0E),
        secondary = Color(0xFFFABD2F),
        onSecondary = Color(0xFF282828),
        secondaryContainer = Color(0xFF3C3836),
        onSecondaryContainer = Color(0xFFBDAE93),
        tertiary = Color(0xFF8EC07C),
        onTertiary = Color(0xFF282828),
        tertiaryContainer = Color(0xFF458588),
        onTertiaryContainer = Color(0xFFFBF1C7),
        background = Color(0xFF282828),
        onBackground = Color(0xFFFBF1C7),
        surface = Color(0xFF32302F),
        onSurface = Color(0xFFFBF1C7),
        surfaceVariant = Color(0xFF3C3836),
        onSurfaceVariant = Color(0xFFBDAE93),
        surfaceTint = Color(0xFFFE8019),
        surfaceContainerLowest = Color(0xFF1D2021),
        surfaceContainerLow = Color(0xFF282828),
        surfaceContainer = Color(0xFF32302F),
        surfaceContainerHigh = Color(0xFF3C3836),
        surfaceContainerHighest = Color(0xFF504945),
        inverseSurface = Color(0xFFFBF1C7),
        inverseOnSurface = Color(0xFF282828),
        error = Color(0xFFFB4934),
        onError = Color(0xFF282828),
        errorContainer = Color(0xFFCC241D),
        onErrorContainer = Color(0xFFFBF1C7),
        outline = Color(0xFF665C54),
        outlineVariant = Color(0xFF504945),
        scrim = Color(0xFF1D2021),
    )

/** Gruvbox color scheme (light). */
val GruvboxLightColorScheme =
    lightColorScheme(
        primary = Color(0xFFD65D0E),
        onPrimary = Color(0xFFFBF1C7),
        primaryContainer = Color(0xFFFE8019),
        onPrimaryContainer = Color(0xFF282828),
        inversePrimary = Color(0xFFFE8019),
        secondary = Color(0xFF458588),
        onSecondary = Color(0xFFFBF1C7),
        secondaryContainer = Color(0xFFEBDBB2),
        onSecondaryContainer = Color(0xFF3C3836),
        tertiary = Color(0xFF689D6A),
        onTertiary = Color(0xFFFBF1C7),
        tertiaryContainer = Color(0xFFD5C4A1),
        onTertiaryContainer = Color(0xFF282828),
        background = Color(0xFFFBF1C7),
        onBackground = Color(0xFF282828),
        surface = Color(0xFFF2E5BC),
        onSurface = Color(0xFF282828),
        surfaceVariant = Color(0xFFEBDBB2),
        onSurfaceVariant = Color(0xFF7C6F64),
        surfaceTint = Color(0xFFD65D0E),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF9F5D7),
        surfaceContainer = Color(0xFFFBF1C7),
        surfaceContainerHigh = Color(0xFFF2E5BC),
        surfaceContainerHighest = Color(0xFFEBDBB2),
        inverseSurface = Color(0xFF282828),
        inverseOnSurface = Color(0xFFFBF1C7),
        error = Color(0xFFCC241D),
        onError = Color(0xFFFBF1C7),
        errorContainer = Color(0xFFF9F5D7),
        onErrorContainer = Color(0xFF9D0006),
        outline = Color(0xFFA89984),
        outlineVariant = Color(0xFFD5C4A1),
        scrim = Color(0xFF3C3836),
    )

/** Gruvbox status colors (dark). */
val GruvboxDarkStatusColors =
    HermesStatusColors(
        success = Color(0xFFB8BB26),
        successContainer = Color(0xFF32302F),
        onSuccess = Color(0xFF282828),
        warning = Color(0xFFFABD2F),
        warningContainer = Color(0xFF3C3836),
        onWarning = Color(0xFF282828),
        error = Color(0xFFFB4934),
        errorContainer = Color(0xFF282828),
        onError = Color(0xFF282828),
        info = Color(0xFF83A598),
        infoContainer = Color(0xFF3C3836),
        onInfo = Color(0xFF282828),
    )

/** Gruvbox status colors (light). */
val GruvboxLightStatusColors =
    HermesStatusColors(
        success = Color(0xFF98971A),
        successContainer = Color(0xFFEBDBB2),
        onSuccess = Color(0xFFEBDBB2),
        warning = Color(0xFFD79921),
        warningContainer = Color(0xFFEBDBB2),
        onWarning = Color(0xFFEBDBB2),
        error = Color(0xFFCC241D),
        errorContainer = Color(0xFFEBDBB2),
        onError = Color(0xFFEBDBB2),
        info = Color(0xFF458588),
        infoContainer = Color(0xFFEBDBB2),
        onInfo = Color(0xFFEBDBB2),
    )
