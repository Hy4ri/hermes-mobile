package com.m57.hermescontrol.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.m57.hermescontrol.theme.ThemePreset
import com.m57.hermescontrol.theme.resolveColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

class CaPickerDividerTest {
    @Test
    fun `decorative divider stays distinguishable and softer than outline in every theme`() {
        ThemePreset.entries.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val colors = resolveColorScheme(preset, dark)
                val background = colors.surfaceContainerHigh
                val divider = caCertificateDividerColor(colors.outline).compositeOver(background)
                val contrast = contrast(divider, background)
                assertTrue("$preset dark=$dark: divider disappeared", contrast > 1.05f)
                assertTrue(
                    "$preset dark=$dark: decorative divider is too prominent",
                    contrast < contrast(colors.outline, background),
                )
            }
        }
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Float = (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
}
