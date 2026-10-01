package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
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
import com.m57.hermescontrol.ui.chat.markdown.buildBulletRunText
import com.m57.hermescontrol.ui.chat.markdown.coalesceBulletRuns
import com.m57.hermescontrol.ui.chat.markdown.parseBlocks
import org.junit.Test
import kotlin.system.measureNanoTime

/** Diagnostic only (opt-in via -PincludeBenchmarks): per-update cost while a list streams from 1 to 1000 bullets. */
class MarkdownBulletRunBenchmark {
    private val highlights =
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

    private fun coalesce(md: String) = coalesceBulletRuns(parseBlocks(md))

    private fun bullets(n: Int) = (1..n).joinToString("\n") { "- item $it" }

    private fun render(run: BulletRun): AnnotatedString =
        buildBulletRunText(run, Density(1f), Color.Black, "", false, Color.Blue, highlights) { (it.length * 7).sp }

    @Test
    fun benchmarkStreamingGrowth() {
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
    }
}
