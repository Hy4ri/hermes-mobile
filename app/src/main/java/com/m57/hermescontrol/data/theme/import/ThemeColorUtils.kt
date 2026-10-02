package com.m57.hermescontrol.data.theme.import

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.m57.hermescontrol.theme.parseHexColor

/**
 * Color math for the marketplace theme apply pipeline (t_f3c6f528).
 *
 * Ports the desktop VS Code → theme converter semantics
 * (`apps/desktop/src/themes/vscode.ts` + `@hermes/shared/color`):
 * sRGB lerp mixing, WCAG relative-luminance contrast, accent contrast
 * enforcement, and readable-ink selection. All functions are pure and
 * unit-testable.
 */
object ThemeColorUtils {
    /** sRGB lerp `a → b` by [t] in [0,1]. */
    fun mix(
        a: Color,
        b: Color,
        t: Float,
    ): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * k,
            green = a.green + (b.green - a.green) * k,
            blue = a.blue + (b.blue - a.blue) * k,
            alpha = a.alpha + (b.alpha - a.alpha) * k,
        )
    }

    /** WCAG contrast ratio of [a] over [b] (>= 1). */
    fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** Black or white, whichever reads better on [background]. */
    fun readableInk(background: Color): Color = if (background.luminance() > 0.4f) Color.Black else Color.White

    /**
     * Nudge [foreground] toward black/white until it reaches [minContrast]
     * against [background]. Returns the original when it already passes.
     * Bounded walk so pathological inputs terminate.
     */
    fun ensureContrast(
        foreground: Color,
        background: Color,
        minContrast: Float,
    ): Color {
        if (contrast(foreground, background) >= minContrast) return foreground
        val toLight = mix(foreground, Color.White, 0.12f)
        val toDark = mix(foreground, Color.Black, 0.12f)
        val lightGain = contrast(toLight, background) - contrast(foreground, background)
        val darkGain = contrast(toDark, background) - contrast(foreground, background)
        var current = if (lightGain >= darkGain) toLight else toDark
        val target = if (lightGain >= darkGain) Color.White else Color.Black
        repeat(20) {
            if (contrast(current, background) >= minContrast) return current
            current = mix(current, target, 0.15f)
        }
        return current
    }

    /**
     * Parse [raw] hex (`#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`) flattened over
     * [backdrop]. VS Code theme colors are RGBA — an 8-digit value is
     * `#rrggbbaa` with alpha as the *last* two hex digits (matching the desktop
     * `normalizeHex`); 4-digit `#rgba` shorthand expands to the same. Alpha
     * composites over the backdrop like the desktop; null when unparseable.
     */
    fun parseLayer(
        raw: String?,
        backdrop: Color,
    ): Color? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim().removePrefix("#")
        // Expand 3/4-digit shorthand to full width, then handle RGB / RGBA.
        val expanded =
            when (clean.length) {
                3, 4 -> clean.map { "$it$it" }.joinToString(separator = "")
                else -> clean
            }
        return try {
            when (expanded.length) {
                6 -> {
                    parseHexColor("#$expanded", Color.Unspecified).takeIf { it != Color.Unspecified }
                }

                8 -> {
                    val rgb = expanded.substring(0, 6)
                    val alpha = expanded.substring(6, 8).toInt(16) / 255f
                    if (alpha >= 1f) {
                        parseHexColor("#$rgb", Color.Unspecified).takeIf { it != Color.Unspecified }
                    } else {
                        val src = parseHexColor("#$rgb", Color.Unspecified)
                        if (src == Color.Unspecified) {
                            null
                        } else {
                            mix(src, backdrop, 1f - alpha).copy(alpha = 1f)
                        }
                    }
                }

                else -> {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
