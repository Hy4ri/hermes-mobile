package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.LatexAutoWrap
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.remote.GatewayFileClient
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.SearchHighlightColors
import com.m57.hermescontrol.theme.searchHighlightColors
import com.m57.hermescontrol.ui.chat.markdown.FnNote
import com.m57.hermescontrol.ui.chat.markdown.InlineMathSegment
import com.m57.hermescontrol.ui.chat.markdown.MarkdownInlineStyler
import com.m57.hermescontrol.ui.chat.markdown.MarkdownInlineText
import com.m57.hermescontrol.ui.chat.markdown.MarkdownMediaResolver
import com.m57.hermescontrol.ui.chat.markdown.MarkdownTable
import com.m57.hermescontrol.ui.chat.markdown.MdBlock
import com.m57.hermescontrol.ui.chat.markdown.TableAlign
import com.m57.hermescontrol.ui.chat.markdown.parseBlocks
import com.m57.hermescontrol.ui.chat.markdown.splitInlineMath
import com.m57.hermescontrol.util.BidiUtils

private val URL_PATTERN = Regex("""https?://[^\s)>\[\]"'‘’]+""")
private val TABLE_COL_WIDTH = 160.dp

private fun bidiTextDirection(isRtl: Boolean): TextDirection = if (isRtl) TextDirection.Rtl else TextDirection.Ltr

/**
 * Renders chat assistant text as Markdown — but ONLY once the message has finished streaming.
 * While [isStreaming] is true we show the raw text to avoid flicker / re-parse churn, then swap
 * to the formatted view on completion (and for all historical/restored messages).
 *
 * Supports: fenced ```code``` blocks (horizontal scroll + copy), inline `code`, **bold**, *italic*,
 * ***bold italic***, ~~strike~~, ==highlight==, ^sup^ / ~sub~, <kbd>keys</kbd>, headings,
 * bullet/ordered/task lists, > blockquotes, definition lists, tables, --- rules, footnotes, and
 * [links](url) / bare URLs, and inline/display LaTeX math using `$…$` / `$$…$$`.
 */
@Composable
fun MarkdownText(
    text: String,
    textColor: Color,
    isStreaming: Boolean = false,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    modifier: Modifier = Modifier,
    onImageClick: (ImageViewerModel) -> Unit = {},
) {
    val statusColors = LocalHermesStatusColors.current
    val highlights = searchHighlightColors(statusColors)
    if (isStreaming) {
        val isRtl = remember(text) { BidiUtils.isRtlText(text) }
        val streamingDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
        CompositionLocalProvider(LocalLayoutDirection provides streamingDirection) {
            Text(
                text = if (isRtl) BidiUtils.anchorTrailingRtl(text) else text,
                color = textColor,
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        textDirection = bidiTextDirection(isRtl),
                    ),
                modifier = modifier,
            )
        }
        return
    }

    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(text) { parseBlocks(text) }
    val latexMeasurer = rememberLatexMeasurer()

    Column(modifier = modifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Code -> {
                    com.m57.hermescontrol.ui.chat.components.CodeBlockCard(
                        code = block.code,
                        language = block.language,
                        onCopy = { /* clipboard handled internally */ },
                    )
                }

                is MdBlock.Math -> {
                    LatexAutoWrap(
                        latex = block.latex,
                        config =
                            LatexConfig(
                                fontSize = 18.sp,
                                theme = LatexTheme.light(color = textColor, backgroundColor = Color.Transparent),
                                accessibilityEnabled = true,
                            ),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }

                is MdBlock.Hr -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = textColor.copy(alpha = 0.25f),
                    )
                }

                is MdBlock.Heading -> {
                    val fontSize =
                        when (block.level) {
                            1 -> 22.sp
                            2 -> 20.sp
                            3 -> 18.sp
                            4 -> 16.sp
                            5 -> 15.sp
                            else -> 14.sp
                        }
                    val isRtl = remember(block.text) { BidiUtils.isRtlText(block.text) }
                    val blockDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
                    CompositionLocalProvider(LocalLayoutDirection provides blockDirection) {
                        MarkdownInlineText(
                            text = block.text,
                            textColor = textColor,
                            latexMeasurer = latexMeasurer,
                            style =
                                MaterialTheme.typography.bodyMedium
                                    .copy(
                                        fontSize = fontSize,
                                        fontWeight = FontWeight.Bold,
                                        textDirection = bidiTextDirection(isRtl),
                                    ),
                            searchQuery = searchQuery,
                            isCurrentMatch = isCurrentMatch,
                            linkColor = linkColor,
                            highlights = highlights,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }

                is MdBlock.Bullet -> {
                    val indent = (block.level * 16).dp
                    val isRtl = remember(block.text) { BidiUtils.isRtlText(block.text) }
                    val blockDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
                    CompositionLocalProvider(LocalLayoutDirection provides blockDirection) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = indent)
                                    .padding(vertical = 1.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            val bulletChar =
                                when (block.level % 3) {
                                    0 -> "•"
                                    1 -> "◦"
                                    else -> "▪"
                                }
                            Text(
                                text = bulletChar,
                                color = textColor,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            MarkdownInlineText(
                                text = block.text,
                                textColor = textColor,
                                latexMeasurer = latexMeasurer,
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        textDirection = bidiTextDirection(isRtl),
                                    ),
                                searchQuery = searchQuery,
                                isCurrentMatch = isCurrentMatch,
                                linkColor = linkColor,
                                highlights = highlights,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MdBlock.Task -> {
                    val indent = (block.level * 16).dp
                    val isRtl = remember(block.text) { BidiUtils.isRtlText(block.text) }
                    val blockDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
                    CompositionLocalProvider(LocalLayoutDirection provides blockDirection) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = indent)
                                    .padding(vertical = 1.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector =
                                    if (block.checked) {
                                        Icons.Outlined.CheckBox
                                    } else {
                                        Icons.Outlined.CheckBoxOutlineBlank
                                    },
                                contentDescription = null,
                                tint =
                                    if (block.checked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        textColor.copy(
                                            alpha = 0.6f,
                                        )
                                    },
                                modifier = Modifier.size(18.dp).padding(top = 1.dp, end = 6.dp),
                            )
                            MarkdownInlineText(
                                text = block.text,
                                textColor = textColor,
                                latexMeasurer = latexMeasurer,
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        textDirection = bidiTextDirection(isRtl),
                                    ),
                                searchQuery = searchQuery,
                                isCurrentMatch = isCurrentMatch,
                                linkColor = linkColor,
                                highlights = highlights,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MdBlock.Ordered -> {
                    val indent = (block.level * 16).dp
                    val isRtl = remember(block.text) { BidiUtils.isRtlText(block.text) }
                    val blockDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
                    CompositionLocalProvider(LocalLayoutDirection provides blockDirection) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = indent)
                                    .padding(vertical = 1.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "${block.index}.",
                                color = textColor,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            MarkdownInlineText(
                                text = block.text,
                                textColor = textColor,
                                latexMeasurer = latexMeasurer,
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        textDirection = bidiTextDirection(isRtl),
                                    ),
                                searchQuery = searchQuery,
                                isCurrentMatch = isCurrentMatch,
                                linkColor = linkColor,
                                highlights = highlights,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MdBlock.Quote -> {
                    val isRtl = remember(block.text) { BidiUtils.isRtlText(block.text) }
                    val blockDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
                    CompositionLocalProvider(LocalLayoutDirection provides blockDirection) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(textColor.copy(alpha = 0.35f)),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            MarkdownInlineText(
                                text = block.text,
                                textColor = textColor,
                                latexMeasurer = latexMeasurer,
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontStyle = FontStyle.Italic,
                                        textDirection = bidiTextDirection(isRtl),
                                    ),
                                searchQuery = searchQuery,
                                isCurrentMatch = isCurrentMatch,
                                linkColor = linkColor,
                                highlights = highlights,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MdBlock.Video -> {
                    val resolvedUri = remember(block.uri) { MarkdownMediaResolver.resolveImageUrl(block.uri) }
                    var showVideoDialog by remember { mutableStateOf(false) }
                    androidx.compose.foundation.layout.Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                    ) {
                        com.m57.hermescontrol.ui.chat.components.InlineVideoPlayer(
                            videoUri = resolvedUri,
                            onFullScreenClick = { showVideoDialog = true },
                        )
                        if (showVideoDialog) {
                            com.m57.hermescontrol.ui.chat.components.VideoViewerDialog(
                                videoUri = resolvedUri,
                                onDismissRequest = { showVideoDialog = false },
                            )
                        }
                    }
                }

                is MdBlock.Image -> {
                    val model: Any = remember(block.uri) { MarkdownMediaResolver.resolveImageUrl(block.uri) }
                    val isGif =
                        remember(block.uri) {
                            block.uri.contains(".gif", ignoreCase = true) ||
                                block.uri.startsWith("data:image/gif", ignoreCase = true)
                        }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                    ) {
                        com.m57.hermescontrol.ui.chat.components.GifImageThumbnail(
                            model = model,
                            contentDescription = block.alt.ifBlank { null },
                            isGif = isGif,
                            onClick = {
                                onImageClick(
                                    ImageViewerModel(
                                        model = block.uri,
                                        name = block.alt,
                                        mimeType = if (isGif) "image/gif" else "image/*",
                                    ),
                                )
                            },
                        )
                    }
                }

                is MdBlock.DefList -> {
                    val isRtl =
                        remember(block.items) {
                            block.items.any { item ->
                                BidiUtils.isRtlText(item.term) ||
                                    item.definitions.any { BidiUtils.isRtlText(it) }
                            }
                        }
                    val blockDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
                    CompositionLocalProvider(LocalLayoutDirection provides blockDirection) {
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            block.items.forEach { item ->
                                val itemRtl = BidiUtils.isRtlText(item.term)
                                Text(
                                    text = if (itemRtl) BidiUtils.anchorTrailingRtl(item.term) else item.term,
                                    color = textColor,
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            textDirection = bidiTextDirection(itemRtl),
                                        ),
                                )
                                item.definitions.forEach { def ->
                                    val defRtl = BidiUtils.isRtlText(def)
                                    Text(
                                        text = if (defRtl) BidiUtils.anchorTrailingRtl(def) else def,
                                        color = textColor,
                                        style =
                                            MaterialTheme.typography.bodyMedium.copy(
                                                textDirection = bidiTextDirection(defRtl),
                                            ),
                                        modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                            }
                        }
                    }
                }

                is MdBlock.Table -> {
                    MarkdownTable(block = block, textColor = textColor)
                }

                is MdBlock.Footnotes -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = textColor.copy(alpha = 0.2f),
                    )
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Footnotes",
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                        block.notes.forEach { note ->
                            val isRtl = remember(note.text) { BidiUtils.isRtlText(note.text) }
                            val noteDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
                            CompositionLocalProvider(LocalLayoutDirection provides noteDirection) {
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                    Text(
                                        text = "[${note.id}] ",
                                        color = textColor,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    )
                                    MarkdownInlineText(
                                        text = note.text,
                                        textColor = textColor,
                                        latexMeasurer = latexMeasurer,
                                        style =
                                            MaterialTheme.typography.bodySmall.copy(
                                                textDirection = bidiTextDirection(isRtl),
                                            ),
                                        searchQuery = searchQuery,
                                        isCurrentMatch = isCurrentMatch,
                                        linkColor = linkColor,
                                        highlights = highlights,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                is MdBlock.Paragraph -> {
                    val isRtl = remember(block.text) { BidiUtils.isRtlText(block.text) }
                    val blockDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
                    CompositionLocalProvider(LocalLayoutDirection provides blockDirection) {
                        MarkdownInlineText(
                            text = block.text,
                            textColor = textColor,
                            latexMeasurer = latexMeasurer,
                            style =
                                MaterialTheme.typography.bodyMedium.copy(
                                    textDirection = bidiTextDirection(isRtl),
                                ),
                            searchQuery = searchQuery,
                            isCurrentMatch = isCurrentMatch,
                            linkColor = linkColor,
                            highlights = highlights,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

internal fun parseInline(
    text: String,
    textColor: Color,
    searchQuery: String,
    isCurrentMatch: Boolean,
    linkColor: Color,
    highlights: SearchHighlightColors,
    isRtl: Boolean = BidiUtils.isRtlText(text),
): AnnotatedString =
    MarkdownInlineStyler.parseInline(
        text = text,
        textColor = textColor,
        searchQuery = searchQuery,
        isCurrentMatch = isCurrentMatch,
        linkColor = linkColor,
        highlights = highlights,
        isRtl = isRtl,
    )
