package com.m57.hermescontrol.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.HermesAmber
import com.m57.hermescontrol.theme.HermesAmberLight
import com.m57.hermescontrol.theme.HermesPurple
import com.m57.hermescontrol.theme.HermesPurpleOnContainer

/**
 * AMOLED color scheme (dark only).
 *
 * AMOLED has no light variant — callers fall back to the brand default light
 * scheme for light mode.
 */
val AmoledDarkColorScheme =
    darkColorScheme(
        primary = HermesPurple,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF1A1040),
        onPrimaryContainer = HermesPurpleOnContainer,
        secondary = HermesAmber,
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF2A2000),
        onSecondaryContainer = HermesAmberLight,
        background = Color.Black,
        onBackground = Color(0xFFE8E6EE),
        surface = Color(0xFF08080A),
        onSurface = Color(0xFFE8E6EE),
        surfaceVariant = Color(0xFF121218),
        onSurfaceVariant = Color(0xFFB6B2C4),
        outline = Color(0xFF3A3A4A),
    )

/**
 * AMOLED has no bespoke light scheme — reuse the brand default light scheme.
 */
val AmoledLightColorScheme = DefaultLightColorScheme

/** AMOLED status colors (dark) — reuses the brand default set. */
val AmoledDarkStatusColors = DefaultDarkStatusColors

/** AMOLED status colors (light) — reuses the brand default set. */
val AmoledLightStatusColors = DefaultLightStatusColors
