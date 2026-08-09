package com.m57.hermescontrol.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionsStatsFormatTest {
    @Test
    fun `under 1000 shows raw number`() {
        assertEquals("0", formatCompactCount(0))
        assertEquals("7", formatCompactCount(7))
        assertEquals("987", formatCompactCount(987))
        assertEquals("999", formatCompactCount(999))
    }

    @Test
    fun `thousands compact to 3 digits plus k`() {
        assertEquals("1k", formatCompactCount(1_000))
        assertEquals("10k", formatCompactCount(10_000))
        assertEquals("100k", formatCompactCount(100_987))
        assertEquals("999k", formatCompactCount(999_999))
    }

    @Test
    fun `fractional thousands keep one or two digits`() {
        assertEquals("1.5k", formatCompactCount(1_500))
        assertEquals("1.05k", formatCompactCount(1_050))
        assertEquals("12.5k", formatCompactCount(12_500))
    }

    @Test
    fun `millions compact to 3 digits plus m`() {
        assertEquals("1m", formatCompactCount(1_000_000))
        assertEquals("1.23m", formatCompactCount(1_234_567))
        assertEquals("100m", formatCompactCount(100_000_000))
        assertEquals("999m", formatCompactCount(999_999_999))
    }

    @Test
    fun `billions compact to b`() {
        assertEquals("1b", formatCompactCount(1_000_000_000))
        assertEquals("2b", formatCompactCount(2_000_000_000))
        assertEquals("2.15b", formatCompactCount(Int.MAX_VALUE))
    }
}
