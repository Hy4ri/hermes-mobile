package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.StatusBlue
import com.m57.hermescontrol.theme.StatusBlueContainer
import com.m57.hermescontrol.theme.StatusGreen
import com.m57.hermescontrol.theme.StatusGreenContainer
import com.m57.hermescontrol.theme.StatusRed
import com.m57.hermescontrol.theme.StatusRedContainer
import com.m57.hermescontrol.theme.StatusYellow
import com.m57.hermescontrol.theme.StatusYellowContainer
import com.m57.hermescontrol.theme.searchHighlightColors
import com.m57.hermescontrol.ui.chat.markdown.BulletRun
import com.m57.hermescontrol.ui.chat.markdown.MIN_BULLET_RUN
import com.m57.hermescontrol.ui.chat.markdown.MdBlock
import com.m57.hermescontrol.ui.chat.markdown.buildBulletRunText
import com.m57.hermescontrol.ui.chat.markdown.coalesceBulletRuns
import com.m57.hermescontrol.ui.chat.markdown.parseBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val HIGHLIGHTS =
    searchHighlightColors(
        HermesStatusColors(
            success = StatusGreen,
            successContainer = StatusGreenContainer,
            onSuccess = Color.White,
            warning = StatusYellow,
            warningContainer = StatusYellowContainer,
            onWarning = Color.White,
            error = StatusRed,
            errorContainer = StatusRedContainer,
            onError = Color.White,
            info = StatusBlue,
            infoContainer = StatusBlueContainer,
            onInfo = Color.White,
        ),
    )

private fun bullets(
    n: Int,
    text: (Int) -> String = { "item $it" },
) = (1..n).joinToString("\n") { "- ${text(it)}" }

private fun coalesce(md: String) = coalesceBulletRuns(parseBlocks(md))

/** Stand-in for TextMeasurer: prefix width grows with font size, like the real bullet glyph. */
private fun fakePrefixWidth(fontSizeSp: Float): (String) -> TextUnit =
    { prefix -> (prefix.length * fontSizeSp * 0.5f).sp }

private fun render(
    run: BulletRun,
    query: String = "",
    density: Density = Density(1f),
    fontSizeSp: Float = 14f,
) = buildBulletRunText(run, density, Color.Black, query, false, Color.Blue, HIGHLIGHTS, fakePrefixWidth(fontSizeSp))

class MarkdownBulletRunTest {
    @Test
    fun longPlainRunCoalescesIntoOneBlock() {
        val out = coalesce(bullets(1000))
        assertEquals(1, out.size)
        assertEquals(1000, (out.single() as BulletRun).items.size)
    }

    @Test
    fun shortRunStaysUncoalesced() {
        val out = coalesce(bullets(MIN_BULLET_RUN - 1))
        assertEquals(MIN_BULLET_RUN - 1, out.size)
        assertTrue(out.all { it is MdBlock.Bullet })
    }

    @Test
    fun runAtThresholdCoalesces() {
        assertTrue(coalesce(bullets(MIN_BULLET_RUN)).single() is BulletRun)
    }

    @Test
    fun rtlBulletBreaksTheRunAndKeepsOldRenderer() {
        val md = bullets(40) { if (it == 20) "مرحبا بالعالم" else "item $it" }
        val out = coalesce(md)
        assertEquals(3, out.size)
        assertEquals(19, (out[0] as BulletRun).items.size)
        assertTrue(out[1] is MdBlock.Bullet)
        assertEquals(20, (out[2] as BulletRun).items.size)
    }

    @Test
    fun fullyRtlListNeverCoalesces() {
        val out = coalesce(bullets(40) { "مرحبا $it" })
        assertTrue(out.none { it is BulletRun })
    }

    @Test
    fun inlineMathBreaksTheRun() {
        val out = coalesce(bullets(40) { if (it == 20) "energy \$E=mc^2\$" else "item $it" })
        assertTrue(out[1] is MdBlock.Bullet)
        assertEquals(3, out.size)
    }

    @Test
    fun nestedBulletsStayInTheRunWithLevelGlyphAndIndent() {
        val md = bullets(20) + "\n  - child\n    - grandchild\n" + bullets(20)
        val run = coalesce(md).single() as BulletRun
        assertEquals(42, run.items.size)
        assertEquals(
            listOf(0, 1, 2, 0),
            listOf(run.items[19], run.items[20], run.items[21], run.items[22]).map { it.level },
        )
        val text = render(run)
        val lines = text.text.split("\n")
        assertTrue(lines[20].startsWith("\u25E6"))
        assertTrue(lines[21].startsWith("\u25AA"))
        val indents =
            text.paragraphStyles.map {
                it.item.textIndent!!
                    .firstLine.value
            }
        assertEquals(listOf(0f, 16f, 32f, 0f), listOf(indents[19], indents[20], indents[21], indents[22]))
    }

    @Test
    fun bulletWithNestedSourceFallsBack() {
        val md = bullets(20) + "\n- parent\n  ```\n  code\n  ```\n" + bullets(20)
        val out = coalesce(md)
        assertTrue(out.any { it is MdBlock.Bullet && it.nestedSource.isNotEmpty() })
        assertTrue(out.filterIsInstance<BulletRun>().flatMap { it.items }.all { it.nestedSource.isEmpty() })
    }

    @Test
    fun taskAndOrderedItemsNeverJoinARun() {
        val md = bullets(20) + "\n- [ ] todo\n1. one\n2. two\n" + bullets(20)
        val out = coalesce(md)
        assertEquals(2, out.count { it is BulletRun })
        assertTrue(out.any { it is MdBlock.Task })
        assertTrue(out.any { it is MdBlock.Ordered })
    }

    @Test
    fun surroundingBlocksAreUntouchedAndOrdered() {
        val out = coalesce("# Title\n\n" + bullets(30) + "\n\nTail paragraph")
        assertTrue(out.first() is MdBlock.Heading)
        assertTrue(out[1] is BulletRun)
        assertTrue(out.last() is MdBlock.Paragraph)
    }

    @Test
    fun mergedTextKeepsEveryLineWithGlyphs() {
        val run = coalesce(bullets(20)).single() as BulletRun
        val lines = render(run).text.split("\n")
        assertEquals(20, lines.size)
        assertTrue(lines.all { it.startsWith("\u2022") })
        assertTrue(lines.last().endsWith("item 20"))
    }

    @Test
    fun linksSurviveMerging() {
        val run =
            coalesce(bullets(20) { if (it == 5) "see [docs](https://example.com/a) now" else "item $it" })
                .single() as BulletRun
        val text = render(run)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        assertEquals("https://example.com/a", (links.single().item as LinkAnnotation.Url).url)
        assertEquals("docs", text.text.substring(links.single().start, links.single().end))
    }

    @Test
    fun bareUrlsStayLinks() {
        val run = coalesce(bullets(20) { if (it == 3) "https://example.com/x" else "item $it" }).single() as BulletRun
        val text = render(run)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
    }

    @Test
    fun searchHighlightLandsOnTheMatchOffsets() {
        val run = coalesce(bullets(20)).single() as BulletRun
        val text = render(run, query = "item 7")
        val spans = text.spanStyles.filter { it.item.background != Color.Unspecified }
        assertTrue(spans.isNotEmpty())
        spans.forEach { assertEquals("item 7", text.text.substring(it.start, it.end)) }
    }

    @Test
    fun noHighlightWithoutQuery() {
        val run = coalesce(bullets(20)).single() as BulletRun
        assertTrue(render(run).spanStyles.none { it.item.background != Color.Unspecified })
    }

    @Test
    fun levelOffsetIsPhysicalDpAcrossFontScales() {
        val run = coalesce(bullets(20) + "\n  - child\n").single() as BulletRun
        val normal = render(run, density = Density(2f, fontScale = 1f))
        val big = render(run, density = Density(2f, fontScale = 2f))
        val n =
            normal.paragraphStyles[20]
                .item.textIndent!!
                .firstLine.value
        val b =
            big.paragraphStyles[20]
                .item.textIndent!!
                .firstLine.value
        // 16dp at density 2 = 32px; sp value shrinks by fontScale so physical size stays 16dp.
        assertEquals(n, b * 2f, 0.001f)
    }

    @Test
    fun hangingIndentIncludesTheFontRelativePrefix() {
        val run = coalesce(bullets(20)).single() as BulletRun
        val small =
            render(run, fontSizeSp = 14f)
                .paragraphStyles
                .first()
                .item.textIndent!!
        val large =
            render(run, fontSizeSp = 28f)
                .paragraphStyles
                .first()
                .item.textIndent!!
        // Wrapped lines must sit right of the first line by the prefix width, which grows with the font.
        assertTrue(small.restLine.value > small.firstLine.value)
        assertEquals(
            (small.restLine.value - small.firstLine.value) * 2f,
            large.restLine.value - large.firstLine.value,
            0.001f,
        )
    }

    @Test
    fun oneParagraphStylePerBullet() {
        val run = coalesce(bullets(50)).single() as BulletRun
        assertEquals(50, render(run).paragraphStyles.size)
    }
}
