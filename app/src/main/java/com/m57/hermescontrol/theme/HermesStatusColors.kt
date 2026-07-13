package com.m57.hermescontrol.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

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
    val onSuccess: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarning: Color,
    val error: Color,
    val errorContainer: Color,
    val onError: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfo: Color,
    val neutral: Color = StatusGrey,
)

/**
 * Theme-aware colors for markdown/search highlighting. Built per-composition from
 * [LocalHermesStatusColors] so `==mark==` and search terms follow the active preset
 * instead of a fixed yellow.
 */
data class SearchHighlightColors(
    val markupBackground: Color,
    val searchBackground: Color,
    val searchForeground: Color,
    val currentSearchBackground: Color,
    val currentSearchForeground: Color,
)

/** Build the highlight palette for the current theme preset. */
internal fun onColorFor(color: Color): Color = if (color.luminance() > 0.5f) StatusGreyDark else StatusGreyLight

internal fun searchHighlightColors(statusColors: HermesStatusColors): SearchHighlightColors =
    SearchHighlightColors(
        markupBackground = statusColors.warning.copy(alpha = 0.3f),
        searchBackground = statusColors.warningContainer,
        searchForeground = onColorFor(statusColors.warningContainer),
        currentSearchBackground = statusColors.warning,
        currentSearchForeground = statusColors.onWarning,
    )

val LocalHermesStatusColors =
    staticCompositionLocalOf {
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
    }
