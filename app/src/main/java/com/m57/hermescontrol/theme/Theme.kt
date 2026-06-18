package com.m57.hermescontrol.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemePreference { SYSTEM, LIGHT, DARK }

val LocalThemePreference = compositionLocalOf { ThemePreference.SYSTEM }

/**
 * Set to true to opt into Material You dynamic color (Android 12+).
 *
 * On API 31+ the app derives its entire colour scheme from the user's
 * wallpaper — primary, secondary, tertiary, surfaces, the lot.  On older
 * devices the [HermesDarkColorScheme] / [HermesLightColorScheme] brand
 * palette is used instead.
 *
 * Semantic status colours (success / warning / error / info) and chat-bubble
 * colours are ALWAYS brand-defined regardless of dynamic colour — see
 * [Color.kt] and the `LocalHermesStatusColors` provider below.
 */
private const val ENABLE_DYNAMIC_COLOR = true

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
        onTertiary = Color.Black,
        background = DarkBackground,
        onBackground = DarkOnSurface,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        surfaceTint = HermesPurple,
        surfaceContainer = DarkSurfaceContainer,
        surfaceContainerHigh = DarkSurfaceContainerHigh,
        surfaceContainerHighest = DarkSurfaceContainerHighest,
        inverseSurface = DarkInverseSurface,
        inverseOnSurface = DarkInverseOnSurface,
        error = StatusRed,
        onError = Color.White,
        errorContainer = StatusRedContainer,
        onErrorContainer = Color(0xFFFFB4B4),
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
        scrim = DarkScrim,
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
        onTertiary = Color.White,
        background = LightBackground,
        onBackground = LightOnSurface,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        surfaceTint = HermesPurple,
        surfaceContainer = LightSurfaceContainer,
        surfaceContainerHigh = LightSurfaceContainerHigh,
        surfaceContainerHighest = LightSurfaceContainerHighest,
        inverseSurface = LightInverseSurface,
        inverseOnSurface = LightInverseOnSurface,
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        scrim = LightScrim,
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

    val statusColors =
        if (darkTheme) {
            HermesStatusColors(
                success = StatusGreen,
                successContainer = StatusGreenContainer,
                warning = StatusYellow,
                warningContainer = StatusYellowContainer,
                error = StatusRed,
                errorContainer = StatusRedContainer,
                info = StatusBlue,
                infoContainer = StatusBlueContainer,
            )
        } else {
            HermesStatusColors(
                success = Color(0xFF1B873A),
                successContainer = Color(0xFFD7F5E0),
                warning = HermesAmberDark,
                warningContainer = Color(0xFFFFEAB3),
                error = Color(0xFFB3261E),
                errorContainer = Color(0xFFF9DEDC),
                info = Color(0xFF2E6FBD),
                infoContainer = Color(0xFFD4E7FF),
            )
        }

    CompositionLocalProvider(
        LocalHermesStatusColors provides statusColors,
        LocalSpacing provides SpacingDefaults,
        LocalMotion provides MotionDefaults,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = HermesShapes,
            content = content,
        )
    }
}
