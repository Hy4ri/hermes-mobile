package com.m57.hermescontrol.theme.presets

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.m57.hermescontrol.theme.DarkBackground
import com.m57.hermescontrol.theme.DarkInverseOnSurface
import com.m57.hermescontrol.theme.DarkInverseSurface
import com.m57.hermescontrol.theme.DarkOnSurface
import com.m57.hermescontrol.theme.DarkOnSurfaceVariant
import com.m57.hermescontrol.theme.DarkOutline
import com.m57.hermescontrol.theme.DarkOutlineVariant
import com.m57.hermescontrol.theme.DarkScrim
import com.m57.hermescontrol.theme.DarkSurface
import com.m57.hermescontrol.theme.DarkSurfaceContainer
import com.m57.hermescontrol.theme.DarkSurfaceContainerHigh
import com.m57.hermescontrol.theme.DarkSurfaceContainerHighest
import com.m57.hermescontrol.theme.DarkSurfaceVariant
import com.m57.hermescontrol.theme.HermesAmber
import com.m57.hermescontrol.theme.HermesAmberDark
import com.m57.hermescontrol.theme.HermesAmberLight
import com.m57.hermescontrol.theme.HermesPurple
import com.m57.hermescontrol.theme.HermesPurpleContainer
import com.m57.hermescontrol.theme.HermesPurpleDark
import com.m57.hermescontrol.theme.HermesPurpleLight
import com.m57.hermescontrol.theme.HermesPurpleOnContainer
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.LightBackground
import com.m57.hermescontrol.theme.LightInverseOnSurface
import com.m57.hermescontrol.theme.LightInverseSurface
import com.m57.hermescontrol.theme.LightOnSurface
import com.m57.hermescontrol.theme.LightOnSurfaceVariant
import com.m57.hermescontrol.theme.LightOutline
import com.m57.hermescontrol.theme.LightOutlineVariant
import com.m57.hermescontrol.theme.LightScrim
import com.m57.hermescontrol.theme.LightSurface
import com.m57.hermescontrol.theme.LightSurfaceContainer
import com.m57.hermescontrol.theme.LightSurfaceContainerHigh
import com.m57.hermescontrol.theme.LightSurfaceContainerHighest
import com.m57.hermescontrol.theme.LightSurfaceVariant
import com.m57.hermescontrol.theme.StatusBlue
import com.m57.hermescontrol.theme.StatusBlueContainer
import com.m57.hermescontrol.theme.StatusGreen
import com.m57.hermescontrol.theme.StatusGreenContainer
import com.m57.hermescontrol.theme.StatusGreyLight
import com.m57.hermescontrol.theme.StatusRed
import com.m57.hermescontrol.theme.StatusRedContainer
import com.m57.hermescontrol.theme.StatusYellow
import com.m57.hermescontrol.theme.StatusYellowContainer

/** Brand default color scheme (dark) — Voltage Purple primary. */
val DefaultDarkColorScheme =
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

/** Brand default color scheme (light) — Voltage Purple primary. */
val DefaultLightColorScheme =
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

/** Default status colors (dark). */
val DefaultDarkStatusColors =
    HermesStatusColors(
        success = StatusGreen,
        successContainer = StatusGreenContainer,
        onSuccess = StatusGreyLight,
        warning = StatusYellow,
        warningContainer = StatusYellowContainer,
        onWarning = StatusGreyLight,
        error = StatusRed,
        errorContainer = StatusRedContainer,
        onError = StatusGreyLight,
        info = StatusBlue,
        infoContainer = StatusBlueContainer,
        onInfo = StatusGreyLight,
    )

/** Default status colors (light). */
val DefaultLightStatusColors =
    HermesStatusColors(
        success = Color(0xFF1B873A),
        successContainer = Color(0xFFD7F5E0),
        onSuccess = Color(0xFFFDF8FF),
        warning = HermesAmberDark,
        warningContainer = Color(0xFFFFEAB3),
        onWarning = Color(0xFFFDF8FF),
        error = Color(0xFFB3261E),
        errorContainer = Color(0xFFF9DEDC),
        onError = Color(0xFFFDF8FF),
        info = Color(0xFF2E6FBD),
        infoContainer = Color(0xFFD4E7FF),
        onInfo = Color(0xFFFDF8FF),
    )
