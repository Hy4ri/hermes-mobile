package com.m57.hermescontrol.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.m57.hermescontrol.theme.presets.AmoledTheme
import com.m57.hermescontrol.theme.presets.CatppuccinTheme
import com.m57.hermescontrol.theme.presets.CyberpunkTheme
import com.m57.hermescontrol.theme.presets.DefaultTheme
import com.m57.hermescontrol.theme.presets.GruvboxTheme
import com.m57.hermescontrol.theme.presets.MonochromeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the palette template's color invariants:
 * 1. Every shipped error slot pair meets >= 3:1 WCAG contrast.
 * 2. ThemeMode combinations are validated at construction time.
 * 3. One-mode themes resolve and fall back to the default theme correctly.
 */
class ThemePaletteTest {
    private val themes =
        listOf(
            ThemePreset.DEFAULT to DefaultTheme,
            ThemePreset.MONOCHROME to MonochromeTheme,
            ThemePreset.GRUVBOX to GruvboxTheme,
            ThemePreset.CATPPUCCIN to CatppuccinTheme,
            ThemePreset.AMOLED to AmoledTheme,
            ThemePreset.CYBERPUNK to CyberpunkTheme,
        )

    @Test
    fun errorSlotPairsMeetContrastInEveryShippedMode() {
        themes.forEach { (preset, theme) ->
            listOf(true, false).forEach { dark ->
                val scheme = theme.schemeFor(dark) ?: return@forEach
                assertTrue(
                    "$preset dark=$dark onError/error contrast must be >= 3:1",
                    contrast(scheme.onError, scheme.error) >= 3f,
                )
                assertTrue(
                    "$preset dark=$dark onErrorContainer/errorContainer contrast must be >= 3:1",
                    contrast(scheme.onErrorContainer, scheme.errorContainer) >= 3f,
                )
            }
        }
    }

    /**
     * Full-bleed chat renderer gate (issue #866): agent prose renders directly
     * on the screen background (HermesScaffold containerColor = background),
     * so the full-bleed text pairs must hold against [ColorScheme.background]:
     * - body prose (onSurface) >= 4.5:1 — primary content, WCAG AA text
     * - header role label + timestamp (onSurfaceVariant) >= 3:1 — WCAG AA UI
     * (The header deliberately avoids `primary`: Nord's light mode reuses its
     * pastel Frost accent as primary, which cannot reach 3:1 on a light
     * background.)
     * Iterates ThemePreset.entries directly so EVERY shipped preset (incl.
     * NORD, which the [themes] list above predates) is covered.
     */
    @Test
    fun fullBleedTextPairsMeetContrastInEveryShippedMode() {
        ThemePreset.entries.forEach { preset ->
            listOf(true, false).forEach { dark ->
                val scheme = resolveColorScheme(preset, darkTheme = dark) ?: return@forEach
                assertTrue(
                    "$preset dark=$dark onSurface/background contrast must be >= 4.5:1 (full-bleed prose)",
                    contrast(scheme.onSurface, scheme.background) >= 4.5f,
                )
                assertTrue(
                    "$preset dark=$dark onSurfaceVariant/background contrast must be >= 3:1 (full-bleed header)",
                    contrast(scheme.onSurfaceVariant, scheme.background) >= 3f,
                )
            }
        }
    }

    /**
     * Cyberpunk accent-ink gate.
     *
     * The web source palette is shadcn-shaped: its `secondary`/`accent` tokens are
     * SURFACES paired with a `…Foreground` ink. Material 3's `secondary`/`tertiary`
     * are the opposite — accent INKS painted straight onto background/surface as
     * icon tints, progress indicators and badge text (ToolBubble, CronJobsScreen,
     * AchievementsScreen, SourceBadge, ContextUsageChip, GatewayScreen). Assigning
     * the web surface token to the bare Material slot yields ~1.2:1 invisible ink,
     * so this guards the corrected direction of that mapping.
     *
     * Scoped to CYBERPUNK deliberately: Nord light and Catppuccin light ship
     * pastel accents at ~2.3:1 today, so a fleet-wide version of this gate would
     * fail on pre-existing presets and is tracked separately.
     */
    @Test
    fun cyberpunkAccentInksAreVisibleOnItsSurfaces() {
        val scheme = CyberpunkTheme.darkScheme!!
        listOf(
            "secondary" to scheme.secondary,
            "tertiary" to scheme.tertiary,
            "outline" to scheme.outline,
        ).forEach { (name, ink) ->
            listOf("background" to scheme.background, "surface" to scheme.surface).forEach { (bgName, bg) ->
                assertTrue(
                    "Cyberpunk $name/$bgName contrast must be >= 3:1 (painted as icon tint/badge text)",
                    contrast(ink, bg) >= 3f,
                )
            }
        }
    }

    @Test
    fun amoledShipsDarkOnly() {
        assertTrue("AMOLED must ship a dark scheme", AmoledTheme.darkScheme != null)
        assertTrue("AMOLED must not ship a light scheme", AmoledTheme.lightScheme == null)
        assertTrue("AMOLED must ship dark status colors", AmoledTheme.darkStatus != null)
        assertTrue("AMOLED must not ship light status colors", AmoledTheme.lightStatus == null)
    }

    @Test
    fun everyPresetResolvesItsDeclaredModes() {
        themes.forEach { (preset, theme) ->
            listOf(true, false).forEach { dark ->
                if (theme.schemeFor(dark) != null) {
                    assertTrue(
                        "$preset dark=$dark scheme must resolve",
                        resolveColorScheme(preset, darkTheme = dark) != null,
                    )
                    assertTrue(
                        "$preset dark=$dark status must resolve",
                        resolveStatusColors(preset, darkTheme = dark) != null,
                    )
                }
            }
        }
    }

    @Test
    fun amoledLightModeFallsBackToDefaultLight() {
        val fallbackScheme = resolveColorScheme(ThemePreset.AMOLED, darkTheme = false)
        val defaultLight = DefaultTheme.lightScheme!!
        assertEquals(
            "AMOLED light scheme must fall back to Default light background",
            defaultLight.background,
            fallbackScheme.background,
        )

        val fallbackStatus = resolveStatusColors(ThemePreset.AMOLED, darkTheme = false)
        val defaultLightStatus = DefaultTheme.lightStatus!!
        assertEquals(
            "AMOLED light status must fall back to Default light success",
            defaultLightStatus.success,
            fallbackStatus.success,
        )

        // AMOLED dark resolves to its own palette, not the default.
        val ownDark = AmoledTheme.darkScheme!!
        assertEquals(
            "AMOLED dark scheme must be its own",
            ownDark.background,
            resolveColorScheme(ThemePreset.AMOLED, darkTheme = true).background,
        )
    }

    @Test
    fun themePaletteRejectsIllegalModeCombinations() {
        val colors = dummyColors()
        assertThrows(IllegalArgumentException::class.java) {
            ThemePalette(ThemeMode.FULL, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThemePalette(ThemeMode.FULL, colors, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThemePalette(ThemeMode.FULL, null, colors)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThemePalette(ThemeMode.DARK_ONLY, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThemePalette(ThemeMode.DARK_ONLY, null, colors)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThemePalette(ThemeMode.LIGHT_ONLY, null, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThemePalette(ThemeMode.LIGHT_ONLY, colors, null)
        }

        // Valid combinations must construct without throwing.
        ThemePalette(ThemeMode.FULL, colors, colors)
        ThemePalette(ThemeMode.DARK_ONLY, colors, null)
        ThemePalette(ThemeMode.LIGHT_ONLY, null, colors)
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun dummyColors(): PaletteColors =
        PaletteColors(
            primary = Color.White,
            onPrimary = Color.Black,
            primaryContainer = Color.White,
            onPrimaryContainer = Color.Black,
            secondary = Color.White,
            onSecondary = Color.Black,
            secondaryContainer = Color.White,
            onSecondaryContainer = Color.Black,
            tertiary = Color.White,
            onTertiary = Color.Black,
            tertiaryContainer = Color.White,
            onTertiaryContainer = Color.Black,
            background = Color.White,
            onBackground = Color.Black,
            surface = Color.White,
            onSurface = Color.Black,
            surfaceVariant = Color.White,
            onSurfaceVariant = Color.Black,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color.White,
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color.White,
            surfaceContainerHighest = Color.White,
            inverseSurface = Color.Black,
            inverseOnSurface = Color.White,
            inversePrimary = Color.Black,
            outline = Color.Gray,
            outlineVariant = Color.Gray,
            scrim = Color.Black,
            status =
                HermesStatusColors(
                    success = Color.Green,
                    successContainer = Color.White,
                    onSuccess = Color.Black,
                    warning = Color.Yellow,
                    warningContainer = Color.White,
                    onWarning = Color.Black,
                    error = Color.Red,
                    errorContainer = Color.White,
                    onError = Color.Black,
                    onErrorContainer = Color.Black,
                    info = Color.Blue,
                    infoContainer = Color.White,
                    onInfo = Color.Black,
                ),
        )
}
