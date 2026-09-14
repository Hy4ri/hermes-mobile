package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.theme.resolveColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrast gate for the chat composer across every preset in both modes.
 *
 * - Primary text and control labels (onSurface) >= 4.5:1 — WCAG AA text.
 * - Secondary text (placeholder, reasoning level) >= 3:1 — same bar as the
 *   full-bleed header gate in ThemePaletteTest.
 * - Action button against the card, and its glyph against the button >= 3:1.
 * - Flat controls and the card edge must stay visibly distinct (>= 1.15:1).
 *   Their labels and icons carry the WCAG 1.4.11 contrast; this floor catches
 *   presets whose surface tiers collapse (Nord's High == Highest rendered the
 *   controls invisible).
 */
class ComposerPaletteTest {
    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val l1 = a.luminance()
        val l2 = b.luminance()
        return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
    }

    private fun forEveryPresetMode(block: (String, ComposerPalette, Color) -> Unit) {
        ThemePreset.entries.forEach { preset ->
            listOf(true, false).forEach { dark ->
                val scheme = resolveColorScheme(preset, darkTheme = dark)
                block("$preset dark=$dark", composerPalette(scheme), scheme.background)
            }
        }
    }

    private fun assertContrast(
        label: String,
        a: Color,
        b: Color,
        min: Float,
    ) {
        val ratio = contrast(a, b)
        assertTrue("$label contrast %.2f must be >= $min".format(ratio), ratio >= min)
    }

    @Test
    fun textMeetsContrastInEveryPresetMode() {
        forEveryPresetMode { mode, p, _ ->
            assertContrast("$mode input text/card", p.text, p.card, 4.5f)
            assertContrast("$mode placeholder/card", p.placeholder, p.card, 3f)
            assertContrast("$mode control label/control", p.onControl, p.control, 4.5f)
            assertContrast("$mode reasoning level/control", p.onControlVariant, p.control, 3f)
        }
    }

    @Test
    fun actionButtonMeetsContrastInEveryPresetMode() {
        forEveryPresetMode { mode, p, _ ->
            assertContrast("$mode action/card", p.action, p.card, 3f)
            assertContrast("$mode action glyph/action", p.onAction, p.action, 3f)
        }
    }

    @Test
    fun controlsAndCardEdgeStayVisibleInEveryPresetMode() {
        forEveryPresetMode { mode, p, background ->
            assertContrast("$mode control/card", p.control, p.card, 1.15f)
            assertContrast("$mode card border/background", p.cardBorder, background, 1.15f)
        }
    }
}
