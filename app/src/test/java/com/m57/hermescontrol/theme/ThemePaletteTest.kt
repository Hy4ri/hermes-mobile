package com.m57.hermescontrol.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.m57.hermescontrol.theme.presets.AmoledTheme
import com.m57.hermescontrol.theme.presets.DefaultTheme
import com.m57.hermescontrol.ui.common.StatusBadgeType
import com.m57.hermescontrol.ui.common.statusBadgeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Guards the palette template's color invariants:
 * 1. Normal-text pairs meet >= 4.5:1 contrast in every resolved mode.
 * 2. ThemeMode combinations are validated at construction time.
 * 3. Every Material role is explicitly mapped; fixed roles are mode-independent.
 * 4. One-mode themes resolve and fall back to the default theme correctly.
 */
class ThemePaletteTest {
    @Test
    fun registryCoversEveryPresetExactlyOnce() {
        assertEquals(ThemePreset.entries, ThemeRegistry.map { it.preset })
    }

    @Test
    fun textSlotPairsMeetContrastInEveryResolvedMode() {
        ThemeRegistry.forEach { (preset, _) ->
            listOf(true, false).forEach { dark ->
                val c = resolveColorScheme(preset, dark)
                val pairs =
                    listOf(
                        "onPrimary/primary" to (c.onPrimary to c.primary),
                        "onPrimaryContainer/primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
                        "onSecondary/secondary" to (c.onSecondary to c.secondary),
                        "onSecondaryContainer/secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
                        "onTertiary/tertiary" to (c.onTertiary to c.tertiary),
                        "onTertiaryContainer/tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
                        "onBackground/background" to (c.onBackground to c.background),
                        "onSurface/surface" to (c.onSurface to c.surface),
                        "onSurfaceVariant/surfaceVariant" to (c.onSurfaceVariant to c.surfaceVariant),
                        "onSurface/surfaceDim" to (c.onSurface to c.surfaceDim),
                        "onSurface/surfaceBright" to (c.onSurface to c.surfaceBright),
                        "onSurface/surfaceContainerLowest" to (c.onSurface to c.surfaceContainerLowest),
                        "onSurface/surfaceContainerLow" to (c.onSurface to c.surfaceContainerLow),
                        "onSurface/surfaceContainer" to (c.onSurface to c.surfaceContainer),
                        "onSurface/surfaceContainerHigh" to (c.onSurface to c.surfaceContainerHigh),
                        "onSurface/surfaceContainerHighest" to (c.onSurface to c.surfaceContainerHighest),
                        "inverseOnSurface/inverseSurface" to (c.inverseOnSurface to c.inverseSurface),
                        "onError/error" to (c.onError to c.error),
                        "onErrorContainer/errorContainer" to (c.onErrorContainer to c.errorContainer),
                        "onPrimaryFixed/primaryFixed" to (c.onPrimaryFixed to c.primaryFixed),
                        "onPrimaryFixedVariant/primaryFixed" to (c.onPrimaryFixedVariant to c.primaryFixed),
                        "onPrimaryFixed/primaryFixedDim" to (c.onPrimaryFixed to c.primaryFixedDim),
                        "onPrimaryFixedVariant/primaryFixedDim" to (c.onPrimaryFixedVariant to c.primaryFixedDim),
                        "onSecondaryFixed/secondaryFixed" to (c.onSecondaryFixed to c.secondaryFixed),
                        "onSecondaryFixedVariant/secondaryFixed" to (c.onSecondaryFixedVariant to c.secondaryFixed),
                        "onSecondaryFixed/secondaryFixedDim" to (c.onSecondaryFixed to c.secondaryFixedDim),
                        "onSecondaryFixedVariant/secondaryFixedDim" to
                            (c.onSecondaryFixedVariant to c.secondaryFixedDim),
                        "onTertiaryFixed/tertiaryFixed" to (c.onTertiaryFixed to c.tertiaryFixed),
                        "onTertiaryFixedVariant/tertiaryFixed" to (c.onTertiaryFixedVariant to c.tertiaryFixed),
                        "onTertiaryFixed/tertiaryFixedDim" to (c.onTertiaryFixed to c.tertiaryFixedDim),
                        "onTertiaryFixedVariant/tertiaryFixedDim" to (c.onTertiaryFixedVariant to c.tertiaryFixedDim),
                    )
                pairs.forEach { (name, colors) ->
                    val ratio = contrast(colors.first, colors.second)
                    assertTrue("$preset dark=$dark $name contrast $ratio must be >= 4.5:1", ratio >= 4.5f)
                }
            }
        }
    }

    @Test
    fun renderedStatusBadgePairsMeetContrastInEveryResolvedMode() {
        ThemeRegistry.forEach { (preset, _) ->
            listOf(true, false).forEach { dark ->
                val colors = resolveStatusColors(preset, dark)
                StatusBadgeType.entries.filter { it != StatusBadgeType.NEUTRAL }.forEach { type ->
                    // PR #1417: test the mapping consumed by both reusable badge renderers.
                    val (background, foreground) = requireNotNull(statusBadgeColors(type, colors))
                    val ratio = contrast(foreground, background)
                    assertTrue("$preset dark=$dark $type badge contrast $ratio must be >= 4.5:1", ratio >= 4.5f)
                }
            }
        }
    }

    @Test
    fun statusBadgesUseMatchingStatusFillsAndOnColors() {
        val c = dummyColors().status
        assertEquals(c.success to c.onSuccess, statusBadgeColors(StatusBadgeType.SUCCESS, c))
        assertEquals(c.warning to c.onWarning, statusBadgeColors(StatusBadgeType.WARNING, c))
        assertEquals(c.error to c.onError, statusBadgeColors(StatusBadgeType.ERROR, c))
        assertEquals(c.info to c.onInfo, statusBadgeColors(StatusBadgeType.INFO, c))
    }

    @Test
    fun fixedRolesDoNotChangeBetweenShippedModes() {
        ThemeRegistry.forEach { (preset, theme) ->
            val dark = theme.darkScheme
            val light = theme.lightScheme
            if (dark != null && light != null) {
                val darkRoles = colorFields(dark).filterKeys { "Fixed" in it }
                val lightRoles = colorFields(light).filterKeys { "Fixed" in it }
                assertEquals("$preset fixed roles must be mode-independent", darkRoles, lightRoles)
            }
        }
    }

    @Test
    fun everyMaterialColorRoleIsMappedFromThePalette() {
        // Distinct sentinel values catch swapped roles and accidental Material defaults.
        val colors = dummyColors()
        val expected = colorFields(colors).toMutableMap()
        expected["surfaceTint"] = expected.getValue("primary")
        expected.putAll(
            colorFields(colors.status).filterKeys {
                it in
                    setOf(
                        "error",
                        "onError",
                        "errorContainer",
                        "onErrorContainer",
                    )
            },
        )
        val theme = buildTheme(colors, colors)
        listOf(theme.darkScheme, theme.lightScheme).forEach { scheme ->
            assertEquals(expected, colorFields(requireNotNull(scheme)))
        }
    }

    private fun colorFields(value: Any): Map<String, Long> =
        value.javaClass.declaredFields
            .filter {
                it.type == java.lang.Long.TYPE &&
                    !Modifier.isStatic(it.modifiers)
            }.associate { field ->
                field.isAccessible = true
                field.name to field.getLong(value)
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
     * The registry covers every shipped preset and its fallback mode.
     */
    @Test
    fun fullBleedTextPairsMeetContrastInEveryShippedMode() {
        ThemeRegistry.forEach { (preset, _) ->
            listOf(true, false).forEach { dark ->
                val scheme = resolveColorScheme(preset, darkTheme = dark)
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

    @Test
    fun amoledShipsDarkOnly() {
        assertTrue("AMOLED must ship a dark scheme", AmoledTheme.darkScheme != null)
        assertTrue("AMOLED must not ship a light scheme", AmoledTheme.lightScheme == null)
        assertTrue("AMOLED must ship dark status colors", AmoledTheme.darkStatus != null)
        assertTrue("AMOLED must not ship light status colors", AmoledTheme.lightStatus == null)
    }

    @Test
    fun everyPresetResolvesItsDeclaredModes() {
        ThemeRegistry.forEach { (preset, theme) ->
            listOf(true, false).forEach { dark ->
                assertSame(
                    "$preset dark=$dark must resolve its palette or Default fallback",
                    theme.schemeFor(dark) ?: DefaultTheme.schemeFor(dark),
                    resolveColorScheme(preset, dark),
                )
                assertSame(
                    "$preset dark=$dark must resolve its status or Default fallback",
                    theme.statusFor(dark) ?: DefaultTheme.statusFor(dark),
                    resolveStatusColors(preset, dark),
                )
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
            primary = Color(0xFF000000L + 1),
            onPrimary = Color(0xFF000000L + 2),
            primaryContainer = Color(0xFF000000L + 3),
            onPrimaryContainer = Color(0xFF000000L + 4),
            secondary = Color(0xFF000000L + 5),
            onSecondary = Color(0xFF000000L + 6),
            secondaryContainer = Color(0xFF000000L + 7),
            onSecondaryContainer = Color(0xFF000000L + 8),
            tertiary = Color(0xFF000000L + 9),
            onTertiary = Color(0xFF000000L + 10),
            tertiaryContainer = Color(0xFF000000L + 11),
            onTertiaryContainer = Color(0xFF000000L + 12),
            background = Color(0xFF000000L + 13),
            onBackground = Color(0xFF000000L + 14),
            surface = Color(0xFF000000L + 15),
            onSurface = Color(0xFF000000L + 16),
            surfaceVariant = Color(0xFF000000L + 17),
            onSurfaceVariant = Color(0xFF000000L + 18),
            surfaceDim = Color(0xFF000000L + 19),
            surfaceBright = Color(0xFF000000L + 20),
            primaryFixed = Color(0xFF000000L + 21),
            primaryFixedDim = Color(0xFF000000L + 22),
            onPrimaryFixed = Color(0xFF000000L + 23),
            onPrimaryFixedVariant = Color(0xFF000000L + 24),
            secondaryFixed = Color(0xFF000000L + 25),
            secondaryFixedDim = Color(0xFF000000L + 26),
            onSecondaryFixed = Color(0xFF000000L + 27),
            onSecondaryFixedVariant = Color(0xFF000000L + 28),
            tertiaryFixed = Color(0xFF000000L + 29),
            tertiaryFixedDim = Color(0xFF000000L + 30),
            onTertiaryFixed = Color(0xFF000000L + 31),
            onTertiaryFixedVariant = Color(0xFF000000L + 32),
            surfaceContainerLowest = Color(0xFF000000L + 33),
            surfaceContainerLow = Color(0xFF000000L + 34),
            surfaceContainer = Color(0xFF000000L + 35),
            surfaceContainerHigh = Color(0xFF000000L + 36),
            surfaceContainerHighest = Color(0xFF000000L + 37),
            inverseSurface = Color(0xFF000000L + 38),
            inverseOnSurface = Color(0xFF000000L + 39),
            inversePrimary = Color(0xFF000000L + 40),
            outline = Color(0xFF000000L + 41),
            outlineVariant = Color(0xFF000000L + 42),
            scrim = Color(0xFF000000L + 43),
            status =
                HermesStatusColors(
                    success = Color(0xFF000000L + 100),
                    successContainer = Color(0xFF000000L + 101),
                    onSuccess = Color(0xFF000000L + 102),
                    warning = Color(0xFF000000L + 103),
                    warningContainer = Color(0xFF000000L + 104),
                    onWarning = Color(0xFF000000L + 105),
                    error = Color(0xFF000000L + 106),
                    errorContainer = Color(0xFF000000L + 107),
                    onError = Color(0xFF000000L + 108),
                    onErrorContainer = Color(0xFF000000L + 109),
                    info = Color(0xFF000000L + 110),
                    infoContainer = Color(0xFF000000L + 111),
                    onInfo = Color(0xFF000000L + 112),
                ),
        )
}
