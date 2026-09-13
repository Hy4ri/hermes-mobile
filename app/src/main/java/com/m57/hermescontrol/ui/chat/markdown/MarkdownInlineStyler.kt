package com.m57.hermescontrol.ui.chat.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.SearchHighlightColors
import com.m57.hermescontrol.util.BidiUtils

object MarkdownInlineStyler {
    private val URL_PATTERN =
        Regex(
            """https?://[^\s<>'"\)\],;]+(?<![.\),;])""",
            RegexOption.IGNORE_CASE,
        )

    fun parseInline(
        text: String,
        textColor: Color,
        searchQuery: String,
        isCurrentMatch: Boolean,
        linkColor: Color,
        highlights: SearchHighlightColors,
        isRtl: Boolean = BidiUtils.isRtlText(text),
    ): AnnotatedString =
        parseInlineSource(
            text =
                splitInlineMath(text).joinToString(separator = "") { segment ->
                    when (segment) {
                        is InlineMathSegment.Math -> segment.latex
                        is InlineMathSegment.Text -> segment.value
                    }
                },
            textColor = textColor,
            searchQuery = searchQuery,
            isCurrentMatch = isCurrentMatch,
            linkColor = linkColor,
            highlights = highlights,
            isRtl = isRtl,
        )

    fun parseInlineSource(
        text: String,
        textColor: Color,
        searchQuery: String,
        isCurrentMatch: Boolean,
        linkColor: Color,
        highlights: SearchHighlightColors,
        isRtl: Boolean = BidiUtils.isRtlText(text),
    ): AnnotatedString {
        val searchHighlightColor =
            if (isCurrentMatch) {
                highlights.currentSearchBackground to highlights.currentSearchForeground
            } else {
                highlights.searchBackground to highlights.searchForeground
            }

        return buildAnnotatedString {
            var i = 0
            val src = text
            while (i < src.length) {
                when {
                    // ***bold italic***
                    src.startsWith("***", i) -> {
                        val end = src.indexOf("***", i + 3)
                        if (end != -1) {
                            val raw = src.substring(i + 3, end)
                            val toAppend = if (isRtl) BidiUtils.wrapLtrIsolate(raw) else raw
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                                append(toAppend)
                            }
                            i = end + 3
                        } else {
                            append(src[i])
                            i++
                        }
                    }

                    // **bold**
                    src.startsWith("**", i) -> {
                        val end = src.indexOf("**", i + 2)
                        if (end != -1) {
                            val raw = src.substring(i + 2, end)
                            val toAppend = if (isRtl) BidiUtils.wrapLtrIsolate(raw) else raw
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(toAppend)
                            }
                            i = end + 2
                        } else {
                            append(src[i])
                            i++
                        }
                    }

                    // ~~strike~~
                    src.startsWith("~~", i) -> {
                        val end = src.indexOf("~~", i + 2)
                        if (end != -1) {
                            val raw = src.substring(i + 2, end)
                            val toAppend = if (isRtl) BidiUtils.wrapLtrIsolate(raw) else raw
                            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                                append(toAppend)
                            }
                            i = end + 2
                        } else {
                            append(src[i])
                            i++
                        }
                    }

                    // *italic*
                    src.startsWith("*", i) -> {
                        val end = src.indexOf('*', i + 1)
                        if (end != -1 && end > i + 1) {
                            val raw = src.substring(i + 1, end)
                            val toAppend = if (isRtl) BidiUtils.wrapLtrIsolate(raw) else raw
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                append(toAppend)
                            }
                            i = end + 1
                        } else {
                            append(src[i])
                            i++
                        }
                    }

                    // ==highlight==
                    src.startsWith("==", i) -> {
                        val end = src.indexOf("==", i + 2)
                        if (end != -1) {
                            val raw = src.substring(i + 2, end)
                            val toAppend = if (isRtl) BidiUtils.wrapLtrIsolate(raw) else raw
                            withStyle(SpanStyle(background = highlights.markupBackground)) {
                                append(toAppend)
                            }
                            i = end + 2
                        } else {
                            append(src[i])
                            i++
                        }
                    }

                    // ^superscript^
                    src.startsWith("^", i) -> {
                        val end = src.indexOf('^', i + 1)
                        if (end != -1 && end > i + 1) {
                            withStyle(SpanStyle(baselineShift = BaselineShift.Superscript)) {
                                append(src.substring(i + 1, end))
                            }
                            i = end + 1
                        } else {
                            append(src[i])
                            i++
                        }
                    }

                    // ~subscript~ (single tilde; ~~ handled above)
                    src.startsWith("~", i) -> {
                        val end = src.indexOf('~', i + 1)
                        if (end != -1 && end > i + 1) {
                            withStyle(SpanStyle(baselineShift = BaselineShift.Subscript)) {
                                append(src.substring(i + 1, end))
                            }
                            i = end + 1
                        } else {
                            append(src[i])
                            i++
                        }
                    }

                    // <kbd>key</kbd>
                    src.startsWith("<kbd>", i) -> {
                        val end = src.indexOf("</kbd>", i)
                        if (end != -1) {
                            val raw = src.substring(i + 5, end)
                            val toAppend = if (isRtr(isRtl)) BidiUtils.wrapLtrIsolate(raw) else raw
                            withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    background = textColor.copy(alpha = 0.12f),
                                ),
                            ) {
                                append(toAppend)
                            }
                            i = end + 6
                        } else {
                            append(src[i])
                            i++
                        }
                    }

                    // [^ref] footnote marker -> superscript
                    src.startsWith("[^", i) -> {
                        val close = src.indexOf(']', i)
                        if (close != -1) {
                            val id = src.substring(i + 2, close)
                            withStyle(
                                SpanStyle(
                                    baselineShift = BaselineShift.Superscript,
                                    color = linkColor,
                                    fontWeight = FontWeight.Bold,
                                ),
                            ) {
                                append("[$id]")
                            }
                            i = close + 1
                        } else {
                            append(src[i])
                            i++
                        }
                    }

                    // [text](url)
                    src.startsWith("[", i) -> {
                        val close = src.indexOf(']', i)
                        if (close != -1 && close + 1 < src.length && src[close + 1] == '(') {
                            val urlEnd = src.indexOf(')', close + 2)
                            if (urlEnd != -1) {
                                val label = src.substring(i + 1, close)
                                val labelToAppend = if (isRtl) BidiUtils.wrapLtrIsolate(label) else label
                                val url = src.substring(close + 2, urlEnd)
                                pushLink(LinkAnnotation.Url(url))
                                withStyle(
                                    SpanStyle(
                                        color = linkColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ) {
                                    append(labelToAppend)
                                }
                                pop()
                                i = urlEnd + 1
                            } else {
                                append(src[i])
                                i++
                            }
                        } else {
                            append(src[i])
                            i++
                        }
                    }

                    // `inline code`
                    src.startsWith("`", i) -> {
                        val end = src.indexOf('`', i + 1)
                        if (end != -1) {
                            val raw = src.substring(i + 1, end)
                            val toAppend = if (isRtl) BidiUtils.wrapLtrIsolate(raw) else raw
                            withStyle(
                                SpanStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    background = textColor.copy(alpha = 0.08f),
                                ),
                            ) {
                                append(toAppend)
                            }
                            i = end + 1
                        } else {
                            append(src[i])
                            i++
                        }
                    }

                    // bare URL
                    URL_PATTERN.matchAt(src, i) != null -> {
                        val match = URL_PATTERN.matchAt(src, i)!!
                        val url = match.value
                        val urlToAppend = if (isRtl) BidiUtils.wrapLtrIsolate(url) else url
                        pushLink(LinkAnnotation.Url(url))
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) {
                            append(urlToAppend)
                        }
                        pop()
                        i = match.range.last + 1
                    }

                    // Plain text / words in RTL
                    isRtl && Character.isLetterOrDigit(src.codePointAt(i)) &&
                        Character.getDirectionality(src.codePointAt(i)) == Character.DIRECTIONALITY_LEFT_TO_RIGHT -> {
                        var end = i + Character.charCount(src.codePointAt(i))
                        while (end < src.length) {
                            val cp = src.codePointAt(end)
                            val dir = Character.getDirectionality(cp)
                            if (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT ||
                                dir == Character.DIRECTIONALITY_EUROPEAN_NUMBER ||
                                dir == Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR ||
                                dir == Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR ||
                                (
                                    dir == Character.DIRECTIONALITY_WHITESPACE && end + 1 < src.length &&
                                        Character.getDirectionality(src.codePointAt(end + 1)) ==
                                        Character.DIRECTIONALITY_LEFT_TO_RIGHT
                                )
                            ) {
                                end += Character.charCount(cp)
                            } else {
                                break
                            }
                        }
                        val ltrSnippet = src.substring(i, end)
                        append(BidiUtils.wrapLtrIsolate(ltrSnippet))
                        i = end
                    }

                    // search highlight
                    searchQuery.isNotEmpty() &&
                        src.regionMatches(i, searchQuery, 0, searchQuery.length, ignoreCase = true) -> {
                        withStyle(
                            SpanStyle(
                                background = searchHighlightColor.first,
                                color = searchHighlightColor.second,
                            ),
                        ) {
                            append(src.substring(i, i + searchQuery.length))
                        }
                        i += searchQuery.length
                    }

                    else -> {
                        append(src[i])
                        i++
                    }
                }
            }
        }
    }

    private fun isRtr(isRtl: Boolean): Boolean = isRtl
}
