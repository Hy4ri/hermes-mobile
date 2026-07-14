package com.m57.hermescontrol.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Monochrome color scheme (dark). */
val MonochromeDarkColorScheme =
    darkColorScheme(
        primary = Color.White,
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF2B2B2B),
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFB0B0B0),
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF222222),
        onSecondaryContainer = Color(0xFFE0E0E0),
        background = Color(0xFF121212),
        onBackground = Color(0xFFE0E0E0),
        surface = Color(0xFF1E1E1E),
        onSurface = Color(0xFFE0E0E0),
        surfaceVariant = Color(0xFF2B2B2B),
        onSurfaceVariant = Color(0xFFB0B0B0),
        outline = Color(0xFF666666),
    )

/** Monochrome color scheme (light). */
val MonochromeLightColorScheme =
    lightColorScheme(
        primary = Color.Black,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE0E0E0),
        onPrimaryContainer = Color.Black,
        secondary = Color(0xFF4A4A4A),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF0F0F0),
        onSecondaryContainer = Color(0xFF111111),
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF111111),
        surface = Color(0xFFF5F5F5),
        onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFE0E0E0),
        onSurfaceVariant = Color(0xFF4A4A4A),
        outline = Color(0xFF888888),
    )
