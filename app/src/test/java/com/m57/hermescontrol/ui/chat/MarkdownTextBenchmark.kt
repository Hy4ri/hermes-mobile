package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.markdown.parseBlocks
import org.junit.Test
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

class MarkdownTextBenchmark {
    @Test
    fun benchmarkParseBlocks() {
        val md =
            """
            # Header
            - item 1
            - item 2
            - item 3

            1. ordered 1
            2. ordered 2

            - [ ] task 1
            - [x] task 2

            Some text with some paragraphs and things.
            It's a long test string to give the parser something to do.

            ```kotlin
            fun main() {
                println("hello")
            }
            ```

            > A quote
            > Another quote
            """.trimIndent()

        // Warmup
        for (i in 1..500) {
            parseBlocks(md)
        }

        val time =
            measureTimeMillis {
                for (i in 1..10000) {
                    parseBlocks(md)
                }
            }

        println("BENCHMARK_RESULT: parseBlocks 10000 iterations took ${time}ms")
    }

    @Test
    fun benchmarkParseBlocksThousandBullets() {
        val md = (1..1000).joinToString("\n") { "- $it" }

        // Cold: first parse in this JVM, before any warmup.
        val cold = measureNanoTime { parseBlocks(md) }

        repeat(50) { parseBlocks(md) }
        val iterations = 200
        val warmNanos = measureNanoTime { repeat(iterations) { parseBlocks(md) } }

        println("BENCHMARK_RESULT: parseBlocks 1000 bullets cold=${cold / 1_000_000.0}ms")
        println("BENCHMARK_RESULT: parseBlocks 1000 bullets warm avg=${warmNanos / iterations / 1_000_000.0}ms")
    }
}
