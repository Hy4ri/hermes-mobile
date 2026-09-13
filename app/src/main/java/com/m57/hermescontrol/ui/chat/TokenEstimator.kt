package com.m57.hermescontrol.ui.chat

import java.util.Locale

/**
 * Token count estimation and formatting utilities.
 *
 * The estimation algorithm matches the backend's `estimate_tokens_rough`
 * in `agent/model_metadata.py`:
 * - CJK ideographs, Hangul, Kana count as ~1 token each.
 * - Non-CJK characters count as ceil(UTF-8 bytes / 4).
 */
object TokenEstimator {
    fun estimate(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var dense = 0
        var nonDenseByteCount = 0
        for (i in 0 until text.length) {
            val ch = text[i]
            if (isCjkDense(ch)) {
                dense++
            } else {
                val code = ch.code
                nonDenseByteCount +=
                    when {
                        code <= 0x7F -> 1
                        code <= 0x7FF -> 2
                        code <= 0xFFFF -> 3
                        else -> 4
                    }
            }
        }
        return dense + ((nonDenseByteCount + 3) / 4)
    }

    private fun isCjkDense(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) ||
            (code in 0x3400..0x4DBF) ||
            (code in 0xAC00..0xD7AF) ||
            (code in 0x1100..0x11FF) ||
            (code in 0x3040..0x309F) ||
            (code in 0x30A0..0x30FF)
    }

    fun formatTokenCount(tokens: Int): String =
        when {
            tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
            tokens >= 10_000 -> String.format(Locale.US, "%.1fk", tokens / 1_000.0)
            tokens >= 1_000 -> String.format(Locale.US, "%,d", tokens)
            else -> tokens.toString()
        }

    fun formatTps(tps: Double): String = Math.round(tps).toString()
}
