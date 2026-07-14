package com.m57.hermescontrol.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.m57.hermescontrol.theme.presets.AmoledDarkColorScheme
import com.m57.hermescontrol.theme.presets.CatppuccinDarkColorScheme
import com.m57.hermescontrol.theme.presets.CatppuccinDarkStatusColors
import com.m57.hermescontrol.theme.presets.CatppuccinLightColorScheme
import com.m57.hermescontrol.theme.presets.CatppuccinLightStatusColors
import com.m57.hermescontrol.theme.presets.DefaultDarkColorScheme
import com.m57.hermescontrol.theme.presets.DefaultDarkStatusColors
import com.m57.hermescontrol.theme.presets.DefaultLightColorScheme
import com.m57.hermescontrol.theme.presets.DefaultLightStatusColors
import com.m57.hermescontrol.theme.presets.GruvboxDarkColorScheme
import com.m57.hermescontrol.theme.presets.GruvboxDarkStatusColors
import com.m57.hermescontrol.theme.presets.GruvboxLightColorScheme
import com.m57.hermescontrol.theme.presets.GruvboxLightStatusColors
import com.m57.hermescontrol.theme.presets.MonochromeDarkColorScheme
import com.m57.hermescontrol.theme.presets.MonochromeLightColorScheme
import com.m57.hermescontrol.theme.presets.NeonNoirDarkColorScheme
import com.m57.hermescontrol.theme.presets.NeonNoirDarkStatusColors
import com.m57.hermescontrol.theme.presets.NeonNoirLightColorScheme
import com.m57.hermescontrol.theme.presets.NeonNoirLightStatusColors
import kotlinx.serialization.Serializable

@Serializable
enum class ThemePreference { SYSTEM, LIGHT, DARK }

@Serializable
enum class ThemePreset { DEFAULT, MONOCHROME, GRUVBOX, CATPPUCCIN, AMOLED, NEON_NOIR }

@Serializable
enum class BottomNavDisplayMode { ICON_AND_TEXT, ICON_ONLY, TEXT_ONLY }

val LocalThemePreference = compositionLocalOf { ThemePreference.SYSTEM }
val LocalThemePreset = compositionLocalOf { ThemePreset.DEFAULT }

/**
 * Resolve the Material 3 [ColorScheme] for a preset + dark flag.
 *
 * AMOLED has no light variant — it falls back to the brand default light
 * scheme in light mode.
 */
private fun resolveColorScheme(
    preset: ThemePreset,
    darkTheme: Boolean,
) = when (preset) {
    ThemePreset.DEFAULT -> if (darkTheme) DefaultDarkColorScheme else DefaultLightColorScheme
    ThemePreset.MONOCHROME -> if (darkTheme) MonochromeDarkColorScheme else MonochromeLightColorScheme
    ThemePreset.GRUVBOX -> if (darkTheme) GruvboxDarkColorScheme else GruvboxLightColorScheme
    ThemePreset.CATPPUCCIN -> if (darkTheme) CatppuccinDarkColorScheme else CatppuccinLightColorScheme
    ThemePreset.AMOLED -> if (darkTheme) AmoledDarkColorScheme else DefaultLightColorScheme
    ThemePreset.NEON_NOIR -> if (darkTheme) NeonNoirDarkColorScheme else NeonNoirLightColorScheme
}

/**
 * Resolve the semantic status colors for a preset + dark flag.
 *
 * Presets without a bespoke status set (MONOCHROME, AMOLED) reuse the brand
 * default dark/light status colors.
 */
private fun resolveStatusColors(
    preset: ThemePreset,
    darkTheme: Boolean,
) = when (preset) {
    ThemePreset.DEFAULT -> if (darkTheme) DefaultDarkStatusColors else DefaultLightStatusColors
    ThemePreset.MONOCHROME -> if (darkTheme) DefaultDarkStatusColors else DefaultLightStatusColors
    ThemePreset.GRUVBOX -> if (darkTheme) GruvboxDarkStatusColors else GruvboxLightStatusColors
    ThemePreset.CATPPUCCIN -> if (darkTheme) CatppuccinDarkStatusColors else CatppuccinLightStatusColors
    ThemePreset.AMOLED -> if (darkTheme) DefaultDarkStatusColors else DefaultLightStatusColors
    ThemePreset.NEON_NOIR -> if (darkTheme) NeonNoirDarkStatusColors else NeonNoirLightStatusColors
}

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
        if (dynamicAvailable) {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        } else {
            resolveColorScheme(themePreset, darkTheme)
        }

    val statusColors = resolveStatusColors(themePreset, darkTheme)

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
