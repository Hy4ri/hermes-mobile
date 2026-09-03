package com.m57.hermescontrol.util

import androidx.compose.ui.unit.LayoutDirection

/**
 * Bidirectional (BiDi) text utilities for mixed Arabic/English content,
 * dynamic layout direction resolution, and Unicode directional isolates.
 */
object BidiUtils {
    const val LRI = "\u2066" // Left-to-Right Isolate
    const val RLI = "\u2067" // Right-to-Left Isolate
    const val FSI = "\u2068" // First-Strong Isolate
    const val PDI = "\u2069" // Pop Directional Isolate
    const val LRM = "\u200E" // Left-to-Right Mark
    const val RLM = "\u200F" // Right-to-Left Mark

    enum class BidiDirection {
        LTR,
        RTL,
        NEUTRAL,
    }

    /**
     * Determines whether the given text should be rendered as RTL.
     *
     * 1. If the first strong directional character is RTL (Arabic/Hebrew), returns true.
     * 2. If the first strong directional character is LTR (e.g. an Arabic sentence starting with
     *    an English term like "Android هو نظام..."), returns true if RTL characters outnumber LTR characters.
     * 3. If there are no strong characters (empty, numbers, emojis, symbols only), returns false.
     */
    fun isRtlText(text: CharSequence): Boolean {
        var firstStrongRtl: Boolean? = null
        var rtlCount = 0
        var ltrCount = 0

        var i = 0
        val len = text.length
        while (i < len) {
            val cp = Character.codePointAt(text, i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> {
                    if (firstStrongRtl == null) firstStrongRtl = true
                    rtlCount++
                }

                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> {
                    if (firstStrongRtl == null) firstStrongRtl = false
                    ltrCount++
                }
            }
            i += Character.charCount(cp)
        }

        return when {
            firstStrongRtl == true -> true
            firstStrongRtl == false -> rtlCount > ltrCount
            else -> false
        }
    }

    /**
     * Detects the dominant [BidiDirection] of [text].
     * Returns [BidiDirection.NEUTRAL] when no strong directional characters exist.
     */
    fun detectTextDirection(text: CharSequence): BidiDirection {
        var hasStrong = false
        var firstStrongRtl: Boolean? = null
        var rtlCount = 0
        var ltrCount = 0

        var i = 0
        val len = text.length
        while (i < len) {
            val cp = Character.codePointAt(text, i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> {
                    hasStrong = true
                    if (firstStrongRtl == null) firstStrongRtl = true
                    rtlCount++
                }

                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> {
                    hasStrong = true
                    if (firstStrongRtl == null) firstStrongRtl = false
                    ltrCount++
                }
            }
            i += Character.charCount(cp)
        }

        if (!hasStrong) return BidiDirection.NEUTRAL

        val isRtl =
            when {
                firstStrongRtl == true -> true
                firstStrongRtl == false -> rtlCount > ltrCount
                else -> false
            }
        return if (isRtl) BidiDirection.RTL else BidiDirection.LTR
    }

    /**
     * Resolves the Compose [LayoutDirection] for [text].
     * If [text] is neutral/empty, falls back to [fallback].
     */
    fun resolveLayoutDirection(
        text: CharSequence,
        fallback: LayoutDirection = LayoutDirection.Ltr,
    ): LayoutDirection =
        when (detectTextDirection(text)) {
            BidiDirection.RTL -> LayoutDirection.Rtl
            BidiDirection.LTR -> LayoutDirection.Ltr
            BidiDirection.NEUTRAL -> fallback
        }

    /**
     * Returns true if [text] contains strong LTR characters (Latin letters, digits)
     * and no strong RTL characters (Arabic, Hebrew).
     */
    fun isLtrSnippet(text: CharSequence): Boolean {
        var hasLtr = false
        var i = 0
        val len = text.length
        while (i < len) {
            val cp = Character.codePointAt(text, i)
            when (Character.getDirectionality(cp)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> return false

                Character.DIRECTIONALITY_LEFT_TO_RIGHT -> hasLtr = true
            }
            i += Character.charCount(cp)
        }
        return hasLtr
    }

    /**
     * Wraps [text] in a Left-to-Right Isolate (LRI ... PDI) if [isLtrSnippet] is true.
     * Used to prevent inline English terms and code from bleeding into surrounding RTL runs.
     */
    fun wrapLtrIsolate(text: String): String = if (isLtrSnippet(text)) "$LRI$text$PDI" else text

    /**
     * Appends a Right-to-Left Mark (RLM) to [text] if it is RTL, ensuring trailing
     * punctuation and neutral emojis anchor to the visual end of the RTL sentence.
     */
    fun anchorTrailingRtl(text: String): String = if (isRtlText(text) && !text.endsWith(RLM)) "$text$RLM" else text
}
