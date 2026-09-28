package com.m57.hermescontrol.ui.chat

import android.content.ClipData
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.components.DiffViewCard
import com.m57.hermescontrol.ui.chat.components.FileViewCard
import com.m57.hermescontrol.ui.chat.tool.ToolView
import com.m57.hermescontrol.ui.chat.tool.ToolViewBuilder
import com.m57.hermescontrol.ui.chat.tool.ToolViewCache
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
): Pair<String, String?>? {
    val titleIsGeneric = view.title.equals(ToolViewBuilder.genericTitleFor(toolName), ignoreCase = true)
    val subtitle = view.subtitle.takeIf { it.isNotBlank() && it != view.title }
    val statsSuffix = view.diffStats?.let { " (+${it.added}/-${it.removed})" } ?: ""

    val firstLine: String
    val secondLine: String?
    if (titleIsGeneric) {
        firstLine =
            buildString {
                if (subtitle != null) {
                    append(subtitle)
                }
                append(statsSuffix)
                view.countLabel?.let { append(" ($it)") }
            }
        secondLine = null
    } else {
        firstLine =
            buildString {
                append(view.title)
                append(statsSuffix)
                view.countLabel?.let { append(" ($it)") }
            }
        secondLine = subtitle
    }

    return firstLine.takeIf { it.isNotBlank() }?.let { it to secondLine }
}

@Composable
internal fun ToolBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var showRawJson by remember { mutableStateOf(false) }
    val chipColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val statusColors = LocalHermesStatusColors.current

    val view =
        remember(message.content, message.toolName, message.isToolRunning) {
            ToolViewCache.getOrParse(message.content, message.toolName, message.isToolRunning)
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
                ToolBubbleHeader(message, config, contentColor, statusColors, view?.serverDisplayName)

                // ── Tool progress preview (tool.progress) ──
                if (message.isToolRunning && !message.progressPreview.isNullOrEmpty()) {
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

                // ── Collapsed summary: icon + title, then subtitle ──
                if (!expanded && view != null) {
                    composeToolSummaryLines(view, message.toolName)?.let { (firstLine, secondLine) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp, start = 22.dp),
                        ) {
                            Icon(
                                imageVector = config.icon,
                                contentDescription = null,
                                tint = contentColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
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
                            )
                        }
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
                        ToolRawJsonView(rawContent = message.content)

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
                        // Unparseable content — show raw JSON
                        ToolRawJsonView(rawContent = message.content)
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
