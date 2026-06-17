package com.m57.hermescontrol.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemePreference { SYSTEM, LIGHT, DARK }

val LocalThemePreference = compositionLocalOf { ThemePreference.SYSTEM }

/**
 * Set to true to opt into Material You dynamic color (Android 12+).
 * Off by default — brand identity (Voltage Purple) is the priority.
 */
private const val ENABLE_DYNAMIC_COLOR = false

private val HermesDarkColorScheme =
    darkColorScheme(
        primary = HermesPurple,
        onPrimary = Color.White,
        primaryContainer = HermesPurpleContainer,
        onPrimaryContainer = HermesPurpleOnContainer,
        secondary = HermesAmber,
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF3D2F0F),
        onSecondaryContainer = HermesAmberLight,
        tertiary = HermesAmberLight,
        background = DarkBackground,
        onBackground = DarkOnSurface,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        surfaceTint = HermesPurple,
        surfaceContainer = DarkSurfaceVariant,
        surfaceContainerHigh = DarkSurfaceHigh,
        surfaceContainerHighest = DarkSurfaceHigh,
        inverseSurface = Color(0xFFE8E6EE),
        inverseOnSurface = Color(0xFF1A1A24),
        error = StatusRed,
        onError = Color.White,
        errorContainer = StatusRedContainer,
        onErrorContainer = Color(0xFFFFB4B4),
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
    )

private val HermesLightColorScheme =
    lightColorScheme(
        primary = HermesPurpleDark,
        onPrimary = Color.White,
        primaryContainer = HermesPurpleLight,
        onPrimaryContainer = Color(0xFF1E0F66),
        secondary = HermesAmberDark,
        onSecondary = Color.White,
        secondaryContainer = HermesAmberLight,
        onSecondaryContainer = Color(0xFF3D2F0F),
        tertiary = HermesAmberDark,
        background = LightBackground,
        onBackground = LightOnSurface,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        surfaceTint = HermesPurple,
        surfaceContainer = LightSurfaceVariant,
        surfaceContainerHigh = LightSurfaceHigh,
        surfaceContainerHighest = LightSurfaceHigh,
        inverseSurface = Color(0xFF1A1A24),
        inverseOnSurface = Color(0xFFE8E6EE),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
    )

@Composable
fun HermesControlTheme(
    themePreference: ThemePreference = LocalThemePreference.current,
    enableDynamicColor: Boolean = ENABLE_DYNAMIC_COLOR,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themePreference) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }

    val context = LocalContext.current
    val dynamicAvailable = enableDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme =
        when {
            dynamicAvailable && darkTheme -> dynamicDarkColorScheme(context)
            dynamicAvailable && !darkTheme -> dynamicLightColorScheme(context)
            darkTheme -> HermesDarkColorScheme
            else -> HermesLightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = HermesShapes,
        content = content,
    )
}
