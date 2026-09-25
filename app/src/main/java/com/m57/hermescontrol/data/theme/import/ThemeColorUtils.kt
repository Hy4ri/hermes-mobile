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
    fun mix(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * k,
            green = a.green + (b.green - a.green) * k,
            blue = a.blue + (b.blue - a.blue) * k,
            alpha = a.alpha + (b.alpha - a.alpha) * k,
        )
    }

    /** WCAG contrast ratio of [a] over [b] (>= 1). */
    fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /** Black or white, whichever reads better on [background]. */
    fun readableInk(background: Color): Color =
        if (background.luminance() > 0.4f) Color.Black else Color.White

    /**
     * Nudge [foreground] toward black/white until it reaches [minContrast]
     * against [background]. Returns the original when it already passes.
     * Bounded walk so pathological inputs terminate.
     */
    fun ensureContrast(foreground: Color, background: Color, minContrast: Float): Color {
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
     * Parse [raw] hex (`#rgb`, `#rrggbb`, `#aarrggbb`) flattened over
     * [backdrop]. Alpha-bearing values composite over the backdrop like the
     * desktop `normalizeHex`; null when unparseable.
     */
    fun parseLayer(raw: String?, backdrop: Color): Color? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.trim().removePrefix("#")
        return try {
            when (clean.length) {
                3 -> {
                    val r = "${clean[0]}${clean[0]}"
                    val g = "${clean[1]}${clean[1]}"
                    val b = "${clean[2]}${clean[2]}"
                    parseHexColor("#$r$g$b", Color.Unspecified).takeIf { it != Color.Unspecified }
                }
                6 -> parseHexColor("#$clean", Color.Unspecified).takeIf { it != Color.Unspecified }
                8 -> {
                    val argb = clean.toLong(16)
                    val alpha = ((argb shr 24) and 0xFF) / 255f
                    if (alpha >= 1f) {
                        Color((argb and 0xFFFFFFFF).toInt())
                    } else {
                        val src = Color((argb and 0xFFFFFFFF).toInt())
                        mix(src, backdrop, 1f - alpha).copy(alpha = 1f)
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
