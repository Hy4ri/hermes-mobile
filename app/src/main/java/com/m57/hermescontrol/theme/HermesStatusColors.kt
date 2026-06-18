package com.m57.hermescontrol.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic status colour set — always brand-defined (never dynamic).
 *
 * Material You changes the *primary* / *surface* palette based on the user's
 * wallpaper, but success / warning / error / info colours must stay
 * semantically correct (green = good, red = bad) regardless of what the
 * wallpaper-derived palette would choose. This struct is provided via
 * [LocalHermesStatusColors] and should be used for all status indicators,
 * badges, and semantic feedback colours.
 */
data class HermesStatusColors(
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val info: Color,
    val infoContainer: Color,
)

val LocalHermesStatusColors =
    staticCompositionLocalOf {
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
