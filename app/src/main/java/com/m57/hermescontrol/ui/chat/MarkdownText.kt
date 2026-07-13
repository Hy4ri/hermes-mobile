package com.m57.hermescontrol.ui.chat

import android.content.ClipData
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.SearchHighlightColors
import com.m57.hermescontrol.theme.searchHighlightColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val URL_PATTERN = Regex("""https?://[^\s)>\[\]"'‘’]+""")
private val TABLE_COL_WIDTH = 160.dp
private val FN_DEF_RE = Regex("""^\[\^([^\]]+)\]:\s*(.*)$""")

/**
 * Renders chat assistant text as Markdown — but ONLY once the message has finished streaming.
 * While [isStreaming] is true we show the raw text to avoid flicker / re-parse churn, then swap
 * to the formatted view on completion (and for all historical/restored messages).
 *
 * Supports: fenced ```code``` blocks (horizontal scroll + copy), inline `code`, **bold**, *italic*,
 * ***bold italic***, ~~strike~~, ==highlight==, ^sup^ / ~sub~, <kbd>keys</kbd>, headings,
 * bullet/ordered/task lists, > blockquotes, definition lists, tables, --- rules, footnotes, and
 * [links](url) / bare URLs. (Math is intentionally out of scope for this hand-rolled renderer.)
 */
@Composable
fun MarkdownText(
    text: String,
    textColor: Color,
    isStreaming: Boolean = false,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalHermesStatusColors.current
    val highlights = searchHighlightColors(statusColors)
    if (isStreaming) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        return
    }

    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(text) { parseBlocks(text) }

    Column(modifier = modifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Code -> CodeBlockCard(code = block.code, textColor = textColor)

                is MdBlock.Hr ->
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = textColor.copy(alpha = 0.25f),
                    )

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
                    Text(
                        text =
                            remember(block.text, searchQuery, isCurrentMatch, textColor, linkColor) {
                                parseInline(block.text, textColor, searchQuery, isCurrentMatch, linkColor, highlights)
                            },
                        color = textColor,
                        style =
                            MaterialTheme.typography.bodyMedium
                                .copy(fontSize = fontSize, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }

                is MdBlock.Bullet -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "•",
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text =
                                remember(block.text, searchQuery, isCurrentMatch, textColor, linkColor) {
                                    parseInline(
                                        block.text,
                                        textColor,
                                        searchQuery,
                                        isCurrentMatch,
                                        linkColor,
                                        highlights,
                                    )
                                },
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.Task -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
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
                        Text(
                            text =
                                remember(block.text, searchQuery, isCurrentMatch, textColor, linkColor) {
                                    parseInline(
                                        block.text,
                                        textColor,
                                        searchQuery,
                                        isCurrentMatch,
                                        linkColor,
                                        highlights,
                                    )
                                },
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.Ordered -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${block.index}.",
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            text =
                                remember(block.text, searchQuery, isCurrentMatch, textColor, linkColor) {
                                    parseInline(
                                        block.text,
                                        textColor,
                                        searchQuery,
                                        isCurrentMatch,
                                        linkColor,
                                        highlights,
                                    )
                                },
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.Quote -> {
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
                        Text(
                            text =
                                remember(block.text, searchQuery, isCurrentMatch, textColor, linkColor) {
                                    parseInline(
                                        block.text,
                                        textColor,
                                        searchQuery,
                                        isCurrentMatch,
                                        linkColor,
                                        highlights,
                                    )
                                },
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.DefList -> {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        block.items.forEach { item ->
                            Text(
                                text = item.term,
                                color = textColor,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            )
                            item.definitions.forEach { def ->
                                Text(
                                    text = def,
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                    }
                }

                is MdBlock.Table -> MarkdownTable(block = block, textColor = textColor)

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
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text(
                                    text = "[${note.id}] ",
                                    color = textColor,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    text =
                                        remember(note.text, searchQuery, isCurrentMatch, textColor, linkColor) {
                                            parseInline(
                                                note.text,
                                                textColor,
                                                searchQuery,
                                                isCurrentMatch,
                                                linkColor,
                                                highlights,
                                            )
                                        },
                                    color = textColor,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text =
                            remember(block.text, searchQuery, isCurrentMatch, textColor, linkColor) {
                                parseInline(block.text, textColor, searchQuery, isCurrentMatch, linkColor, highlights)
                            },
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Fenced code block rendered as a card: monospaced text with horizontal scroll and a copy button.
 */
@Composable
private fun CodeBlockCard(
    code: String,
    textColor: Color,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    // Auto-dismiss the "copied" checkmark after 2s
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = textColor.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, textColor.copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, code)))
                        }
                        copied = true
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.content_desc_copy),
                        modifier = Modifier.size(15.dp),
                        tint = textColor.copy(alpha = 0.7f),
                    )
                }
            }
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = textColor,
                softWrap = false,
                modifier =
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun MarkdownTable(
    block: MdBlock.Table,
    textColor: Color,
) {
    val headerBg = textColor.copy(alpha = 0.08f)
    val alignments = block.alignments
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
                Text(
                    text = cell,
                    textAlign = tableTextAlign(alignments.getOrNull(idx)),
                    color = textColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
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
                    Text(
                        text = cell,
                        textAlign = tableTextAlign(alignments.getOrNull(idx)),
                        color = textColor,
                        style = MaterialTheme.typography.bodySmall,
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

private fun tableTextAlign(align: TableAlign?): TextAlign? =
    when (align) {
        TableAlign.CENTER -> TextAlign.Center
        TableAlign.RIGHT -> TextAlign.End
        else -> null
    }

/**
 * Splits source text into Markdown blocks. Fenced code blocks (```...```) are extracted first;
 * everything else is grouped into headings, lists, tables, rules, footnotes, or paragraphs.
 */
internal fun parseBlocks(src: String): List<MdBlock> {
    val lines = src.lines()
    val blocks = mutableListOf<MdBlock>()
    val footnotes = mutableListOf<Footnote>()
    val bulletRe = Regex("""^\s*[-*+]\s+(.*)""")
    val taskRe = Regex("""^\s*[-*+]\s+\[([ xX])\]\s+(.*)""")
    val orderedRe = Regex("""^\s*\d+\.\s+(.*)""")
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Footnote definition: [^id]: text  (collected, not rendered inline)
        val fnMatch = FN_DEF_RE.matchAt(line, 0)
        if (fnMatch != null) {
            footnotes.add(Footnote(fnMatch.groupValues[1], fnMatch.groupValues[2]))
            i++
            continue
        }

        when {
            line.startsWith("```") -> {
                val end = (i + 1 until lines.size).firstOrNull { lines[it].startsWith("```") }
                if (end != null) {
                    blocks.add(MdBlock.Code(lines.subList(i + 1, end).joinToString("\n")))
                    i = end + 1
                } else {
                    blocks.add(MdBlock.Code(lines.subList(i + 1, lines.size).joinToString("\n")))
                    i = lines.size
                }
            }

            line.isBlank() -> i++

            isHorizontalRule(line) -> {
                blocks.add(MdBlock.Hr)
                i++
            }

            // Heading: requires a space after the '#' run so "#572" stays a paragraph
            isValidHeading(line) -> {
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks.add(MdBlock.Heading(level, line.substring(level).trim()))
                i++
            }

            isTableStart(lines, i) -> {
                val (header, alignments, body) = parseTable(lines, i)
                blocks.add(MdBlock.Table(header, alignments, body))
                i += body.size + 2
            }

            line.startsWith(">") -> {
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].startsWith(">")) {
                    quote.add(lines[i].removePrefix(">").trim())
                    i++
                }
                blocks.add(MdBlock.Quote(quote.joinToString("\n")))
            }

            // Definition list: term line followed by one+ ": definition" lines
            isDefListStart(lines, i, bulletRe, orderedRe) -> {
                val term = line.trim()
                val defs = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].trim().startsWith(":")) {
                    defs.add(lines[i].trim().removePrefix(":").trim())
                    i++
                }
                blocks.add(MdBlock.DefList(listOf(DefItem(term, defs))))
            }

            taskRe.matches(line) -> {
                val m = taskRe.find(line)!!
                blocks.add(MdBlock.Task(m.groupValues[1].equals("x", ignoreCase = true), m.groupValues[2]))
                i++
            }

            bulletRe.matches(line) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && bulletRe.matches(lines[i]) && !taskRe.matches(lines[i])) {
                    items.add(bulletRe.find(lines[i])!!.groupValues[1])
                    i++
                }
                items.forEach { blocks.add(MdBlock.Bullet(it)) }
            }

            orderedRe.matches(line) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && orderedRe.matches(lines[i])) {
                    items.add(orderedRe.find(lines[i])!!.groupValues[1])
                    i++
                }
                items.forEachIndexed { idx, t -> blocks.add(MdBlock.Ordered(idx + 1, t)) }
            }

            else -> {
                i = fallthroughToParagraph(lines, i, bulletRe, orderedRe, blocks)
            }
        }
    }

    if (footnotes.isNotEmpty()) {
        blocks.add(MdBlock.Footnotes(footnotes.map { FnNote(it.id, it.text) }))
    }
    return blocks
}

private fun isHorizontalRule(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.length < 3) return false
    val c = trimmed[0]
    return (c == '-' || c == '*' || c == '_') && trimmed.all { it == c }
}

private fun isTableStart(
    lines: List<String>,
    i: Int,
): Boolean {
    val l = lines[i]
    if (!l.contains('|')) return false
    if (i + 1 >= lines.size) return false
    return isTableSeparator(lines[i + 1])
}

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim().trim('|')
    if (t.isEmpty()) return false
    // every cell must be only dashes/colons/spaces, with at least one dash
    return t.split('|').all { cell ->
        val c = cell.trim()
        c.isNotEmpty() && c.all { it == '-' || it == ':' || it == ' ' } && c.any { it == '-' }
    }
}

private fun parseTable(
    lines: List<String>,
    i: Int,
): Triple<List<String>, List<TableAlign>, List<List<String>>> {
    val header = splitRow(lines[i])
    val alignments =
        splitRow(lines[i + 1]).map { cell ->
            val c = cell.trim()
            when {
                c.startsWith(":") && c.endsWith(":") -> TableAlign.CENTER
                c.endsWith(":") -> TableAlign.RIGHT
                c.startsWith(":") -> TableAlign.LEFT
                else -> TableAlign.LEFT
            }
        }
    val body = mutableListOf<List<String>>()
    var j = i + 2
    while (j < lines.size && lines[j].contains('|') && lines[j].trim().isNotEmpty()) {
        body.add(splitRow(lines[j]))
        j++
    }
    return Triple(header, alignments, body)
}

private fun splitRow(line: String): List<String> {
    val trimmed = line.trim().trim('|')
    return trimmed.split('|').map { it.trim() }
}

private fun isDefListStart(
    lines: List<String>,
    i: Int,
    bulletRe: Regex,
    orderedRe: Regex,
): Boolean {
    val l = lines[i].trim()
    if (l.isBlank() || l.startsWith("#") || l.startsWith(">") || l.startsWith("```")) return false
    if (bulletRe.matches(lines[i]) || orderedRe.matches(lines[i])) return false
    if (i + 1 >= lines.size) return false
    return lines[i + 1].trim().startsWith(":")
}

private fun isValidHeading(line: String): Boolean {
    if (!line.startsWith("#")) return false
    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
    return level < line.length && line[level] == ' '
}

private fun fallthroughToParagraph(
    lines: List<String>,
    start: Int,
    bulletRe: Regex,
    orderedRe: Regex,
    blocks: MutableList<MdBlock>,
): Int {
    var i = start
    val para = mutableListOf<String>()
    while (
        i < lines.size &&
        lines[i].isNotBlank() &&
        !lines[i].startsWith("```") &&
        !isValidHeading(lines[i]) &&
        !lines[i].startsWith(">") &&
        !bulletRe.matches(lines[i]) &&
        !orderedRe.matches(lines[i]) &&
        !isHorizontalRule(lines[i]) &&
        !isTableStart(lines, i) &&
        !isDefListStart(lines, i, bulletRe, orderedRe) &&
        FN_DEF_RE.matchAt(lines[i], 0) == null
    ) {
        para.add(lines[i])
        i++
    }
    if (para.isNotEmpty()) blocks.add(MdBlock.Paragraph(para.joinToString("\n")))
    return i
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
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(src.substring(i + 3, end))
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
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(src.substring(i + 2, end))
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
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(src.substring(i + 2, end))
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
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(src.substring(i + 1, end))
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
                        withStyle(SpanStyle(background = highlights.markupBackground)) {
                            append(src.substring(i + 2, end))
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
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = textColor.copy(alpha = 0.12f),
                            ),
                        ) {
                            append(src.substring(i + 5, end))
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
                            val url = src.substring(close + 2, urlEnd)
                            pushLink(LinkAnnotation.Url(url))
                            withStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ) {
                                append(label)
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
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                background = textColor.copy(alpha = 0.08f),
                            ),
                        ) {
                            append(src.substring(i + 1, end))
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
                    pushLink(LinkAnnotation.Url(url))
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        append(url)
                    }
                    pop()
                    i = match.range.last + 1
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

internal sealed interface MdBlock {
    data class Code(val code: String) : MdBlock

    data class Heading(val level: Int, val text: String) : MdBlock

    data class Bullet(val text: String) : MdBlock

    data class Task(val checked: Boolean, val text: String) : MdBlock

    data class Ordered(val index: Int, val text: String) : MdBlock

    data class Quote(val text: String) : MdBlock

    data class Paragraph(val text: String) : MdBlock

    data class Table(
        val header: List<String>,
        val alignments: List<TableAlign>,
        val rows: List<List<String>>,
    ) : MdBlock

    object Hr : MdBlock

    data class DefList(val items: List<DefItem>) : MdBlock

    data class Footnotes(val notes: List<FnNote>) : MdBlock
}

internal data class DefItem(
    val term: String,
    val definitions: List<String>,
)

internal data class Footnote(
    val id: String,
    val text: String,
)

internal data class FnNote(
    val id: String,
    val text: String,
)

internal enum class TableAlign {
    LEFT,
    CENTER,
    RIGHT,
}
