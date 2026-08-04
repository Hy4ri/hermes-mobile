package com.m57.hermescontrol.ui.chat

import android.content.ClipData
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.components.DiffViewCard
import com.m57.hermescontrol.ui.chat.tool.ToolView
import com.m57.hermescontrol.ui.chat.tool.ToolViewBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Composes the collapsed summary lines for a tool card.
 *
 * The header row already shows the tool name, so when the engine's title is
 * just the generic humanized name ("Fact Store" for fact_store) it is
 * dropped and the subtitle merges into the emoji line instead — one
 * identity per bubble. Descriptive titles ("Searched \"cats\"", "Ran ls")
 * stay on line one with the subtitle on line two.
 *
 * Returns null when there is nothing to show (lone emoji, no subtitle).
 */
internal fun composeToolSummaryLines(
    view: ToolView,
    toolName: String?,
    emoji: String,
): Pair<String, String?>? {
    val titleIsGeneric = view.title.equals(ToolViewBuilder.genericTitleFor(toolName), ignoreCase = true)
    val subtitle = view.subtitle.takeIf { it.isNotBlank() && it != view.title }

    val firstLine: String
    val secondLine: String?
    if (titleIsGeneric) {
        firstLine =
            buildString {
                append(emoji)
                if (subtitle != null) {
                    append(" $subtitle")
                }
                view.countLabel?.let { append(" ($it)") }
            }
        secondLine = null
    } else {
        firstLine =
            buildString {
                append("$emoji ${view.title}")
                view.countLabel?.let { append(" ($it)") }
            }
        secondLine = subtitle
    }

    return firstLine.takeIf { it.trim() != emoji }?.let { it to secondLine }
}

@Composable
internal fun ToolBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    val todoItems = extractTodosFromJson(message.content)
    if (todoItems != null) {
        TodoTaskCard(
            items = todoItems,
            isRunning = message.toolStatus == ToolStatus.RUNNING,
            riskData = message.toolOutputRiskData,
            modifier = modifier,
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    var showRawJson by remember { mutableStateOf(false) }
    val chipColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val statusColors = LocalHermesStatusColors.current

    val view =
        remember(message.content, message.toolName, message.toolStatus) {
            parseToolOutput(message.content, message.toolName, message.toolStatus == ToolStatus.RUNNING)
        }
    val config = ToolSchemaRegistry.getDisplayConfig(message.toolName)

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showCopyButton by remember { mutableStateOf(false) }

    // Auto-dismiss copy button after 4 seconds
    LaunchedEffect(showCopyButton) {
        if (showCopyButton) {
            delay(4000)
            showCopyButton = false
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 1.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Card(
            onClick = { expanded = !expanded },
            colors = CardDefaults.cardColors(containerColor = chipColor),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .animateContentSize()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                // ── Header row: icon + tool name ──
                HeaderRow(message, config, contentColor, statusColors)

                // ── Tool progress preview (tool.progress) ──
                if (message.toolStatus == ToolStatus.RUNNING && !message.progressPreview.isNullOrEmpty()) {
                    Text(
                        text = message.progressPreview,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = contentColor.copy(alpha = 0.7f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, start = 22.dp),
                    )
                }

                // ── Security risk chip (tool.output_risk) ──
                val riskData = message.toolOutputRiskData
                if (riskData != null && (riskData.risk == "medium" || riskData.risk == "high" || riskData.redacted)) {
                    SecurityRiskChip(riskData, contentColor)
                }

                // ── Collapsed summary: emoji + title, then subtitle ──
                if (!expanded && view != null) {
                    composeToolSummaryLines(view, message.toolName, config.iconEmoji)?.let { (firstLine, secondLine) ->
                        Text(
                            text = firstLine,
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    color = contentColor.copy(alpha = 0.7f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp, start = 22.dp),
                        )
                        if (secondLine != null) {
                            Text(
                                text = secondLine,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        color = contentColor.copy(alpha = 0.5f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 1.dp, start = 22.dp),
                            )
                        }
                    }
                }

                // ── Expanded content ──
                if (expanded) {
                    Spacer(modifier = Modifier.height(6.dp))

                    if (showRawJson) {
                        // Raw JSON view — selectable + copy button
                        Box {
                            SelectionContainer {
                                Text(
                                    text = message.content,
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            color = contentColor.copy(alpha = 0.8f),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                        ),
                                )
                            }
                            CopyButton(
                                visible = showCopyButton,
                                textToCopy = message.content,
                                onCopy = { showCopyButton = false },
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 8.dp, y = (-8).dp),
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.chat_tool_show_parsed),
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            modifier =
                                Modifier
                                    .testTag("chat_tool_show_parsed")
                                    .clickable(role = Role.Button) { showRawJson = false },
                        )
                    } else if (view != null) {
                        // Clean structured expanded view
                        Box {
                            SelectionContainer {
                                ExpandedToolContent(view, contentColor, statusColors)
                            }
                            CopyButton(
                                visible = showCopyButton,
                                textToCopy = message.content,
                                onCopy = { showCopyButton = false },
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 8.dp, y = (-8).dp),
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.chat_tool_show_raw),
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            modifier =
                                Modifier
                                    .testTag("chat_tool_show_raw")
                                    .clickable(role = Role.Button) { showRawJson = true },
                        )
                    } else {
                        // Unparseable content — show raw JSON, selectable
                        Box {
                            SelectionContainer {
                                Text(
                                    text = message.content,
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            color = contentColor.copy(alpha = 0.8f),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                        ),
                                )
                            }
                            CopyButton(
                                visible = showCopyButton,
                                textToCopy = message.content,
                                onCopy = { showCopyButton = false },
                                modifier =
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 8.dp, y = (-8).dp),
                            )
                        }
                    }
                }

                // ── Timestamp ──
                Text(
                    text = formatTimestamp(message.timestamp, DateFormat.is24HourFormat(LocalContext.current)),
                    color = contentColor.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Renders the engine's [ToolView] — the expanded body of a tool card.
 *
 * Order: error line → terminal streams ($ command / stdout / stderr / exit
 * code) → file diff → search hits → plain detail → duration footer.
 */
@Composable
private fun ExpandedToolContent(
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
                                color = statusColors.error,
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                }
            }
        } else {
            // ── File diff ──
            if (view.inlineDiff != null) {
                DiffViewCard(
                    diffText = view.inlineDiff,
                    filePath = view.diffPath,
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
                                text = "🔗 ${hit.url}",
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
            if (view.detail.isNotBlank() && view.inlineDiff == null) {
                Text(
                    text = view.detail,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = contentColor.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                        ),
                )
            }
        }

        // ── Duration footer ──
        view.durationLabel?.let {
            Text(
                text = "Duration: $it",
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        color = contentColor.copy(alpha = 0.5f),
                    ),
            )
        }
    }
}

@Composable
private fun HeaderRow(
    message: ChatMessage,
    config: ToolDisplayConfig,
    contentColor: Color,
    statusColors: HermesStatusColors,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Status icon or spinner
        if (message.toolStatus == ToolStatus.RUNNING) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            val icon =
                when (message.toolStatus) {
                    ToolStatus.COMPLETED -> Icons.Filled.CheckCircle
                    ToolStatus.FAILED -> Icons.Filled.Error
                    else -> Icons.Filled.Build
                }
            val tint =
                when (message.toolStatus) {
                    ToolStatus.COMPLETED -> statusColors.success
                    ToolStatus.FAILED -> statusColors.error
                    else -> contentColor.copy(alpha = 0.6f)
                }
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        }

        Text(
            text = message.toolName ?: stringResource(R.string.chat_tool_fallback),
            style =
                MaterialTheme.typography.labelMedium.copy(
                    color = contentColor,
                    fontFamily = FontFamily.Monospace,
                ),
        )
    }
}

/**
 * Security risk chip for [tool.output_risk] events.
 *
 * Shows a compact ⚠ badge when the backend flagged tool output as risky.
 * Renders in the tool card between the header row and the summary line.
 */
@Composable
internal fun SecurityRiskChip(
    riskData: ToolOutputRiskData,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalHermesStatusColors.current
    val (chipColor, label) =
        when {
            riskData.risk == "high" -> statusColors.error to "Risky output"
            riskData.risk == "medium" -> statusColors.warning to "Caution"
            else -> statusColors.warning to "Redacted"
        }

    Row(
        modifier =
            modifier
                .padding(top = 4.dp, start = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "⚠",
            style = MaterialTheme.typography.labelSmall,
            color = chipColor,
        )
        Text(
            text = label,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                ),
            color = chipColor,
        )
        if (riskData.redacted && (riskData.risk == "high" || riskData.risk == "medium")) {
            Text(
                text = "· redacted",
                style = MaterialTheme.typography.labelSmall,
                color = chipColor.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun CopyButton(
    visible: Boolean,
    textToCopy: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 6.dp,
        ) {
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, textToCopy)))
                    }
                    onCopy()
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.content_desc_copy),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
