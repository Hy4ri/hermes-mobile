package com.m57.hermescontrol.ui.sessions.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Colors for the history card. Every secondary line (project, age, preview, footer) is
 * body-sized or smaller, so it needs 4.5:1 against the card. Some presets' onSurfaceVariant
 * falls short on surfaceContainer (Gruvbox, Catppuccin light); there the secondary color is
 * pulled toward onSurface just far enough to pass, keeping the hierarchy everywhere else.
 * Gated in SessionCardContrastTest.
 */
internal data class SessionCardPalette(
    val card: Color,
    val title: Color,
    val secondary: Color,
)

internal const val SESSION_CARD_MIN_TEXT_CONTRAST = 4.5f
private const val BLEND_STEPS = 20

internal fun sessionCardPalette(scheme: ColorScheme): SessionCardPalette {
    val card = scheme.surfaceContainer
    val secondary =
        (0..BLEND_STEPS)
            .asSequence()
            .map { step -> lerp(scheme.onSurfaceVariant, scheme.onSurface, step / BLEND_STEPS.toFloat()) }
            .firstOrNull { contrastRatio(it, card) >= SESSION_CARD_MIN_TEXT_CONTRAST }
            ?: scheme.onSurface
    return SessionCardPalette(card = card, title = scheme.onSurface, secondary = secondary)
}

@Composable
@ReadOnlyComposable
internal fun sessionCardPalette(): SessionCardPalette = sessionCardPalette(MaterialTheme.colorScheme)

internal fun contrastRatio(
    a: Color,
    b: Color,
): Float {
    val l1 = a.luminance()
    val l2 = b.luminance()
    return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
}
