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
import kotlinx.serialization.Serializable

@Serializable
enum class ThemePreference { SYSTEM, LIGHT, DARK }

@Serializable
enum class ThemePreset { DEFAULT, MONOCHROME, GRUVBOX, CATPPUCCIN, AMOLED, NEON_NOIR }

@Serializable
enum class BottomNavDisplayMode { ICON_AND_TEXT, ICON_ONLY, TEXT_ONLY }

val LocalThemePreference = compositionLocalOf { ThemePreference.SYSTEM }
val LocalThemePreset = compositionLocalOf { ThemePreset.DEFAULT }

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

private val MonochromeDarkColorScheme =
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

private val MonochromeLightColorScheme =
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

private val GruvboxDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFFE8019),
        onPrimary = Color(0xFF282828),
        primaryContainer = Color(0xFFD65D0E),
        onPrimaryContainer = Color(0xFFFBF1C7),
        secondary = Color(0xFFFABD2F),
        onSecondary = Color(0xFF282828),
        secondaryContainer = Color(0xFF3C3836),
        onSecondaryContainer = Color(0xFFBDAE93),
        background = Color(0xFF282828),
        onBackground = Color(0xFFFBF1C7),
        surface = Color(0xFF32302F),
        onSurface = Color(0xFFFBF1C7),
        surfaceVariant = Color(0xFF3C3836),
        onSurfaceVariant = Color(0xFFBDAE93),
        outline = Color(0xFF665C54),
    )

private val GruvboxLightColorScheme =
    lightColorScheme(
        primary = Color(0xFFD65D0E),
        onPrimary = Color(0xFFFBF1C7),
        primaryContainer = Color(0xFFFE8019),
        onPrimaryContainer = Color(0xFF282828),
        secondary = Color(0xFF458588),
        onSecondary = Color(0xFFFBF1C7),
        secondaryContainer = Color(0xFFEBDBB2),
        onSecondaryContainer = Color(0xFF3C3836),
        background = Color(0xFFFBF1C7),
        onBackground = Color(0xFF282828),
        surface = Color(0xFFF2E5BC),
        onSurface = Color(0xFF282828),
        surfaceVariant = Color(0xFFEBDBB2),
        onSurfaceVariant = Color(0xFF7C6F64),
        outline = Color(0xFFA89984),
    )

private val CatppuccinDarkColorScheme =
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

private val CatppuccinLightColorScheme =
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

private val AmoledDarkColorScheme =
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

private val NeonNoirDarkColorScheme =
    darkColorScheme(
        primary = Color(0xFFFF6BF4),
        onPrimary = Color(0xFF1A001A),
        primaryContainer = Color(0xFF3A003A),
        onPrimaryContainer = Color(0xFFFFCCF2),
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

private val NeonNoirLightColorScheme =
    lightColorScheme(
        primary = Color(0xFFC440A8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFD9F2),
        onPrimaryContainer = Color(0xFF3A003A),
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

@Composable
fun HermesControlTheme(
    themePreference: ThemePreference = LocalThemePreference.current,
    useDynamicColors: Boolean = true,
    themePreset: ThemePreset = ThemePreset.DEFAULT,
    content: @Composable () -> Unit,
) {
    val darkTheme =
        when (themePreference) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        }

    val context = LocalContext.current
    val dynamicAvailable = useDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme =
        when {
            dynamicAvailable && darkTheme -> {
                dynamicDarkColorScheme(context)
            }

            dynamicAvailable && !darkTheme -> {
                dynamicLightColorScheme(context)
            }

            else -> {
                when (themePreset) {
                    ThemePreset.DEFAULT -> if (darkTheme) HermesDarkColorScheme else HermesLightColorScheme
                    ThemePreset.MONOCHROME -> if (darkTheme) MonochromeDarkColorScheme else MonochromeLightColorScheme
                    ThemePreset.GRUVBOX -> if (darkTheme) GruvboxDarkColorScheme else GruvboxLightColorScheme
                    ThemePreset.CATPPUCCIN -> if (darkTheme) CatppuccinDarkColorScheme else CatppuccinLightColorScheme
                    ThemePreset.AMOLED -> if (darkTheme) AmoledDarkColorScheme else HermesLightColorScheme
                    ThemePreset.NEON_NOIR -> if (darkTheme) NeonNoirDarkColorScheme else NeonNoirLightColorScheme
                }
            }
        }

    val statusColors =
        when {
            themePreset == ThemePreset.GRUVBOX && darkTheme -> {
                HermesStatusColors(
                    success = Color(0xFFB8BB26),
                    successContainer = Color(0xFF32302F),
                    warning = Color(0xFFFABD2F),
                    warningContainer = Color(0xFF3C3836),
                    error = Color(0xFFFB4934),
                    errorContainer = Color(0xFF282828),
                    info = Color(0xFF83A598),
                    infoContainer = Color(0xFF3C3836),
                )
            }

            themePreset == ThemePreset.GRUVBOX && !darkTheme -> {
                HermesStatusColors(
                    success = Color(0xFF98971A),
                    successContainer = Color(0xFFEBDBB2),
                    warning = Color(0xFFD79921),
                    warningContainer = Color(0xFFEBDBB2),
                    error = Color(0xFFCC241D),
                    errorContainer = Color(0xFFEBDBB2),
                    info = Color(0xFF458588),
                    infoContainer = Color(0xFFEBDBB2),
                )
            }

            themePreset == ThemePreset.CATPPUCCIN && darkTheme -> {
                HermesStatusColors(
                    success = Color(0xFFA6E3A1),
                    successContainer = Color(0xFF313244),
                    warning = Color(0xFFF9E2AF),
                    warningContainer = Color(0xFF313244),
                    error = Color(0xFFF38BA8),
                    errorContainer = Color(0xFF313244),
                    info = Color(0xFF89B4FA),
                    infoContainer = Color(0xFF313244),
                )
            }

            themePreset == ThemePreset.CATPPUCCIN && !darkTheme -> {
                HermesStatusColors(
                    success = Color(0xFF40A02B),
                    successContainer = Color(0xFFE6E9EF),
                    warning = Color(0xFFDF8E1D),
                    warningContainer = Color(0xFFE6E9EF),
                    error = Color(0xFFD20F39),
                    errorContainer = Color(0xFFE6E9EF),
                    info = Color(0xFF1E66F5),
                    infoContainer = Color(0xFFE6E9EF),
                )
            }

            themePreset == ThemePreset.NEON_NOIR && darkTheme -> {
                HermesStatusColors(
                    success = Color(0xFF6FEA8B),
                    successContainer = Color(0xFF0A3A14),
                    warning = Color(0xFFFFB347),
                    warningContainer = Color(0xFF3A2000),
                    error = Color(0xFFFF3D68),
                    errorContainer = Color(0xFF3A0A14),
                    info = Color(0xFF60F0FF),
                    infoContainer = Color(0xFF003B40),
                )
            }

            themePreset == ThemePreset.NEON_NOIR && !darkTheme -> {
                HermesStatusColors(
                    success = Color(0xFF1B873A),
                    successContainer = Color(0xFFD7F5E0),
                    warning = Color(0xFFB87800),
                    warningContainer = Color(0xFFFFEAB3),
                    error = Color(0xFFB3261E),
                    errorContainer = Color(0xFFF9DEDC),
                    info = Color(0xFF2E6FBD),
                    infoContainer = Color(0xFFD4E7FF),
                )
            }

            darkTheme -> {
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
            }

            else -> {
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
        }

    CompositionLocalProvider(
        LocalThemePreference provides themePreference,
        LocalThemePreset provides themePreset,
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
