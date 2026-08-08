package com.m57.hermescontrol.theme.presets

import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.buildThemeDarkOnly

/**
 * AMOLED theme — pure black, dark only.
 *
 * A light AMOLED makes no sense, so this theme declares [ThemeMode.DARK_ONLY]
 * (via [buildThemeDarkOnly]): in light mode the app falls back to the brand
 * default light theme. Status colors are bespoke neon-on-black so they pop
 * against true black surfaces.
 */
val AmoledTheme =
    buildThemeDarkOnly(
        dark =
            PaletteColors(
                primary = Color(0xFF7C5CFF),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFF1A1040),
                onPrimaryContainer = Color(0xFFD9CCFF),
                secondary = Color(0xFFFFB627),
                onSecondary = Color(0xFF000000),
                secondaryContainer = Color(0xFF2A2000),
                onSecondaryContainer = Color(0xFFFFE082),
                tertiary = Color(0xFFFFE082),
                onTertiary = Color(0xFF000000),
                tertiaryContainer = Color(0xFF2A2000),
                onTertiaryContainer = Color(0xFFFFE082),
                background = Color(0xFF000000),
                onBackground = Color(0xFFE8E6EE),
                surface = Color(0xFF000000),
                onSurface = Color(0xFFE8E6EE),
                surfaceVariant = Color(0xFF121218),
                onSurfaceVariant = Color(0xFFB6B2C4),
                surfaceContainerLowest = Color(0xFF000000),
                surfaceContainerLow = Color(0xFF08080A),
                surfaceContainer = Color(0xFF0E0E12),
                surfaceContainerHigh = Color(0xFF16161C),
                surfaceContainerHighest = Color(0xFF1F1F28),
                inverseSurface = Color(0xFFE8E6EE),
                inverseOnSurface = Color(0xFF000000),
                inversePrimary = Color(0xFF6750A4),
                outline = Color(0xFF3A3A4A),
                outlineVariant = Color(0xFF252532),
                scrim = Color(0xFF000000),
                status =
                    HermesStatusColors(
                        success = Color(0xFF00E676),
                        successContainer = Color(0xFF00331A),
                        onSuccess = Color(0xFFE8E6EE),
                        warning = Color(0xFFFFD740),
                        warningContainer = Color(0xFF332500),
                        onWarning = Color(0xFFE8E6EE),
                        error = Color(0xFFFF5252),
                        errorContainer = Color(0xFF33000A),
                        onError = Color(0xFFFFFFFF),
                        onErrorContainer = Color(0xFFF2B8B5),
                        info = Color(0xFF40C4FF),
                        infoContainer = Color(0xFF002A33),
                        onInfo = Color(0xFFE8E6EE),
                    ),
            ),
    )
