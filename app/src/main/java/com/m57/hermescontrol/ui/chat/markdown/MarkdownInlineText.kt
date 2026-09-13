package com.m57.hermescontrol.ui.chat.markdown

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.m57.hermescontrol.theme.SearchHighlightColors
import com.m57.hermescontrol.util.BidiUtils

@Composable
fun MarkdownInlineText(
    text: String,
    textColor: Color,
    latexMeasurer: LatexMeasurerState,
    style: TextStyle,
    searchQuery: String,
    isCurrentMatch: Boolean,
    linkColor: Color,
    highlights: SearchHighlightColors,
    modifier: Modifier = Modifier,
) {
    val isRtl = remember(text) { BidiUtils.isRtlText(text) }
    val direction = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
    val resolvedStyle =
        style.copy(
            textDirection = if (isRtl) TextDirection.Rtl else TextDirection.Ltr,
        )
    val processedText =
        remember(text, isRtl) {
            if (isRtl) BidiUtils.anchorTrailingRtl(text) else text
        }

    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        val segments = remember(processedText) { splitInlineMath(processedText) }
        if (segments.none { it is InlineMathSegment.Math }) {
            Text(
                text =
                    remember(processedText, searchQuery, isCurrentMatch, textColor, linkColor, isRtl) {
                        MarkdownInlineStyler.parseInlineSource(
                            text = processedText,
                            textColor = textColor,
                            searchQuery = searchQuery,
                            isCurrentMatch = isCurrentMatch,
                            linkColor = linkColor,
                            highlights = highlights,
                            isRtl = isRtl,
                        )
                    },
                color = textColor,
                style = resolvedStyle,
                modifier = modifier,
            )
            return@CompositionLocalProvider
        }

        val config =
            LatexConfig(
                fontSize = style.fontSize,
                theme = LatexTheme.light(color = textColor, backgroundColor = Color.Transparent),
                accessibilityEnabled = true,
            )
        val inlineContent = mutableMapOf<String, InlineTextContent>()
        segments.forEachIndexed { index, segment ->
            if (segment is InlineMathSegment.Math) {
                latexMeasurer.inlineContent(segment.latex, config)?.let { inlineContent["latex-$index"] = it }
            }
        }
        val annotated =
            buildAnnotatedString {
                segments.forEachIndexed { index, segment ->
                    when (segment) {
                        is InlineMathSegment.Math -> {
                            val id = "latex-$index"
                            if (id in inlineContent) appendInlineContent(id, segment.latex) else append(segment.latex)
                        }

                        is InlineMathSegment.Text -> {
                            append(
                                MarkdownInlineStyler.parseInlineSource(
                                    text = segment.value,
                                    textColor = textColor,
                                    searchQuery = searchQuery,
                                    isCurrentMatch = isCurrentMatch,
                                    linkColor = linkColor,
                                    highlights = highlights,
                                    isRtl = isRtl,
                                ),
                            )
                        }
                    }
                }
            }
        Text(
            text = annotated,
            inlineContent = inlineContent,
            color = textColor,
            style = resolvedStyle,
            modifier = modifier,
        )
    }
}
