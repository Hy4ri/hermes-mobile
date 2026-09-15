package com.m57.hermescontrol.ui.sessions.components

import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.theme.resolveColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrast gate for the history card across every preset in both modes. Every line on the
 * card is body-sized or smaller, so title and secondary text must both clear WCAG AA 4.5:1.
 */
class SessionCardContrastTest {
    private fun forEveryPresetMode(block: (String, androidx.compose.material3.ColorScheme) -> Unit) {
        ThemePreset.entries.forEach { preset ->
            listOf(true, false).forEach { dark ->
                block("$preset dark=$dark", resolveColorScheme(preset, darkTheme = dark))
            }
        }
    }

    @Test
    fun cardTextMeetsContrastInEveryPresetMode() {
        forEveryPresetMode { mode, scheme ->
            val palette = sessionCardPalette(scheme)
            listOf("title" to palette.title, "secondary" to palette.secondary).forEach { (role, color) ->
                val ratio = contrastRatio(color, palette.card)
                assertTrue(
                    "$mode $role contrast %.2f must be >= 4.5".format(ratio),
                    ratio >= SESSION_CARD_MIN_TEXT_CONTRAST,
                )
            }
        }
    }

    @Test
    fun secondaryKeepsTheThemeColorWhenItAlreadyPasses() {
        forEveryPresetMode { mode, scheme ->
            if (contrastRatio(scheme.onSurfaceVariant, scheme.surfaceContainer) >= SESSION_CARD_MIN_TEXT_CONTRAST) {
                assertEquals(mode, scheme.onSurfaceVariant, sessionCardPalette(scheme).secondary)
            }
        }
    }
}
