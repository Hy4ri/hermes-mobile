package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.Density
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

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

private fun render(
    run: BulletRun,
    query: String = "",
    density: Density = Density(1f),
) = buildBulletRunText(run, density, Color.Black, query, false, Color.Blue, HIGHLIGHTS)

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
    fun nestedBulletKeepsOldRenderer() {
        val md = bullets(30) + "\n  - child\n" + bullets(30)
        val out = coalesce(md)
        assertTrue(out.any { it is BulletRun })
        val plainInRuns = out.filterIsInstance<BulletRun>().flatMap { it.items }
        assertTrue(plainInRuns.all { it.nestedSource.isEmpty() })
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
    fun indentScalesLikeDpNotSp() {
        val run = coalesce(bullets(20)).single() as BulletRun
        val normal = render(run, density = Density(2f, fontScale = 1f))
        val bigFont = render(run, density = Density(2f, fontScale = 2f))
        val a =
            normal.paragraphStyles
                .first()
                .item.textIndent!!
        val b =
            bigFont.paragraphStyles
                .first()
                .item.textIndent!!
        // Text scales by fontScale at layout; dp->sp conversion must cancel it so physical dp is fixed.
        assertEquals(a.restLine.value, b.restLine.value * 2f, 0.001f)
        assertEquals(14f, a.restLine.value, 0.001f)
    }

    @Test
    fun oneParagraphStylePerBullet() {
        val run = coalesce(bullets(50)).single() as BulletRun
        assertEquals(50, render(run).paragraphStyles.size)
    }

    @Test
    fun streamingGrowthDiagnostic() {
        val sizes = listOf(100, 250, 500, 750, 1000)
        val sb = StringBuilder()
        var done = 0
        val results = mutableListOf<String>()
        // warm the JIT
        repeat(30) { coalesce(bullets(1000)) }
        for (target in sizes) {
            while (done < target) {
                sb.append("- item ${++done}\n")
            }
            val md = sb.toString()
            val n = 50
            val parse = measureNanoTime { repeat(n) { parseBlocks(md) } } / n / 1e6
            val full =
                measureNanoTime {
                    repeat(n) {
                        (coalesce(md).firstOrNull { it is BulletRun } as? BulletRun)?.let { render(it) }
                    }
                } / n / 1e6
            results += "$target bullets: parse=${"%.2f".format(parse)}ms parse+coalesce+build=${"%.2f".format(full)}ms"
        }
        results.forEach { println("BENCHMARK_RESULT: stream $it") }
        assertFalse(results.isEmpty())
    }
}
