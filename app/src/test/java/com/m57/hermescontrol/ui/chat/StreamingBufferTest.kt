package com.m57.hermescontrol.ui.chat

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingBufferTest {
    @Test
    fun channelsThrottleIndependentlyAndFlushCumulativeText() =
        runTest {
            var now = 100L
            val tokens = mutableListOf<String>()
            val reasoning = mutableListOf<String>()
            val tokenBuffer = StreamingBuffer(this, 33L, { now }, tokens::add)
            val reasoningBuffer = StreamingBuffer(this, 33L, { now }, reasoning::add)

            tokenBuffer.append("A", flushImmediately = false)
            tokenBuffer.append("B", flushImmediately = false)
            reasoningBuffer.append("R", flushImmediately = false)
            assertEquals(listOf("A"), tokens)
            assertEquals(listOf("R"), reasoning)

            now += 34
            testScheduler.advanceTimeBy(34L)
            assertEquals(listOf("A", "AB"), tokens)
            assertEquals(listOf("R"), reasoning)
        }

    @Test
    fun transitionFlushCancelsTrailingJobWithoutDroppingTheNextDelta() =
        runTest {
            val output = mutableListOf<String>()
            val buffer = StreamingBuffer(this, 33L, { 100L }, output::add)
            buffer.append("A", flushImmediately = false)
            buffer.append("B", flushImmediately = false)
            buffer.flushPending()
            testScheduler.advanceTimeBy(34L)
            assertEquals(listOf("A", "AB"), output)

            buffer.append("C", flushImmediately = true)
            assertEquals(listOf("A", "AB", "ABC"), output)
        }

    @Test
    fun clearCancelsTrailingJobAndStartsFresh() =
        runTest {
            val output = mutableListOf<String>()
            val buffer = StreamingBuffer(this, 33L, { 100L }, output::add)
            buffer.append("Old", flushImmediately = false)
            buffer.append(" tail", flushImmediately = false)
            buffer.clear()
            testScheduler.advanceTimeBy(34L)
            buffer.append("Fresh", flushImmediately = false)
            assertEquals(listOf("Old", "Fresh"), output)
        }
}
