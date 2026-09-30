package com.m57.hermescontrol.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enabled-state contract for the pinned Material 3 defaults, verified again by the device gallery. */
class ThemeComponentContrastTest {
    @Test
    fun everyResolvedPresetSupportsEnabledComponentStates() {
        ThemeRegistry.forEach { (preset, _) ->
            listOf(true, false).forEach { dark ->
                val c = resolveColorScheme(preset, dark)
                // PR #1417: these are component-state combinations, not just on-color pairs.
                val graphics =
                    listOf(
                        "switch on thumb/track" to (c.onPrimary to c.primary),
                        "switch on track/surface" to (c.primary to c.surface),
                        "switch off thumb/track" to (c.outline to c.surfaceContainerHighest),
                        "switch off border/surface" to (c.outline to c.surface),
                        "radio selected/surface" to (c.primary to c.surface),
                        "radio unselected/surface" to (c.onSurfaceVariant to c.surface),
                        "checkbox selected/surface" to (c.primary to c.surface),
                        "checkbox checkmark/box" to (c.onPrimary to c.primary),
                        "checkbox unselected/surface" to (c.onSurfaceVariant to c.surface),
                        "slider active/inactive" to (c.primary to c.secondaryContainer),
                        "field normal border/surface" to (c.outline to c.surface),
                        "field focused border/surface" to (c.primary to c.surface),
                        "field error border/surface" to (c.error to c.surface),
                        "segment selected border/container" to (c.outline to c.secondaryContainer),
                        "segment unselected border/surface" to (c.outline to c.surface),
                    )
                val text =
                    listOf(
                        "primary foreground/surface" to (c.primary to c.surface),
                        "primary foreground/background" to (c.primary to c.background),
                        "error foreground/surface" to (c.error to c.surface),
                        "error foreground/background" to (c.error to c.background),
                        "field normal label/surface" to (c.onSurfaceVariant to c.surface),
                        "field focused label/surface" to (c.primary to c.surface),
                        "field error label/surface" to (c.error to c.surface),
                        "segment selected content/container" to (c.onSecondaryContainer to c.secondaryContainer),
                        "segment unselected content/surface" to (c.onSurface to c.surface),
                    )
                graphics.forEach { (name, pair) -> checkPair(preset, dark, name, pair, 3f) }
                text.forEach { (name, pair) -> checkPair(preset, dark, name, pair, 4.5f) }
            }
        }
    }

    private fun checkPair(
        preset: ThemePreset,
        dark: Boolean,
        name: String,
        pair: Pair<Color, Color>,
        minimum: Float,
    ) {
        val a = pair.first.luminance()
        val b = pair.second.luminance()
        val ratio = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
        assertTrue("$preset dark=$dark $name is $ratio, expected >= $minimum", ratio >= minimum)
    }
}
