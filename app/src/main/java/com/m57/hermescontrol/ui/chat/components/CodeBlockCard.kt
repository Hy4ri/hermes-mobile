package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.CodeTerminalText
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.SearchHighlightColors
import com.m57.hermescontrol.theme.searchHighlightColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Terminal-style card for a fenced code block with syntax highlighting,
 * a language badge, and a copy button.
 */
@Composable
fun CodeBlockCard(
    code: String,
    language: String?,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
) {
    val highlights = searchHighlightColors(LocalHermesStatusColors.current)
    val highlighted by produceState(
        initialValue = remember(code) { AnnotatedString(code) },
        key1 = code,
    ) {
        value =
            withContext(Dispatchers.Default) {
                highlightSyntax(code)
            }
    }

    CodeTerminalCard(
        textToCopy = code,
        modifier = modifier,
        testTag = "code_block",
        title = language?.takeIf { it.isNotBlank() }?.uppercase(),
        onCopy = onCopy,
        copyContentDescription = "Copy code",
    ) {
        Text(
            text =
                remember(highlighted, searchQuery, isCurrentMatch, highlights) {
                    highlighted.withSearchHighlights(searchQuery, isCurrentMatch, highlights)
                },
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = CodeTerminalText,
            softWrap = false,
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Overlay search-hit spans on an already syntax-highlighted string, keeping its existing styles. */
private fun AnnotatedString.withSearchHighlights(
    query: String,
    isCurrent: Boolean,
    highlights: SearchHighlightColors,
): AnnotatedString {
    if (query.isEmpty()) return this
    val (bg, fg) =
        if (isCurrent) {
            highlights.currentSearchBackground to highlights.currentSearchForeground
        } else {
            highlights.searchBackground to highlights.searchForeground
        }
    return buildAnnotatedString {
        append(this@withSearchHighlights)
        var from = 0
        while (true) {
            val hit = text.indexOf(query, from, ignoreCase = true)
            if (hit < 0) break
            addStyle(SpanStyle(background = bg, color = fg), hit, hit + query.length)
            from = hit + query.length
        }
    }
}
