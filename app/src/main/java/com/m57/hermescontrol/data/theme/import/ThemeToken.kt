package com.m57.hermescontrol.data.theme.import

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/**
 * Raw color tokens parsed from a VS Code theme's package.json
 * (the `colors` map) or the dashboard's theme definition.
 *
 * Token names follow the VS Code convention: e.g. `editor.background`,
 * `editor.foreground`, `activityBar.background`, etc.
 */
@Serializable
data class ThemeTokenSet(
    val colors: Map<String, String> = emptyMap(),
    /** Semantic token groupings (may be absent in older themes). */
    val semanticColors: Map<String, String> = emptyMap(),
    /** Theme metadata. */
    val name: String = "",
    val label: String = "",
    val description: String = "",
    /** VS Code theme `type`: "dark", "light", "hc", "hc-black", or "". */
    val type: String = "",
)

/**
 * A resolved subset of theme tokens mapped to the slots the app needs.
 * This is the bridge between raw VS Code tokens and [PaletteColors].
 */
data class ResolvedThemeTokens(
    val primary: String? = null,
    val onPrimary: String? = null,
    val primaryContainer: String? = null,
    val onPrimaryContainer: String? = null,
    val secondary: String? = null,
    val onSecondary: String? = null,
    val secondaryContainer: String? = null,
    val onSecondaryContainer: String? = null,
    val tertiary: String? = null,
    val onTertiary: String? = null,
    val tertiaryContainer: String? = null,
    val onTertiaryContainer: String? = null,
    val background: String? = null,
    val onBackground: String? = null,
    val surface: String? = null,
    val onSurface: String? = null,
    val surfaceVariant: String? = null,
    val onSurfaceVariant: String? = null,
    val surfaceContainerLowest: String? = null,
    val surfaceContainerLow: String? = null,
    val surfaceContainer: String? = null,
    val surfaceContainerHigh: String? = null,
    val surfaceContainerHighest: String? = null,
    val inverseSurface: String? = null,
    val inverseOnSurface: String? = null,
    val inversePrimary: String? = null,
    val outline: String? = null,
    val outlineVariant: String? = null,
    val scrim: String? = null,
    val success: String? = null,
    val successContainer: String? = null,
    val onSuccess: String? = null,
    val warning: String? = null,
    val warningContainer: String? = null,
    val onWarning: String? = null,
    val error: String? = null,
    val errorContainer: String? = null,
    val onError: String? = null,
    val onErrorContainer: String? = null,
    val info: String? = null,
    val infoContainer: String? = null,
    val onInfo: String? = null,
)
