package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.ui.chat.components.DiffViewCard
import com.m57.hermescontrol.ui.chat.components.FileViewCard
import com.m57.hermescontrol.ui.chat.tool.ToolView

/**
 * Renders the engine's [ToolView] — the expanded body of a tool card.
 *
 * Order: error line → terminal streams ($ command / stdout / stderr / exit
 * code) → file diff → search hits → plain detail → duration footer.
 */
@Composable
internal fun ExpandedToolContent(
    view: ToolView,
    contentColor: Color,
    statusColors: HermesStatusColors,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val isTerminal =
            view.stdout != null ||
                view.stderr != null ||
                view.exitCode != null ||
                view.terminalCommand != null

        // ── Error line ──
        view.error?.let {
            Text(
                text = stringResource(R.string.chat_tool_execution_error, it),
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        color = statusColors.error,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
            )
        }

        // ── Terminal: $ command + streams + exit code ──
        if (isTerminal) {
            view.terminalCommand?.takeIf { it.isNotEmpty() }?.let { command ->
                Text(
                    text = "$ $command",
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = contentColor.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                )
            }
            view.stdout?.let {
                Text(
                    text = it,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = contentColor.copy(alpha = 0.9f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                )
            }
            view.stderr?.let {
                Text(
                    text = it,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = contentColor.copy(alpha = 0.6f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                )
            }
            view.exitCode?.let { code ->
                if (code != 0) {
                    Text(
                        text = stringResource(R.string.chat_tool_exit_code, code),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = statusColors.warning,
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                }
            }
        } else {
            // ── Generated/resolved image preview ──
            view.imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = view.title,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(8.dp)),
                )
            }

            // ── File diff ──
            if (view.inlineDiff != null) {
                DiffViewCard(
                    diffText = view.inlineDiff,
                    filePath = view.diffPath,
                )
            } else if (view.fileContent != null) {
                FileViewCard(
                    content = view.fileContent,
                    filePath = view.filePath,
                )
            }

            // ── Search hits ──
            if (!view.searchHits.isNullOrEmpty()) {
                view.detailLabel?.let { label ->
                    Text(
                        text = label,
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = contentColor.copy(alpha = 0.6f),
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                }
                view.searchHits.forEach { hit ->
                    Column(modifier = Modifier.padding(top = 2.dp)) {
                        if (hit.title.isNotEmpty()) {
                            Text(
                                text = hit.title,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        color = contentColor.copy(alpha = 0.9f),
                                        fontWeight = FontWeight.Medium,
                                    ),
                            )
                        }
                        if (hit.snippet.isNotEmpty()) {
                            Text(
                                text = hit.snippet,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        color = contentColor.copy(alpha = 0.6f),
                                        fontSize = 11.sp,
                                    ),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (hit.url.isNotEmpty()) {
                            Text(
                                text = hit.url,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        color = contentColor.copy(alpha = 0.7f),
                                        fontSize = 11.sp,
                                    ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // ── Plain detail body ──
            if (view.detail.isNotBlank() && view.inlineDiff == null && view.fileContent == null) {
                Text(
                    text = view.detail,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = contentColor.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                        ),
                )
            }
        }

        view.outputCut?.let { omitted ->
            Text(
                text = stringResource(R.string.chat_tool_output_omitted, omitted),
                style = MaterialTheme.typography.labelSmall.copy(color = statusColors.warning),
            )
        }

        // ── Duration footer ──
        view.durationLabel?.let {
            Text(
                text = stringResource(R.string.tool_duration, it),
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        color = contentColor.copy(alpha = 0.5f),
                    ),
            )
        }
    }
}
