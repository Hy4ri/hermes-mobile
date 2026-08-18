package com.m57.hermescontrol.ui.chat.fullbleed

import android.content.ClipData
import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.SecurityRiskChip
import com.m57.hermescontrol.ui.chat.TodoTaskCard
import com.m57.hermescontrol.ui.chat.ToolSchemaRegistry
import com.m57.hermescontrol.ui.chat.ToolStatus
import com.m57.hermescontrol.ui.chat.components.DiffViewCard
import com.m57.hermescontrol.ui.chat.extractTodosFromJson
import com.m57.hermescontrol.ui.chat.formatChatTimestamp
import com.m57.hermescontrol.ui.chat.tool.ToolView
import com.m57.hermescontrol.ui.chat.tool.ToolViewBuilder
import com.m57.hermescontrol.ui.chat.tool.ToolViewStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Inline, always-expanded rendering of a single tool call — the mobile port of
 * the desktop app's `ToolEntry` row (apps/desktop/src/components/assistant-ui/
 * tool/fallback.tsx).
 *
 * Design: no Card chrome, no tap-to-collapse. The `ToolView` produced by
 * [ToolViewBuilder] is rendered in full (scaffold-dimmed, like desktop's
 * `data-conversation-scaffold` grey): leading glyph + title + count/diff/duration
 * meta, then the body in desktop-exact order — error → terminal (`$` command +
 * exit chip + stdout/stderr, stderr deliberately NOT red) → image → search hits
 * → inline diff (auto-open for file edits) → plain detail. A settled file edit
 * with no diff is hidden (desktop hides diff-less `write_file` rehydrates).
 *
 * Mobile-only affordances kept: tool.progress preview line, the security risk
 * chip, a copy button, and a raw-JSON link (inline, never the only path to the
 * content — the parsed view is already shown).
 */
@Composable
internal fun InlineToolRow(
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

    val view =
        remember(message.content, message.toolName, message.toolStatus) {
            buildToolView(message)
        }

    // Desktop hides a settled file edit that carries no diff (almost always a
    // diff-less `write_file` rehydrate) — it reads as a dead duplicate of the
    // real diff row. Keep in-flight writes and failures visible.
    val isFileEdit =
        message.toolName in setOf("edit_file", "patch", "write_file")
    if (isFileEdit &&
        message.toolStatus != ToolStatus.RUNNING &&
        view.status != ToolViewStatus.ERROR &&
        view.inlineDiff == null
    ) {
        return
    }

    val config = ToolSchemaRegistry.getDisplayConfig(message.toolName)
    val statusColors = LocalHermesStatusColors.current
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 3.dp)
                .testTag("inline_tool_row"),
    ) {
        Column(
            modifier = Modifier.animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── Header line: glyph + title + count/diff-stat/duration meta ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ToolGlyph(
                    status = view.status,
                    running = message.toolStatus == ToolStatus.RUNNING,
                    icon = config.iconEmoji,
                    statusColors = statusColors,
                    contentColor = contentColor,
                )
                Text(
                    text = view.title.ifBlank { message.toolName ?: stringResource(R.string.chat_tool_fallback) },
                    style =
                        MaterialTheme.typography.labelMedium.copy(
                            color = contentColor,
                            fontFamily = FontFamily.Monospace,
                        ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                view.countLabel?.let {
                    Text(
                        text = it,
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = contentColor.copy(alpha = 0.6f),
                                fontFamily = FontFamily.Monospace,
                            ),
                    )
                }
                if (isFileEdit && view.diffStats != null) {
                    val stats = view.diffStats!!
                    if (stats.added > 0) {
                        Text(
                            text = "+${stats.added}",
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    color = statusColors.success,
                                    fontFamily = FontFamily.Monospace,
                                ),
                        )
                    }
                    if (stats.removed > 0) {
                        Text(
                            text = "-${stats.removed}",
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    color = statusColors.error,
                                    fontFamily = FontFamily.Monospace,
                                ),
                        )
                    }
                }
                if (!isFileEdit && view.durationLabel != null) {
                    Text(
                        text = view.durationLabel!!,
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = contentColor.copy(alpha = 0.5f),
                            ),
                    )
                }
            }

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
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ── Security risk chip (tool.output_risk) ──
            val riskData = message.toolOutputRiskData
            if (riskData != null &&
                (riskData.risk == "medium" || riskData.risk == "high" || riskData.redacted)
            ) {
                SecurityRiskChip(riskData, contentColor)
            }

            // ── Body (desktop-exact order) ──
            InlineToolBody(view = view, contentColor = contentColor, statusColors = statusColors)

            // ── Footer: timestamp + raw-JSON link + copy ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                var showRaw by remember { mutableStateOf(false) }
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
                var showCopy by remember { mutableStateOf(false) }

                LaunchedEffect(showCopy) {
                    if (showCopy) {
                        delay(4000)
                        showCopy = false
                    }
                }

                Text(
                    text = formatChatTimestamp(message.timestamp, DateFormat.is24HourFormat(LocalContext.current)),
                    color = contentColor.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )

                if (showRaw) {
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
                                .clickable { showRaw = false },
                    )
                } else {
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
                                .clickable { showRaw = true },
                    )
                }

                if (showRaw) {
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
                }

                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, message.content)))
                        }
                        showCopy = true
                    },
                    modifier = Modifier.size(28.dp).testTag("chat_tool_copy"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.content_desc_copy),
                        tint = if (showCopy) statusColors.success else contentColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * The expanded body of a tool row — mirrors desktop `ToolEntry`'s open body
 * (apps/desktop fallback.tsx lines ~597-704): error line, terminal transcript
 * (`$` command + exit chip + split stdout/stderr with stderr NOT destructive),
 * image, search hits, inline diff (auto-open), then plain detail.
 */
@Composable
private fun InlineToolBody(
    view: ToolView,
    contentColor: Color,
    statusColors: HermesStatusColors,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

        // ── Terminal: $ command + exit chip + stdout/stderr ──
        val isTerminal =
            view.stdout != null || view.stderr != null || view.exitCode != null || view.terminalCommand != null
        if (isTerminal) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (view.terminalCommand != null) {
                    Text(
                        text = "$ ${view.terminalCommand}",
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = contentColor.copy(alpha = 0.75f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                    )
                }
                view.stdout?.let {
                    SelectionContainerText(it, contentColor.copy(alpha = 0.9f))
                }
                view.stderr?.let {
                    // stderr intentionally NOT painted destructive (desktop parity).
                    SelectionContainerText(it, contentColor.copy(alpha = 0.6f))
                }
                view.exitCode?.let { code ->
                    Text(
                        text = stringResource(R.string.chat_tool_exit_code, code),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = if (code == 0) statusColors.success else statusColors.warning,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                            ),
                    )
                }
            }
        } else {
            // ── Image ──
            view.imageUrl?.let { url ->
                Text(
                    text = "🔗 $url",
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = contentColor.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                        ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ── Search hits ──
            if (!view.searchHits.isNullOrEmpty()) {
                view.searchQuery?.let { q ->
                    Text(
                        text = "Search: $q",
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = contentColor.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                }
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

            // ── Inline diff (auto-open for file edits) ──
            if (view.inlineDiff != null) {
                DiffViewCard(
                    diffText = view.inlineDiff,
                    filePath = view.diffPath,
                )
            }

            // ── Plain detail body ──
            if (view.detail.isNotBlank() && view.inlineDiff == null) {
                val renderAsCode =
                    messageToolRendersDetailAsCode(view)
                if (renderAsCode) {
                    SelectionContainerText(view.detail, contentColor.copy(alpha = 0.9f))
                } else {
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
        }
    }
}

@Composable
private fun SelectionContainerText(
    text: String,
    color: Color,
) {
    SelectionContainer {
        Text(
            text = text,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
        )
    }
}

/**
 * Desktop renders the detail as mono code for terminal/execute_code/read_file;
 * everything else as compact markdown/prose. Mobile has no markdown engine here,
 * so non-code detail is plain text (desktop parity for the code path, prose for
 * the rest).
 */
private fun messageToolRendersDetailAsCode(view: ToolView): Boolean = view.status != ToolViewStatus.ERROR

/**
 * Leading glyph for a tool row — spinner while running, SUCCESS IS SILENT
 * (desktop parity: a completed row reads as done with no checkmark), error
 * red, warning amber; falls back to the tool's emoji icon.
 */
@Composable
private fun ToolGlyph(
    status: ToolViewStatus,
    running: Boolean,
    icon: String,
    statusColors: HermesStatusColors,
    contentColor: Color,
) {
    if (running) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.secondary,
        )
        return
    }
    when (status) {
        ToolViewStatus.ERROR -> {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                tint = statusColors.error,
                modifier = Modifier.size(14.dp),
            )
        }
        ToolViewStatus.WARNING -> {
            Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = null,
                tint = statusColors.warning,
                modifier = Modifier.size(14.dp),
            )
        }
        ToolViewStatus.SUCCESS -> {
            // Silent — no checkmark (desktop parity).
            Text(
                text = icon,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
        else -> {
            Text(
                text = icon,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Build the [ToolView] for a tool message — extracts args/result sub-objects
 * from the tool.complete payload (new format) with legacy top-level fallback,
 * mirrors ToolResultParser.parseToolOutput, then runs the desktop-ported engine.
 */
internal fun buildToolView(message: ChatMessage): ToolView {
    val trimmed = message.content.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
        return ToolViewBuilder.build(
            toolName = message.toolName ?: "tool",
            args = null,
            result = null,
            isError = message.toolStatus == ToolStatus.FAILED,
            running = message.toolStatus == ToolStatus.RUNNING,
        )
    }
    val element =
        runCatching { OkHttpProvider.json.parseToJsonElement(trimmed) }.getOrNull()
            as? JsonObject ?: return ToolViewBuilder.build(
            toolName = message.toolName ?: "tool",
            args = null,
            result = null,
            isError = message.toolStatus == ToolStatus.FAILED,
            running = message.toolStatus == ToolStatus.RUNNING,
        )

    val resolvedToolName =
        message.toolName
            ?: (element["name"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
    val args = element["args"] as? JsonObject
    val result = element["result"] as? JsonObject ?: element

    return ToolViewBuilder.build(
        toolName = resolvedToolName ?: "tool",
        args = args,
        result = result,
        isError = message.toolStatus == ToolStatus.FAILED,
        running = message.toolStatus == ToolStatus.RUNNING,
    )
}
