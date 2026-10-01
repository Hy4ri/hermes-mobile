package com.m57.hermescontrol.ui.chat.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.m57.hermescontrol.theme.CodeComment
import com.m57.hermescontrol.theme.CodeKeyword
import com.m57.hermescontrol.theme.CodeNumber
import com.m57.hermescontrol.theme.CodePunctuation
import com.m57.hermescontrol.theme.CodeString

// Hoisted token patterns for syntax highlighting — compiled once.
private val HIGHLIGHT_TOKENS =
    listOf(
        TokenPattern(Regex("""//[^\n]*"""), CodeComment),
        TokenPattern(Regex("""/\*[\s\S]*?\*/"""), CodeComment),
        TokenPattern(Regex(""""[^"\\]*(\\.[^"\\]*)*"(?=\s*:)"""), CodeKeyword),
        TokenPattern(Regex(""""[^"\\]*(\\.[^"\\]*)*""""), CodeString),
        TokenPattern(Regex("""'[^'\\]*(\\.[^'\\]*)*'"""), CodeString),
        TokenPattern(Regex("""`[^`\\]*(\\.[^`\\]*)*`"""), CodeString),
        TokenPattern(Regex("""\b0[xX][0-9a-fA-F]+\b"""), CodeNumber),
        TokenPattern(Regex("""\b\d+\.?\d*(?:[eE][+-]?\d+)?\b"""), CodeNumber),
        TokenPattern(
            Regex(
                """\b(?:val|var|fun|class|object|interface|enum|data|sealed|open|abstract|""" +
                    """override|private|protected|public|internal|import|package|""" +
                    """if|else|when|for|while|do|return|throw|try|catch|finally|""" +
                    """true|false|null|this|super|is|in|as|typealias|companion|""" +
                    """init|constructor|by|get|set|field|value|suspend|inline|""" +
                    """infix|operator|tailrec|external|annotation)\b""",
            ),
            CodeKeyword,
        ),
        TokenPattern(Regex("""[{}()\[\];:.]"""), CodePunctuation),
    )

/**
 * Builds an [AnnotatedString] from [code] with syntax highlighting colours
 * applied via token regexes. Covers keywords, strings, comments, numbers,
 * and punctuation — everything else remains the default light-grey.
 */
internal fun highlightSyntax(code: String): AnnotatedString =
    buildAnnotatedString {
        val tokens = HIGHLIGHT_TOKENS
        var lastIndex = 0
        val matches = mutableListOf<Pair<IntRange, Color>>()

        for (pattern in tokens) {
            for (match in pattern.regex.findAll(code)) {
                matches.add(match.range to pattern.color)
            }
        }

        matches.sortBy { it.first.first }
        // Resolve overlaps: later-in-text wins for same-pos, else first-match
        val resolved = mutableListOf<Pair<IntRange, Color>>()
        for (m in matches) {
            if (resolved.isEmpty()) {
                resolved.add(m)
            } else {
                val last = resolved.last()
                if (m.first.first >= last.first.last + 1) {
                    // Non-overlapping — safe add
                    resolved.add(m)
                } else if (m.first.first > last.first.first) {
                    // Overlap, this match starts later — it wins the overlapping portion
                    // Keep the last match's pre-overlap, then replace
                    resolved.removeAt(resolved.lastIndex)
                    // Split: keep text before overlap from last match
                    if (last.first.first < m.first.first) {
                        resolved.add(last.first.first..<m.first.first to last.second)
                    }
                    resolved.add(m)
                    // If last match extends beyond this match, add remainder
                    if (last.first.last > m.first.last) {
                        resolved.add(m.first.last + 1..last.first.last to last.second)
                    }
                }
                // else same start position — first match wins, skip this one
            }
        }

        var pos = 0
        for ((range, color) in resolved) {
            if (range.first > pos) {
                append(code.substring(pos, range.first))
            }
            withStyle(SpanStyle(color = color)) {
                append(code.substring(range.first, range.last + 1))
            }
            pos = range.last + 1
        }
        if (pos < code.length) {
            append(code.substring(pos))
        }
    }

private data class TokenPattern(
    val regex: Regex,
    val color: Color,
)
