package com.m57.hermescontrol.data.theme.import

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.PaletteColors
import com.m57.hermescontrol.theme.ThemeMode
import com.m57.hermescontrol.theme.ThemePalette
import com.m57.hermescontrol.theme.buildTheme
import com.m57.hermescontrol.theme.buildThemeDarkOnly
import com.m57.hermescontrol.theme.buildThemeLightOnly

/**
 * Converts raw VS Code theme tokens (from a VSIX or dashboard definition)
 * into a [ThemePalette] the app's existing dispatcher consumes (t_f3c6f528).
 *
 * Ports `convertVscodeColorTheme` from `apps/desktop/src/themes/vscode.ts`:
 * ~6 workbench keys carry the whole look (background, foreground, accent,
 * elevated surface, sidebar, error); everything else derives by mixing those
 * toward background/foreground. Every slot is filled — never
 * [Color.Unspecified] — so contrast gates and the Material scheme hold for
 * any marketplace theme.
 *
 * Multi-variant extensions fold into ONE family like the desktop
 * `buildThemeFromMarketplace`: first light variant → light palette, first
 * dark variant → dark palette. A single-variant theme fills both slots.
 */
class ThemeDefinitionConverter {
    data class ConvertedVariant(
        val palette: PaletteColors,
        val isDark: Boolean,
    )

    /** Convert one VS Code theme variant into [PaletteColors]. */
    fun convertVariant(tokens: ThemeTokenSet): ConvertedVariant {
        val colors = tokens.colors
        val background =
            pick(colors, BACKGROUND_KEYS, Color.Black)
                ?: Color(0xFF1E1E1E)
        val dark = isDarkType(tokens.type, background)
        val bg = pick(colors, BACKGROUND_KEYS, background) ?: fallbackBg(dark)
        val fg = pick(colors, FOREGROUND_KEYS, bg) ?: fallbackFg(dark)

        val accentSource =
            pick(colors, ACCENT_KEYS, bg)
                ?: ThemeColorUtils.mix(fg, bg, 0.55f)
        val sidebar =
            pick(colors, SIDEBAR_KEYS, bg)
                ?: ThemeColorUtils.mix(bg, fg, if (dark) 0.02f else 0.012f)
        val accent = ThemeColorUtils.ensureContrast(accentSource, sidebar, ACCENT_MIN_CONTRAST)
        val elevated =
            pick(colors, ELEVATED_KEYS, bg)
                ?: ThemeColorUtils.mix(bg, fg, if (dark) 0.08f else 0.05f)
        val card =
            pick(colors, CARD_KEYS, bg)
                ?: ThemeColorUtils.mix(bg, fg, if (dark) 0.04f else 0.025f)
        val border =
            pick(colors, BORDER_KEYS, bg)
                ?: ThemeColorUtils.mix(bg, fg, if (dark) 0.16f else 0.14f)
        val input =
            pick(colors, INPUT_KEYS, bg)
                ?: ThemeColorUtils.mix(bg, fg, if (dark) 0.10f else 0.06f)
        val mutedFg =
            pick(colors, MUTED_FG_KEYS, bg)
                ?: ThemeColorUtils.mix(fg, bg, 0.45f)
        val destructive =
            pick(colors, DESTRUCTIVE_KEYS, bg)
                ?: Color(0xFFE25563)
        val success =
            pick(colors, SUCCESS_KEYS, bg)
                ?: Color(0xFF3DDC84)
        val warning =
            pick(colors, WARNING_KEYS, bg)
                ?: Color(0xFFFFB627)
        val info =
            pick(colors, INFO_KEYS, bg)
                ?: Color(0xFF4DA8FF)

        val inkOnAccent = ThemeColorUtils.readableInk(accent)
        val inkOnDestructive = ThemeColorUtils.readableInk(destructive)
        val muted = ThemeColorUtils.mix(bg, fg, if (dark) 0.06f else 0.04f)
        val accentSoft = ThemeColorUtils.mix(accent, bg, if (dark) 0.82f else 0.88f)
        val secondary = ThemeColorUtils.mix(accent, bg, if (dark) 0.72f else 0.86f)
        val containerLow = ThemeColorUtils.mix(bg, fg, if (dark) 0.03f else 0.02f)
        val containerHigh = ThemeColorUtils.mix(bg, fg, if (dark) 0.12f else 0.08f)
        val containerHighest = ThemeColorUtils.mix(bg, fg, if (dark) 0.18f else 0.12f)
        val userBubble = ThemeColorUtils.mix(card, accent, if (dark) 0.18f else 0.12f)

        val status =
            HermesStatusColors(
                success = success,
                successContainer = ThemeColorUtils.mix(bg, success, 0.80f),
                onSuccess = ThemeColorUtils.readableInk(success),
                warning = warning,
                warningContainer = ThemeColorUtils.mix(bg, warning, 0.80f),
                onWarning = ThemeColorUtils.readableInk(warning),
                error = destructive,
                errorContainer = ThemeColorUtils.mix(bg, destructive, 0.80f),
                onError = inkOnDestructive,
                onErrorContainer = ThemeColorUtils.ensureContrast(fg, ThemeColorUtils.mix(bg, destructive, 0.80f), 3f),
                info = info,
                infoContainer = ThemeColorUtils.mix(bg, info, 0.80f),
                onInfo = ThemeColorUtils.readableInk(info),
            )

        return ConvertedVariant(
            palette =
                PaletteColors(
                    primary = accent,
                    onPrimary = inkOnAccent,
                    primaryContainer = ThemeColorUtils.mix(accent, bg, 0.75f),
                    onPrimaryContainer = fg,
                    secondary = secondary,
                    onSecondary = ThemeColorUtils.readableInk(secondary),
                    secondaryContainer = ThemeColorUtils.mix(secondary, bg, 0.70f),
                    onSecondaryContainer = fg,
                    tertiary = accentSoft,
                    onTertiary = ThemeColorUtils.readableInk(accentSoft),
                    tertiaryContainer = ThemeColorUtils.mix(accentSoft, bg, 0.60f),
                    onTertiaryContainer = fg,
                    background = bg,
                    onBackground = fg,
                    surface = sidebar,
                    onSurface = fg,
                    surfaceVariant = elevated,
                    onSurfaceVariant = mutedFg,
                    surfaceContainerLowest = bg,
                    surfaceContainerLow = containerLow,
                    surfaceContainer = card,
                    surfaceContainerHigh = containerHigh,
                    surfaceContainerHighest = containerHighest,
                    inverseSurface = fg,
                    inverseOnSurface = bg,
                    inversePrimary = accent,
                    outline = ThemeColorUtils.mix(fg, bg, 0.40f),
                    outlineVariant = border,
                    scrim = bg,
                    status = status,
                ),
            isDark = dark,
        )
    }

    /**
     * Fold contributed variants into ONE [ThemePalette]: first light variant
     * → light, first dark variant → dark (desktop `buildThemeFromMarketplace`
     * semantics). Single-variant fills both slots so the light/dark toggle
     * is a no-op rather than a wrong-mode render.
     */
    fun buildFamily(variants: List<ThemeTokenSet>): ThemePalette {
        require(variants.isNotEmpty()) { "No theme variants to convert" }
        val converted = variants.map(::convertVariant)
        val light = converted.firstOrNull { !it.isDark } ?: converted.first()
        val dark = converted.firstOrNull { it.isDark } ?: converted.first()
        return if (light.isDark == dark.isDark && converted.size == 1) {
            if (dark.isDark) buildThemeDarkOnly(dark.palette) else buildThemeLightOnly(light.palette)
        } else {
            buildTheme(dark = dark.palette, light = light.palette)
        }
    }

    /** Legacy single-token entry point: same-variant fills per [mode]. */
    fun convert(
        tokens: ThemeTokenSet,
        mode: ThemeMode = ThemeMode.DARK_ONLY,
    ): ThemePalette {
        val variant = convertVariant(tokens)
        return when (mode) {
            ThemeMode.FULL -> buildTheme(dark = variant.palette, light = variant.palette)
            ThemeMode.DARK_ONLY -> buildThemeDarkOnly(variant.palette)
            ThemeMode.LIGHT_ONLY -> buildThemeLightOnly(variant.palette)
            else -> throw IllegalArgumentException("Unknown ThemeMode: $mode")
        }
    }

    private fun pick(
        colors: Map<String, String>,
        keys: List<String>,
        backdrop: Color,
    ): Color? {
        for (key in keys) {
            val parsed = ThemeColorUtils.parseLayer(colors[key], backdrop)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun isDarkType(
        type: String,
        background: Color,
    ): Boolean {
        val t = type.lowercase()
        if (t.contains("light")) return false
        if (t == "dark" || t == "hc" || t == "hc-black" || t.contains("dark")) return true
        return background.luminance() < 0.4f
    }

    private fun fallbackBg(dark: Boolean): Color = if (dark) Color(0xFF1E1E1E) else Color.White

    private fun fallbackFg(dark: Boolean): Color = if (dark) Color(0xFFD4D4D4) else Color(0xFF1F1F1F)

    private companion object {
        const val ACCENT_MIN_CONTRAST = 4.5f
        val BACKGROUND_KEYS = listOf("editor.background", "editorPane.background", "editorGroup.background")
        val FOREGROUND_KEYS = listOf("editor.foreground", "foreground")
        val ACCENT_KEYS =
            listOf(
                "button.background",
                "textLink.activeForeground",
                "textLink.foreground",
                "activityBarBadge.background",
                "badge.background",
                "progressBar.background",
                "pickerGroup.foreground",
                "list.highlightForeground",
                "editorLink.activeForeground",
                "focusBorder",
                "tab.activeBorder",
                "statusBarItem.remoteBackground",
            )
        val ELEVATED_KEYS =
            listOf(
                "editorWidget.background",
                "dropdown.background",
                "menu.background",
                "quickInput.background",
                "editorSuggestWidget.background",
            )
        val CARD_KEYS =
            listOf(
                "sideBarSectionHeader.background",
                "tab.inactiveBackground",
                "editorGroupHeader.tabsBackground",
            )
        val SIDEBAR_KEYS = listOf("sideBar.background", "activityBar.background")
        val BORDER_KEYS =
            listOf(
                "panel.border",
                "editorGroup.border",
                "sideBar.border",
                "contrastBorder",
                "widget.border",
                "input.border",
            )
        val INPUT_KEYS = listOf("input.background", "dropdown.background", "quickInput.background")
        val MUTED_FG_KEYS =
            listOf(
                "descriptionForeground",
                "editorLineNumber.foreground",
                "tab.inactiveForeground",
                "disabledForeground",
            )
        val DESTRUCTIVE_KEYS =
            listOf(
                "editorError.foreground",
                "errorForeground",
                "editorOverviewRuler.errorForeground",
                "notificationsErrorIcon.foreground",
            )
        val SUCCESS_KEYS = listOf("gitDecoration.modifiedResourceForeground", "testing.iconPassed")
        val WARNING_KEYS = listOf("editorWarning.foreground", "problemsWarningIcon.foreground")
        val INFO_KEYS = listOf("editorInfo.foreground", "problemsInfoIcon.foreground")
    }
}
