package com.m57.hermescontrol.ui.chat.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the token-compaction math in [ContextUsageChip] — the only
 * non-trivial pure logic in the context meter. Formats must stay compact so
 * the chip fits above the composer on a narrow phone screen.
 */
class ContextUsageChipTest {
    @Test
    fun formatTokens_subThousand_returnsRawCount() {
        assertEquals("950", formatTokens(950))
        assertEquals("0", formatTokens(0))
    }

    @Test
    fun formatTokens_thousands_dropsTrailingPointZero() {
        // 262_144 -> "262k" (no ".0"), not "262.1k".
        assertEquals("262k", formatTokens(262_144))
        // 12_300 -> "12.3k" (one decimal preserved).
        assertEquals("12.3k", formatTokens(12_300))
    }

    @Test
    fun formatTokens_millions_usesMSuffix() {
        assertEquals("1M", formatTokens(1_000_000))
        assertEquals("2M", formatTokens(2_048_000))
    }

    @Test
    fun formatTokens_exactThousandBoundary_roundsDownCleanly() {
        // 1_000 -> "1k" not "1.0k".
        assertEquals("1k", formatTokens(1_000))
        assertEquals("100k", formatTokens(100_000))
    }
}
