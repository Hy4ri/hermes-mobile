package com.m57.hermescontrol.ui.chat.markdown

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableLayoutTest {
    @Test
    fun emptyTableReturnsEmpty() {
        val result =
            MarkdownTableLayout.computeColumnWidths(
                columnCount = 0,
                contentWidths = emptyList(),
            )
        assertTrue(result.widths.isEmpty())
        assertFalse(result.requiresScroll)
    }

    @Test
    fun twoColumnsFitAvailableWidthWithoutScroll() {
        val availableWidth = 320.dp
        val contentWidths = listOf(80.dp, 120.dp) // sum = 200dp
        val result =
            MarkdownTableLayout.computeColumnWidths(
                columnCount = 2,
                contentWidths = contentWidths,
                availableWidth = availableWidth,
            )

        assertFalse(result.requiresScroll)
        assertEquals(2, result.widths.size)
        val sum = result.widths[0] + result.widths[1]
        assertEquals(availableWidth.value, sum.value, 0.01f)
        // Scaled proportionally: 80/200 * 320 = 128, 120/200 * 320 = 192
        assertEquals(128.dp.value, result.widths[0].value, 0.01f)
        assertEquals(192.dp.value, result.widths[1].value, 0.01f)
    }

    @Test
    fun wideTwoColumnsScaleDownToFitIfAboveMinCellWidth() {
        val availableWidth = 300.dp
        // Content widths sum to 400dp, both > min (48dp)
        val contentWidths = listOf(200.dp, 200.dp)
        val result =
            MarkdownTableLayout.computeColumnWidths(
                columnCount = 2,
                contentWidths = contentWidths,
                availableWidth = availableWidth,
            )

        assertFalse(result.requiresScroll)
        val sum = result.widths[0] + result.widths[1]
        assertEquals(availableWidth.value, sum.value, 0.01f)
        assertEquals(150.dp.value, result.widths[0].value, 0.01f)
        assertEquals(150.dp.value, result.widths[1].value, 0.01f)
    }

    @Test
    fun fourOrMoreColumnsFallbackToContentSizedAndScroll() {
        val availableWidth = 320.dp
        val contentWidths = listOf(100.dp, 100.dp, 100.dp, 100.dp) // 4 cols, sum 400dp > 320dp
        val result =
            MarkdownTableLayout.computeColumnWidths(
                columnCount = 4,
                contentWidths = contentWidths,
                availableWidth = availableWidth,
            )

        assertTrue(result.requiresScroll)
        assertEquals(4, result.widths.size)
        assertEquals(100.dp, result.widths[0])
        assertEquals(100.dp, result.widths[1])
        assertEquals(100.dp, result.widths[2])
        assertEquals(100.dp, result.widths[3])
    }

    @Test
    fun threeColumnsNarrowerThanMinimumCellFallbackToScroll() {
        // availableWidth is only 100dp, but 3 cols need at least 3 * 48dp = 144dp
        val availableWidth = 100.dp
        val contentWidths = listOf(50.dp, 50.dp, 50.dp)
        val result =
            MarkdownTableLayout.computeColumnWidths(
                columnCount = 3,
                contentWidths = contentWidths,
                availableWidth = availableWidth,
            )

        assertTrue(result.requiresScroll)
        assertEquals(3, result.widths.size)
        // Each clamped to at least min (48dp)
        assertEquals(50.dp, result.widths[0])
    }

    @Test
    fun unspecifiedAvailableWidthFallbackToIntrinsicAndScrollIfLarge() {
        val contentWidths = listOf(100.dp, 150.dp)
        val result =
            MarkdownTableLayout.computeColumnWidths(
                columnCount = 2,
                contentWidths = contentWidths,
                availableWidth = Dp.Unspecified,
            )

        // With Unspecified, cannot fit to screen, so requiresScroll is true (safe fallback)
        assertTrue(result.requiresScroll)
        assertEquals(100.dp, result.widths[0])
        assertEquals(150.dp, result.widths[1])
    }
}
