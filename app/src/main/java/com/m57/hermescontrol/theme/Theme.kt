package com.m57.hermescontrol.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemePreference { SYSTEM, LIGHT, DARK }

val LocalThemePreference = compositionLocalOf { ThemePreference.SYSTEM }

private val HermesDarkColorScheme =
    darkColorScheme(
        primary = HermesPurple,
        onPrimary = Color.White,
        primaryContainer = HermesPurpleDark,
        onPrimaryContainer = HermesPurple80,
        secondary = HermesAmber,
        onSecondary = Color.Black,
        secondaryContainer = HermesAmberDark,
        onSecondaryContainer = HermesAmber80,
        tertiary = HermesAmberLight,
        background = DarkBackground,
        onBackground = DarkOnSurface,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        error = Color(0xFFCF6679),
        onError = Color.Black,
        outline = Color(0xFF938F99),
    )

private val HermesLightColorScheme =
    lightColorScheme(
        primary = HermesPurple,
        onPrimary = Color.White,
        primaryContainer = HermesPurple80,
        onPrimaryContainer = HermesPurpleDark,
        secondary = HermesAmber,
        onSecondary = Color.Black,
        secondaryContainer = HermesAmber80,
        onSecondaryContainer = HermesAmberDark,
        tertiary = HermesAmberDark,
        background = LightBackground,
        onBackground = LightOnSurface,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        error = Color(0xFFB00020),
        onError = Color.White,
        outline = Color(0xFF79747E),
    )

@Composable
fun HermesControlTheme(
    themePreference: ThemePreference = LocalThemePreference.current,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themePreference) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }

    val colorScheme = if (darkTheme) HermesDarkColorScheme else HermesLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
