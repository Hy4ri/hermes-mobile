package com.m57.hermescontrol.ui.chat.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.SearchHighlightColors
import com.m57.hermescontrol.util.BidiUtils

/**
 * Render-time optimization, not parsed Markdown: a long run of plain bullets drawn as one text node.
 * Produced only by [coalesceBulletRuns]; kept out of the parser's AST file on purpose.
 */
data class BulletRun(
    val items: List<MdBlock.Bullet>,
) : MdBlock

/** Runs shorter than this keep the per-row renderer; the win only matters for very long lists. */
internal const val MIN_BULLET_RUN = 16

/**
 * A bullet is eligible for coalescing when it is a plain, LTR, single-line item: no nested content
 * and no inline math (math needs per-item inline content that a merged paragraph run can't host).
 */
private fun MdBlock.Bullet.isPlain(): Boolean =
    nestedSource.isEmpty() &&
        !BidiUtils.isRtlText(text) &&
        splitInlineMath(text).none { it is InlineMathSegment.Math }

/**
 * Replaces each run of at least [minRun] consecutive plain bullets with a single [MdBlock.BulletRun].
 * Everything else is passed through untouched, so complex lists keep the existing renderer.
 */
internal fun coalesceBulletRuns(
    blocks: List<MdBlock>,
    minRun: Int = MIN_BULLET_RUN,
): List<MdBlock> {
    if (blocks.size < minRun) return blocks
    val out = ArrayList<MdBlock>(blocks.size)
    var i = 0
    while (i < blocks.size) {
        val block = blocks[i]
        if (block is MdBlock.Bullet && block.isPlain()) {
            var end = i + 1
            while (end < blocks.size) {
                val next = blocks[end]
                if (next is MdBlock.Bullet && next.isPlain()) end++ else break
            }
            if (end - i >= minRun) {
                out.add(BulletRun(blocks.subList(i, end).map { it as MdBlock.Bullet }))
            } else {
                out.addAll(blocks.subList(i, end))
            }
            i = end
        } else {
            out.add(block)
            i++
        }
    }
    return out
}

private const val BULLET_LEVEL_INDENT_DP = 16
private const val BULLET_TEXT_GAP_DP = 14

private fun bulletGlyph(level: Int): String =
    when (level % 3) {
        0 -> "\u2022"
        1 -> "\u25E6"
        else -> "\u25AA"
    }

/**
 * Builds the merged text for a [BulletRun]. Indents are specified in dp and converted through
 * [density] so they match the per-row renderer's `Modifier.padding(start = (level * 16).dp)`.
 */
internal fun buildBulletRunText(
    run: BulletRun,
    density: Density,
    textColor: Color,
    searchQuery: String,
    isCurrentMatch: Boolean,
    linkColor: Color,
    highlights: SearchHighlightColors,
): AnnotatedString =
    buildAnnotatedString {
        run.items.forEachIndexed { index, item ->
            val start = with(density) { (item.level * BULLET_LEVEL_INDENT_DP).dp.toSp() }
            val rest = with(density) { (item.level * BULLET_LEVEL_INDENT_DP + BULLET_TEXT_GAP_DP).dp.toSp() }
            withStyle(ParagraphStyle(textIndent = TextIndent(firstLine = start, restLine = rest))) {
                append("${bulletGlyph(item.level)}\u00A0\u00A0")
                append(
                    MarkdownInlineStyler.parseInlineSource(
                        text = item.text,
                        textColor = textColor,
                        searchQuery = searchQuery,
                        isCurrentMatch = isCurrentMatch,
                        linkColor = linkColor,
                        highlights = highlights,
                        isRtl = false,
                    ),
                )
                // ParagraphStyle breaks lines visually but adds no character; the newline keeps copy/select faithful.
                if (index < run.items.lastIndex) append('\n')
            }
        }
    }
