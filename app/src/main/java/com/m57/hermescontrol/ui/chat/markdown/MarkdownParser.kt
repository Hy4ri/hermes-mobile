package com.m57.hermescontrol.ui.chat.markdown

private val FN_DEF_RE = Regex("""^\[\^([^\]]+)\]:\s*(.*)$""")
private val TASK_LINE_RE = Regex("""^(\s*)[-*+]\s+\[([ xX])\]\s+(.*)$""")
private val BULLET_LINE_RE = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val ORDERED_LINE_RE = Regex("""^(\s*)(\d+)\.\s+(.*)$""")
private val LIST_ITEM_LINE_RE = Regex("""^(\s*)(?:[-*+]\s+(?:\[([ xX])\]\s+)?|(\d+)\.\s+)(.*)$""")
private val IMAGE_RE = Regex("""^!\[([^\]]*)\]\(([^)\s]+)\s*\)""")

/** Splits `$…$` without treating escaped dollars or inline-code contents as math. */
fun splitInlineMath(text: String): List<InlineMathSegment> {
    val segments = mutableListOf<InlineMathSegment>()
    var plainStart = 0
    var i = 0

    while (i < text.length) {
        if (text[i] == '`' && !text.isEscaped(i)) {
            val codeEnd = text.indexOf('`', i + 1)
            i = if (codeEnd == -1) text.length else codeEnd + 1
            continue
        }
        if (text.startsWith("\\(", i)) {
            val end = text.indexOf("\\)", i + 2)
            if (end != -1 && end > i + 2) {
                if (plainStart < i) segments.add(InlineMathSegment.Text(text.substring(plainStart, i)))
                segments.add(InlineMathSegment.Math(text.substring(i + 2, end)))
                i = end + 2
                plainStart = i
                continue
            }
        }
        if (text[i] != '$' || text.isEscaped(i) || text.getOrNull(i + 1) == '$') {
            i++
            continue
        }

        var end = i + 1
        while (end < text.length && (text[end] != '$' || text.isEscaped(end))) end++
        if (end >= text.length || end == i + 1 || text[end - 1].isWhitespace()) {
            i++
            continue
        }

        if (plainStart < i) segments.add(InlineMathSegment.Text(text.substring(plainStart, i)))
        segments.add(InlineMathSegment.Math(text.substring(i + 1, end)))
        i = end + 1
        plainStart = i
    }

    if (plainStart < text.length) segments.add(InlineMathSegment.Text(text.substring(plainStart)))
    return segments.ifEmpty { listOf(InlineMathSegment.Text(text)) }
}

private fun String.isEscaped(index: Int): Boolean {
    var slashes = 0
    var i = index - 1
    while (i >= 0 && this[i] == '\\') {
        slashes++
        i--
    }
    return slashes % 2 == 1
}

private fun tryParseDisplayMath(
    lines: List<String>,
    start: Int,
): Pair<MdBlock.Math, Int>? {
    val first = lines[start].trim()
    if (first.startsWith("\\[")) {
        if (first.length > 4 && first.endsWith("\\]")) {
            val latex = first.substring(2, first.length - 2).trim()
            return latex.takeIf { it.isNotEmpty() }?.let { MdBlock.Math(it) to start + 1 }
        }
        if (first != "\\[") return null
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim() == "\\]" } ?: return null
        val latex = lines.subList(start + 1, end).joinToString("\n").trim()
        return latex.takeIf { it.isNotEmpty() }?.let { MdBlock.Math(it) to end + 1 }
    }
    if (!first.startsWith("$$")) return null
    if (first.length > 4 && first.endsWith("$$")) {
        val latex = first.substring(2, first.length - 2).trim()
        return latex.takeIf { it.isNotEmpty() }?.let { MdBlock.Math(it) to start + 1 }
    }
    if (first != "$$") return null

    val end = (start + 1 until lines.size).firstOrNull { lines[it].trim() == "$$" } ?: return null
    val latex = lines.subList(start + 1, end).joinToString("\n").trim()
    return latex.takeIf { it.isNotEmpty() }?.let { MdBlock.Math(it) to end + 1 }
}

/**
 * Splits source text into Markdown blocks. Fenced code blocks (```...```) are extracted first;
 * everything else is grouped into headings, lists, tables, rules, footnotes, or paragraphs.
 */
fun parseBlocks(src: String): List<MdBlock> {
    val lines = src.lines()
    val blocks = mutableListOf<MdBlock>()
    val footnotes = mutableListOf<Footnote>()
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

        val displayMath = tryParseDisplayMath(lines, i)
        if (displayMath != null) {
            blocks.add(displayMath.first)
            i = displayMath.second
            continue
        }

        val codeFence = parseCodeFenceStart(line)
        when {
            codeFence != null -> {
                val end = (i + 1 until lines.size).firstOrNull { isCodeFenceEnd(lines[it], codeFence) }
                if (end != null) {
                    blocks.add(
                        MdBlock.Code(
                            code = lines.subList(i + 1, end).joinToString("\n"),
                            language = codeFence.language,
                        ),
                    )
                    i = end + 1
                } else {
                    blocks.add(
                        MdBlock.Code(
                            code = lines.subList(i + 1, lines.size).joinToString("\n"),
                            language = codeFence.language,
                        ),
                    )
                    i = lines.size
                }
            }

            line.isBlank() -> {
                i++
            }

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
            isDefListStart(lines, i) -> {
                val term = line.trim()
                val defs = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].trim().startsWith(":")) {
                    defs.add(lines[i].trim().removePrefix(":").trim())
                    i++
                }
                blocks.add(MdBlock.DefList(listOf(DefItem(term, defs))))
            }

            LIST_ITEM_LINE_RE.matches(line) -> {
                val (listBlocks, nextIndex) = parseList(lines, i)
                blocks.addAll(listBlocks)
                i = nextIndex
            }

            // Standalone Markdown image: ![alt](uri) on its own line.
            line.isNotBlank() && tryParseVideo(line) != null -> {
                blocks.add(tryParseVideo(line)!!)
                i++
            }

            line.isNotBlank() && tryParseImage(line) != null -> {
                blocks.add(tryParseImage(line)!!)
                i++
            }

            else -> {
                i = fallthroughToParagraph(lines, i, blocks)
            }
        }
    }

    if (footnotes.isNotEmpty()) {
        blocks.add(MdBlock.Footnotes(footnotes.map { FnNote(it.id, it.text) }))
    }
    return blocks
}

private sealed interface ParsedItem {
    val indent: Int
    val continuationLines: MutableList<String>

    data class Bullet(
        override val indent: Int,
        val text: String,
        override val continuationLines: MutableList<String> = mutableListOf(),
    ) : ParsedItem

    data class Task(
        override val indent: Int,
        val checked: Boolean,
        val text: String,
        override val continuationLines: MutableList<String> = mutableListOf(),
    ) : ParsedItem

    data class Ordered(
        override val indent: Int,
        val rawNumber: Int,
        val text: String,
        override val continuationLines: MutableList<String> = mutableListOf(),
    ) : ParsedItem
}

private fun computeIndent(prefix: String): Int {
    var count = 0
    for (ch in prefix) {
        if (ch == '\t') {
            count += 4 - (count % 4)
        } else {
            count++
        }
    }
    return count
}

private fun parseList(
    lines: List<String>,
    startIndex: Int,
): Pair<List<MdBlock>, Int> {
    val items = mutableListOf<ParsedItem>()
    var j = startIndex

    while (j < lines.size) {
        val line = lines[j]

        val taskMatch = TASK_LINE_RE.matchEntire(line)
        val bulletMatch = if (taskMatch == null) BULLET_LINE_RE.matchEntire(line) else null
        val orderedMatch = if (taskMatch == null && bulletMatch == null) ORDERED_LINE_RE.matchEntire(line) else null

        if (taskMatch != null) {
            val indent = computeIndent(taskMatch.groupValues[1])
            val checked = taskMatch.groupValues[2].equals("x", ignoreCase = true)
            val text = taskMatch.groupValues[3]
            items.add(ParsedItem.Task(indent, checked, text))
            j++
        } else if (bulletMatch != null) {
            val indent = computeIndent(bulletMatch.groupValues[1])
            val text = bulletMatch.groupValues[2]
            items.add(ParsedItem.Bullet(indent, text))
            j++
        } else if (orderedMatch != null) {
            val indent = computeIndent(orderedMatch.groupValues[1])
            val num = orderedMatch.groupValues[2].toIntOrNull() ?: 1
            val text = orderedMatch.groupValues[3]
            items.add(ParsedItem.Ordered(indent, num, text))
            j++
        } else if (line.isBlank()) {
            // Check if there is a following list item
            var lookahead = j + 1
            while (lookahead < lines.size && lines[lookahead].isBlank()) {
                lookahead++
            }
            if (lookahead < lines.size && LIST_ITEM_LINE_RE.matches(lines[lookahead])) {
                j = lookahead
            } else {
                break
            }
        } else {
            // Check if this is a continuation line for the previous list item
            val indent = computeIndent(line.takeWhile { it == ' ' || it == '\t' })
            if (items.isNotEmpty() && (indent >= 2 || line.startsWith("    "))) {
                items.last().continuationLines.add(line.trim())
                j++
            } else {
                break
            }
        }
    }

    if (items.isEmpty()) {
        return emptyList<MdBlock>() to j
    }

    val blocks = mutableListOf<MdBlock>()

    // Track state of list items and their relative nesting depth using an indent stack
    val indentStack = mutableListOf<Int>() // maps level (index) -> base indent
    var lastItemWasOrdered = false
    var currentSequenceNumber = 1
    var lastLevel = -1

    for (item in items) {
        // Resolve level using the indent stack
        val level: Int
        if (indentStack.isEmpty() || item.indent == 0) {
            indentStack.clear()
            indentStack.add(item.indent)
            level = 0
        } else if (item.indent > indentStack.last()) {
            indentStack.add(item.indent)
            level = indentStack.size - 1
        } else if (item.indent == indentStack.last()) {
            level = indentStack.size - 1
        } else {
            // Unwind stack to the matching or nearest smaller indent
            while (indentStack.size > 1 && indentStack.last() > item.indent) {
                indentStack.removeAt(indentStack.size - 1)
            }
            level = indentStack.size - 1
        }

        val fullText =
            if (item.continuationLines.isEmpty()) {
                when (item) {
                    is ParsedItem.Bullet -> item.text
                    is ParsedItem.Task -> item.text
                    is ParsedItem.Ordered -> item.text
                }
            } else {
                val base =
                    when (item) {
                        is ParsedItem.Bullet -> item.text
                        is ParsedItem.Task -> item.text
                        is ParsedItem.Ordered -> item.text
                    }
                base + " " + item.continuationLines.joinToString(" ")
            }

        when (item) {
            is ParsedItem.Bullet -> {
                blocks.add(MdBlock.Bullet(fullText, level = level))
                lastItemWasOrdered = false
                lastLevel = level
            }

            is ParsedItem.Task -> {
                blocks.add(MdBlock.Task(item.checked, fullText, level = level))
                lastItemWasOrdered = false
                lastLevel = level
            }

            is ParsedItem.Ordered -> {
                val indexToUse =
                    if (level == lastLevel && lastItemWasOrdered) {
                        if (item.rawNumber > currentSequenceNumber) {
                            currentSequenceNumber = item.rawNumber
                        } else {
                            currentSequenceNumber++
                        }
                        currentSequenceNumber
                    } else {
                        currentSequenceNumber = item.rawNumber
                        currentSequenceNumber
                    }
                blocks.add(MdBlock.Ordered(indexToUse, fullText, level = level))
                lastItemWasOrdered = true
                lastLevel = level
            }
        }
    }

    return blocks to j
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

private data class CodeFenceInfo(
    val fenceChar: Char,
    val fenceLength: Int,
    val language: String?,
)

private fun parseCodeFenceStart(line: String): CodeFenceInfo? {
    val trimmed = line.trimStart()
    if (trimmed.length < 3) return null
    val firstChar = trimmed[0]
    if (firstChar != '`' && firstChar != '~') return null
    val count = trimmed.takeWhile { it == firstChar }.length
    if (count < 3) return null
    val rest = trimmed.substring(count).trim()
    if (firstChar == '`' && rest.contains('`')) return null
    return CodeFenceInfo(
        fenceChar = firstChar,
        fenceLength = count,
        language = rest.ifBlank { null },
    )
}

private fun isCodeFenceEnd(
    line: String,
    fence: CodeFenceInfo,
): Boolean {
    val trimmed = line.trimStart()
    val count = trimmed.takeWhile { it == fence.fenceChar }.length
    if (count < fence.fenceLength) return false
    val rest = trimmed.substring(count).trim()
    return rest.isEmpty()
}

private fun isDefListStart(
    lines: List<String>,
    i: Int,
): Boolean {
    val l = lines[i].trim()
    if (l.isBlank() || l.startsWith("#") || l.startsWith(">") || parseCodeFenceStart(l) != null) return false
    if (LIST_ITEM_LINE_RE.matches(lines[i])) return false
    if (i + 1 >= lines.size) return false
    return lines[i + 1].trim().startsWith(":")
}

private fun isValidHeading(line: String): Boolean {
    if (!line.startsWith("#")) return false
    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
    return level < line.length && line[level] == ' '
}

private fun tryParseImage(line: String): MdBlock.Image? {
    val m = IMAGE_RE.matchAt(line, 0) ?: return null
    val uri = m.groupValues[2].trim()
    if (uri.isEmpty()) return null
    return MdBlock.Image(uri = uri, alt = m.groupValues[1].trim())
}

private fun tryParseVideo(line: String): MdBlock.Video? {
    val m = IMAGE_RE.matchAt(line, 0) ?: return null
    val uri = m.groupValues[2].trim()
    if (uri.isEmpty()) return null
    val ext = uri.substringAfterLast('.', "").lowercase()
    if (ext in listOf("mp4", "webm", "mkv", "mov", "avi", "3gp") || uri.contains("video/", ignoreCase = true)) {
        return MdBlock.Video(uri = uri, alt = m.groupValues[1].trim())
    }
    return null
}

private fun fallthroughToParagraph(
    lines: List<String>,
    start: Int,
    blocks: MutableList<MdBlock>,
): Int {
    var i = start
    val para = mutableListOf<String>()
    while (
        i < lines.size &&
        lines[i].isNotBlank() &&
        parseCodeFenceStart(lines[i]) == null &&
        !isValidHeading(lines[i]) &&
        !lines[i].startsWith(">") &&
        !LIST_ITEM_LINE_RE.matches(lines[i]) &&
        !isHorizontalRule(lines[i]) &&
        !isTableStart(lines, i) &&
        !isDefListStart(lines, i) &&
        tryParseDisplayMath(lines, i) == null &&
        FN_DEF_RE.matchAt(lines[i], 0) == null
    ) {
        para.add(lines[i])
        i++
    }
    if (para.isNotEmpty()) blocks.add(MdBlock.Paragraph(para.joinToString("\n")))
    return i
}
