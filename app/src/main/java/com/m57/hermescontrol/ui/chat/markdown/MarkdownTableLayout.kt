package com.m57.hermescontrol.ui.chat.markdown

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object MarkdownTableLayout {
    val MIN_CELL_WIDTH: Dp = 48.dp
    val MAX_CELL_WIDTH: Dp = 280.dp
    val CELL_PADDING: Dp = 12.dp

    /**
     * Maximum column count for which the table can be fitted proportionally to the available width.
     * Tables with 3 or fewer columns can easily fit on phone screens without horizontal scrolling.
     */
    const val MAX_FIT_COLUMNS = 3

    /**
     * Computes column widths for a markdown table.
     *
     * If [availableWidth] is finite and positive, and the table has [MAX_FIT_COLUMNS] or fewer columns,
     * the columns are fitted to fill [availableWidth] (proportional to content if sum > 0, otherwise evenly)
     * as long as each column receives at least [minCellWidth].
     *
     * Otherwise, each column sizes strictly to its intrinsic content width (clamped to [[minCellWidth], [maxCellWidth]]).
     *
     * @return [TableLayoutResult] containing the list of column widths and whether horizontal scrolling is needed.
     */
    fun computeColumnWidths(
        columnCount: Int,
        contentWidths: List<Dp>,
        availableWidth: Dp = Dp.Unspecified,
        minCellWidth: Dp = MIN_CELL_WIDTH,
        maxCellWidth: Dp = MAX_CELL_WIDTH,
    ): TableLayoutResult {
        if (columnCount <= 0) return TableLayoutResult(emptyList(), requiresScroll = false)

        val clampedIntrinsicWidths =
            List(columnCount) { index ->
                val w = contentWidths.getOrElse(index) { 0.dp }
                w.coerceIn(minCellWidth, maxCellWidth)
            }

        val hasValidAvailableWidth =
            availableWidth != Dp.Unspecified &&
                availableWidth.value.isFinite() &&
                availableWidth > 0.dp

        if (hasValidAvailableWidth && columnCount <= MAX_FIT_COLUMNS) {
            val minTotalWidth = minCellWidth * columnCount
            if (availableWidth >= minTotalWidth) {
                val intrinsicSum = clampedIntrinsicWidths.fold(0.dp) { acc, dp -> acc + dp }
                val fittedWidths =
                    if (intrinsicSum > 0.dp) {
                        val scale = (availableWidth / intrinsicSum).coerceAtLeast(0f)
                        val rawFitted = clampedIntrinsicWidths.map { (it * scale).coerceAtLeast(minCellWidth) }
                        val fittedSum = rawFitted.fold(0.dp) { acc, dp -> acc + dp }
                        val diff = availableWidth - fittedSum
                        if (rawFitted.isNotEmpty()) {
                            rawFitted.toMutableList().also { list ->
                                list[list.lastIndex] = (list.last() + diff).coerceAtLeast(minCellWidth)
                            }
                        } else {
                            rawFitted
                        }
                    } else {
                        val evenWidth = availableWidth / columnCount
                        List(columnCount) { evenWidth.coerceAtLeast(minCellWidth) }
                    }

                val totalFitted = fittedWidths.fold(0.dp) { acc, dp -> acc + dp }
                val fitsWithoutScroll = totalFitted <= availableWidth + 0.5.dp
                return TableLayoutResult(
                    widths = fittedWidths,
                    requiresScroll = !fitsWithoutScroll,
                )
            }
        }

        val totalIntrinsic = clampedIntrinsicWidths.fold(0.dp) { acc, dp -> acc + dp }
        val requiresScroll = !hasValidAvailableWidth || totalIntrinsic > availableWidth

        return TableLayoutResult(
            widths = clampedIntrinsicWidths,
            requiresScroll = requiresScroll,
        )
    }
}

internal data class TableLayoutResult(
    val widths: List<Dp>,
    val requiresScroll: Boolean,
)
