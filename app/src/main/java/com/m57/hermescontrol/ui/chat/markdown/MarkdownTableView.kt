package com.m57.hermescontrol.ui.chat.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.util.BidiUtils

private val TABLE_COL_WIDTH = 140.dp

@Composable
fun MarkdownTable(
    block: MdBlock.Table,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val isRtl =
        remember(block) {
            block.header.any { BidiUtils.isRtlText(it) } ||
                block.rows.any { row -> row.any { BidiUtils.isRtlText(it) } }
        }
    val tableDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
    val headerBg = textColor.copy(alpha = 0.08f)
    val alignments = block.alignments
    CompositionLocalProvider(LocalLayoutDirection provides tableDirection) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
        ) {
            // Header row
            Row(modifier = Modifier.background(headerBg)) {
                block.header.forEachIndexed { idx, cell ->
                    val cellRtl = BidiUtils.isRtlText(cell)
                    Text(
                        text = if (cellRtl) BidiUtils.anchorTrailingRtl(cell) else cell,
                        textAlign = tableTextAlign(alignments.getOrNull(idx)),
                        color = textColor,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                textDirection = if (cellRtl) TextDirection.Rtl else TextDirection.Ltr,
                            ),
                        modifier =
                            Modifier
                                .width(TABLE_COL_WIDTH)
                                .padding(6.dp),
                    )
                }
            }
            HorizontalDivider(color = textColor.copy(alpha = 0.25f))
            // Body rows
            block.rows.forEach { row ->
                Row {
                    row.forEachIndexed { idx, cell ->
                        val cellRtl = BidiUtils.isRtlText(cell)
                        Text(
                            text = if (cellRtl) BidiUtils.anchorTrailingRtl(cell) else cell,
                            textAlign = tableTextAlign(alignments.getOrNull(idx)),
                            color = textColor,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    textDirection = if (cellRtl) TextDirection.Rtl else TextDirection.Ltr,
                                ),
                            modifier =
                                Modifier
                                    .width(TABLE_COL_WIDTH)
                                    .padding(6.dp),
                        )
                    }
                }
                HorizontalDivider(color = textColor.copy(alpha = 0.1f))
            }
        }
    }
}

private fun tableTextAlign(align: TableAlign?): TextAlign? =
    when (align) {
        TableAlign.CENTER -> TextAlign.Center
        TableAlign.RIGHT -> TextAlign.End
        else -> null
    }
