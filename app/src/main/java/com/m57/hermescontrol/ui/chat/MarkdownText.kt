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
                    val resolvedUri = remember(block.uri) { resolveImageUrl(block.uri) }
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
                    val model: Any = remember(block.uri) { resolveImageUrl(block.uri) }
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

@Composable
private fun MarkdownInlineText(
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
                        parseInlineSource(
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
                                parseInlineSource(
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

@Composable
private fun MarkdownTable(
    block: MdBlock.Table,
    textColor: Color,
) {
    val isRtl =
        remember(block) {
            block.header.any { BidiUtils.isRtlText(it) } ||
                block.rows.any { row -> row.any { BidiUtils.isRtlText(it) } }
        }
    val tableDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
    val headerBg = textColor.copy(alpha = 0.08f)
    val alignments = block.alignments
    CompositionLocalProvider(LocalLayoutDirection provides tableDirection) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
        ) {
            // Header row
            Row(modifier = Modifier.background(headerBg)) {
                block.header.forEachIndexed { idx, cell ->
                    val cellRtl = BidiUtils.isRtlText(cell)
                    Text(
                        text = if (cellRtl) BidiUtils.anchorTrailingRtl(cell) else cell,
                        textAlign = tableTextAlign(alignments.getOrNull(idx)),
                        color = textColor,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                textDirection = bidiTextDirection(cellRtl),
                            ),
                        modifier =
                            Modifier
                                .width(TABLE_COL_WIDTH)
                                .padding(6.dp),
                    )
                }
            }
            HorizontalDivider(color = textColor.copy(alpha = 0.25f))
            // Body rows
            block.rows.forEach { row ->
                Row {
                    row.forEachIndexed { idx, cell ->
                        val cellRtl = BidiUtils.isRtlText(cell)
                        Text(
                            text = if (cellRtl) BidiUtils.anchorTrailingRtl(cell) else cell,
                            textAlign = tableTextAlign(alignments.getOrNull(idx)),
                            color = textColor,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    textDirection = bidiTextDirection(cellRtl),
                                ),
                            modifier =
                                Modifier
                                    .width(TABLE_COL_WIDTH)
                                    .padding(6.dp),
                        )
                    }
                }
                HorizontalDivider(color = textColor.copy(alpha = 0.1f))
            }
        }
    }
}

private fun tableTextAlign(align: TableAlign?): TextAlign? =
    when (align) {
        TableAlign.CENTER -> TextAlign.Center
        TableAlign.RIGHT -> TextAlign.End
        else -> null
    }

/**
 * Inline Markdown -> AnnotatedString. Handles `code`, **bold**, *italic*, ***bold italic***,
 * ~~strike~~, ==highlight==, ^sup^, ~sub~, <kbd>keys</kbd>, [^ref] footnotes, [text](url) and
 * bare URLs, plus search-query highlighting.
 */
internal fun parseInline(
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

private fun parseInlineSource(
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
                        val toAppend = if (isRtl) BidiUtils.wrapLtrIsolate(raw) else raw
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

                // Plain text / words: when inside RTL text, wrap sequences of Latin/LTR words in LTR isolate
                // so an English word at the start/middle of an Arabic line doesn't flip the layout direction.
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

private fun resolveImageUrl(uri: String): String {
    val trimmed = uri.trim()
    if (trimmed.isBlank()) return uri
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:image/")) {
        return trimmed
    }
    val baseUrl = runCatching { AuthManager.getBaseUrl() }.getOrDefault("")
    val token = runCatching { AuthManager.getToken() }.getOrNull().orEmpty()
    val mediaUrl = GatewayFileClient.buildMediaUrl(baseUrl, token, trimmed)
    if (mediaUrl != null) return mediaUrl

    if (baseUrl.isNotBlank() && (trimmed.startsWith("/api/") || trimmed.startsWith("api/"))) {
        val cleanPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val sep = if (cleanPath.contains("?")) "&" else "?"
        return if (token.isNotBlank() && !cleanPath.contains("token=")) {
            "${baseUrl.trimEnd('/')}$cleanPath${sep}token=$token"
        } else {
            "${baseUrl.trimEnd('/')}$cleanPath"
        }
    }
    return uri
}
