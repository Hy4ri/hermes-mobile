package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.onColorFor

/** Highlight literal search hits in plain transcript labels, reasoning, and tool summaries. */
internal fun buildHighlightedString(
    text: String,
    query: String,
    isCurrentMatch: Boolean = false,
    statusColors: HermesStatusColors,
): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)
    val (highlightColor, highlightText) =
        if (isCurrentMatch) {
            statusColors.warning to statusColors.onWarning
        } else {
            statusColors.warningContainer to onColorFor(statusColors.warningContainer)
        }
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val matchStart = text.indexOf(query, i, ignoreCase = true)
            if (matchStart == -1) {
                append(text.substring(i))
                break
            }
            append(text.substring(i, matchStart))
            withStyle(SpanStyle(background = highlightColor, color = highlightText)) {
                append(text.substring(matchStart, matchStart + query.length))
            }
            i = matchStart + query.length
        }
    }
}
