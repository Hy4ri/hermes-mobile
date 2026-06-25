package com.m57.hermescontrol.ui.chat

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.AssistantBubble
import com.m57.hermescontrol.theme.AssistantBubbleLight
import com.m57.hermescontrol.theme.StatusGreen
import com.m57.hermescontrol.theme.StatusRed
import com.m57.hermescontrol.theme.SystemMessageColor
import com.m57.hermescontrol.theme.ToolChipColor
import com.m57.hermescontrol.theme.ToolChipColorLight
import com.m57.hermescontrol.theme.UserBubble
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChatBubble(
    message: ChatMessage,
    isDarkTheme: Boolean,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * 0.80f

    AnimatedVisibility(
        visible = true,
        enter =
            fadeIn() +
                expandVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                ),
    ) {
        when (message.role) {
            MessageRole.USER -> {
                UserBubble(message, maxBubbleWidth, searchQuery, isCurrentMatch, modifier)
            }

            MessageRole.ASSISTANT -> {
                AssistantBubble(
                    message,
                    maxBubbleWidth,
                    isDarkTheme,
                    searchQuery,
                    isCurrentMatch,
                    modifier,
                )
            }

            MessageRole.SYSTEM -> {
                SystemBubble(message, modifier)
            }

            MessageRole.TOOL -> {
                ToolBubble(message, isDarkTheme, modifier)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: ChatMessage,
    maxWidth: androidx.compose.ui.unit.Dp,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
    var showCopyButton by remember { mutableStateOf(false) }

    // Auto-dismiss copy button after 4 seconds
    LaunchedEffect(showCopyButton) {
        if (showCopyButton) {
            delay(4000)
            showCopyButton = false
        }
    }

    val highlightedText =
        remember(message.content, searchQuery, isCurrentMatch) {
            if (searchQuery.isNotBlank()) {
                buildHighlightedString(message.content, searchQuery, isCurrentMatch)
            } else {
                AnnotatedString(message.content)
            }
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val gradientBrush =
            Brush.linearGradient(
                colors =
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary,
                    ),
            )
        val primary = MaterialTheme.colorScheme.primary
        val secondary = MaterialTheme.colorScheme.secondary
        val avgLuminance = (primary.luminance() + secondary.luminance()) / 2f
        val userBubbleTextColor =
            if (avgLuminance > 0.5f) {
                if (MaterialTheme.colorScheme.onPrimary.luminance() < 0.5f) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    Color(0xFF1A1A24)
                }
            } else {
                if (MaterialTheme.colorScheme.onPrimary.luminance() > 0.5f) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    Color.White
                }
            }
        Box {
            Surface(
                modifier =
                    Modifier
                        .widthIn(max = maxWidth)
                        .clip(
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = 16.dp,
                                bottomEnd = 4.dp,
                            ),
                        ).background(brush = gradientBrush)
                        .testTag("chat_bubble_user")
                        .combinedClickable(
                            role = Role.Button,
                            onClick = {},
                            onLongClick = {
                                showCopyButton = true
                            },
                        ),
                color = Color.Transparent,
                tonalElevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    SelectionContainer {
                        Text(
                            text = highlightedText,
                            color = userBubbleTextColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (!message.isStreaming) {
                        Text(
                            text = formatTimestamp(message.timestamp, DateFormat.is24HourFormat(LocalContext.current)),
                            color = userBubbleTextColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier =
                                Modifier
                                    .align(Alignment.End)
                                    .padding(top = 4.dp),
                        )
                    }
                }
            }

            // Copy button overlay — top-right of the bubble
            AnimatedVisibility(
                visible = showCopyButton,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-8).dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shadowElevation = 6.dp,
                ) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            showCopyButton = false
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    message: ChatMessage,
    maxWidth: androidx.compose.ui.unit.Dp,
    isDarkTheme: Boolean,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bubbleColor = if (isDarkTheme) AssistantBubble else AssistantBubbleLight
    val textColor =
        if (bubbleColor.luminance() > 0.5f) {
            if (MaterialTheme.colorScheme.onSurface.luminance() < 0.5f) {
                MaterialTheme.colorScheme.onSurface
            } else {
                Color(0xFF1A1A24)
            }
        } else {
            if (MaterialTheme.colorScheme.onSurface.luminance() > 0.5f) {
                MaterialTheme.colorScheme.onSurface
            } else {
                Color(0xFFE8E6EE)
            }
        }
    val clipboardManager = LocalClipboardManager.current
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
                .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box {
            Surface(
                modifier =
                    Modifier
                        .widthIn(max = maxWidth)
                        .animateContentSize()
                        .clip(
                            RoundedCornerShape(
                                topStart = 4.dp,
                                topEnd = 16.dp,
                                bottomStart = 16.dp,
                                bottomEnd = 16.dp,
                            ),
                        ).testTag("chat_bubble_assistant")
                        .combinedClickable(
                            role = Role.Button,
                            onClick = {},
                            onLongClick = {
                                showCopyButton = true
                            },
                        ),
                color = bubbleColor,
                border =
                    BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                    ),
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    SelectionContainer {
                        RichText(
                            text = message.content,
                            textColor = textColor,
                            searchQuery = searchQuery,
                            isCurrentMatch = isCurrentMatch,
                        )
                    }
                    if (!message.isStreaming) {
                        Text(
                            text = formatTimestamp(message.timestamp, DateFormat.is24HourFormat(LocalContext.current)),
                            color = textColor.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier =
                                Modifier
                                    .align(Alignment.End)
                                    .padding(top = 4.dp),
                        )
                    }
                }
            }

            // Copy button overlay — top-right of the bubble
            AnimatedVisibility(
                visible = showCopyButton,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-8).dp, y = (-8).dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    shadowElevation = 6.dp,
                ) {
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            showCopyButton = false
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
    }
}

@Composable
private fun SystemBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message.content,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontStyle = FontStyle.Italic,
                    color = SystemMessageColor,
                ),
        )
    }
}

/**
 * Cleanly parsed representation of a tool call, extracted from the
 * tool.complete JSON payload (which contains both args and result).
 */
data class ParsedToolData(
    val toolName: String = "",
    val args: Map<String, Any?> = emptyMap(),
    val result: Map<String, Any?> = emptyMap(),
    val isTerminal: Boolean = false,
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val error: String? = null,
    val summaryText: String? = null,
    val durationSec: Double? = null,
    val mainOutput: String? = null,
    val extraFields: Map<String, String> = emptyMap(),
    val isRunning: Boolean = false,
)

/**
 * Parses the tool.complete JSON payload into a [ParsedToolData].
 * The gateway sends a payload with `args` and `result` sub-objects
 * (see tui_gateway/server.py `_on_tool_complete`). We extract both
 * and use the tool name to build a one-line summary from the relevant arg.
 */
fun parseToolOutput(
    content: String,
    toolName: String?,
    isRunning: Boolean,
): ParsedToolData? {
    val trimmed = content.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
    return try {
        val element = com.google.gson.JsonParser.parseString(trimmed)
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject

        // Resolve tool name — from parameter first, then from payload (old sessions)
        val resolvedToolName =
            toolName
                ?: obj.get("name")?.takeIf { !it.isJsonNull }?.asString

        val config = ToolSchemaRegistry.getDisplayConfig(resolvedToolName)

        // Extract args sub-object if present (new tool.complete format)
        val argsObj = obj.get("args")?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject
        val args: Map<String, Any?> =
            argsObj?.entrySet()?.associate { entry ->
                entry.key to (if (entry.value.isJsonPrimitive) entry.value.asString else entry.value.toString())
            } ?: emptyMap()

        // Extract result sub-object if present, fall back to top-level (old format)
        val resultObj = obj.get("result")?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject
        val dataSource = resultObj ?: obj

        // Build summary line from args (new format) or context field (old tool.start)
        val summaryText =
            if (config.summaryArgKey != null) {
                val raw = args[config.summaryArgKey]?.toString() ?: ""
                val truncated = if (raw.length > 100) raw.take(100) + "…" else raw
                if (truncated.isNotBlank()) "${config.summaryPrefix}$truncated" else null
            } else {
                null ?: obj.get("context")?.takeIf { !it.isJsonNull }?.asString
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { "${config.summaryPrefix}$it" }
            }

        // Check if this looks like a terminal result (check dataSource for backward compat)
        val hasStdout = dataSource.has("stdout")
        val hasStderr = dataSource.has("stderr")
        val hasExitCode = dataSource.has("exit_code") || dataSource.has("exitCode")

        if (hasStdout || hasStderr || hasExitCode) {
            val stdout = dataSource.get("stdout")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
            val stderr = dataSource.get("stderr")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
            val exitCode = (dataSource.get("exit_code") ?: dataSource.get("exitCode"))?.takeIf { !it.isJsonNull }?.asInt
            val error = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble

            ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                isTerminal = true,
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
                error = error,
                summaryText = summaryText,
                durationSec = duration,
                isRunning = isRunning,
            )
        } else {
            // Generic tool display
            val mainOutput =
                dataSource
                    .let { r ->
                        (r.get("output") ?: r.get("result") ?: r.get("content") ?: r.get("text"))
                            ?.takeIf { !it.isJsonNull }
                            ?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
                            ?.takeIf { it.isNotEmpty() }
                    }

            val extraFields = mutableMapOf<String, String>()
            dataSource.entrySet().forEach { (key, value) ->
                if (key != "output" && key != "result" && key != "content" && key != "text" &&
                    key != "name" && key != "tool_id" && key != "context" && !value.isJsonNull
                ) {
                    val valStr = if (value.isJsonPrimitive) value.asString else value.toString()
                    if (valStr.isNotEmpty() && valStr != "null") {
                        extraFields[key] = valStr
                    }
                }
            }

            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble

            ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                extraFields = extraFields,
                durationSec = duration,
                isRunning = isRunning,
            )
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun ExpandedToolContent(
    parsed: ParsedToolData,
    contentColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Summary line at top of expanded view
        if (parsed.summaryText != null) {
            Text(
                text = parsed.summaryText,
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        color = contentColor.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
            )
            Spacer(modifier = Modifier.height(2.dp))
        }

        if (parsed.isTerminal) {
            // ── Terminal output ──
            parsed.stdout?.let {
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
            parsed.stderr?.let {
                Text(
                    text = stringResource(R.string.chat_tool_stderr, it),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = StatusRed.copy(alpha = 0.9f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                )
            }
            parsed.error?.let {
                Text(
                    text = stringResource(R.string.chat_tool_execution_error, it),
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = StatusRed,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                )
            }
            parsed.exitCode?.let { code ->
                if (code != 0) {
                    Text(
                        text = stringResource(R.string.chat_tool_exit_code, code),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = StatusRed,
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                }
            }

            // Duration footer for terminal
            parsed.durationSec?.let { dur ->
                Text(
                    text = "Duration: ${"%.1f".format(dur)}s",
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            color = contentColor.copy(alpha = 0.5f),
                        ),
                )
            }
        } else {
            // ── Generic tool output ──
            parsed.mainOutput?.let {
                Text(
                    text = it,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = contentColor.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                        ),
                )
            }
            parsed.extraFields.forEach { (key, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "$key:",
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = contentColor.copy(alpha = 0.6f),
                                fontWeight = FontWeight.Bold,
                            ),
                    )
                    Text(
                        text = value,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = contentColor.copy(alpha = 0.9f),
                                fontSize = 11.sp,
                            ),
                    )
                }
            }

            // Duration footer
            parsed.durationSec?.let { dur ->
                Text(
                    text = "Duration: ${"%.1f".format(dur)}s",
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            color = contentColor.copy(alpha = 0.5f),
                        ),
                )
            }
        }
    }
}

@Composable
private fun ToolBubble(
    message: ChatMessage,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var showRawJson by remember { mutableStateOf(false) }
    val chipColor = if (isDarkTheme) ToolChipColor else ToolChipColorLight
    val contentColor = if (isDarkTheme) Color.White else Color.Black

    val parsed =
        remember(message.content, message.toolName, message.toolStatus) {
            parseToolOutput(message.content, message.toolName, message.toolStatus == ToolStatus.RUNNING)
        }
    val config = ToolSchemaRegistry.getDisplayConfig(message.toolName)

    val clipboardManager = LocalClipboardManager.current
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
                HeaderRow(message, config, contentColor)

                // ── Summary line (always visible when collapsed) ──
                if (!expanded && parsed?.summaryText != null) {
                    Text(
                        text = parsed.summaryText,
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
                                clipboardManager = clipboardManager,
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
                    } else if (parsed != null) {
                        // Clean structured expanded view
                        Box {
                            SelectionContainer {
                                ExpandedToolContent(parsed, contentColor)
                            }
                            CopyButton(
                                visible = showCopyButton,
                                textToCopy = message.content,
                                clipboardManager = clipboardManager,
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
                                clipboardManager = clipboardManager,
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

@Composable
private fun HeaderRow(
    message: ChatMessage,
    config: ToolDisplayConfig,
    contentColor: Color,
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
                    ToolStatus.COMPLETED -> StatusGreen
                    ToolStatus.FAILED -> StatusRed
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

@Composable
private fun CopyButton(
    visible: Boolean,
    textToCopy: String,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    clipboardManager.setText(AnnotatedString(textToCopy))
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

/**
 * Simple rich text renderer supporting **bold**, `inline code`, ```code blocks```, and clickable URLs.
 */
@Composable
private fun RichText(
    text: String,
    textColor: Color,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
) {
    val uriHandler = LocalUriHandler.current
    val urlPattern = remember { Regex("""https?://[^\s)>\]\u0022\u0027]+""") }
    val linkColor = MaterialTheme.colorScheme.primary
    val searchHighlightColor = if (isCurrentMatch) Color(0xFFF57C00) else Color(0xFFFFF176).copy(alpha = 0.9f)
    val annotated =
        remember(text, searchQuery, isCurrentMatch) {
            buildAnnotatedString {
                var i = 0
                val src = text
                while (i < src.length) {
                    when {
                        // Code block: ```...```
                        src.startsWith("```", i) -> {
                            val end = src.indexOf("```", i + 3)
                            if (end != -1) {
                                val code = src.substring(i + 3, end).trimStart('\n').trimEnd('\n')
                                withStyle(
                                    SpanStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        background = textColor.copy(alpha = 0.08f),
                                    ),
                                ) {
                                    append(code)
                                }
                                i = end + 3
                            } else {
                                append(src[i])
                                i++
                            }
                        }

                        // Inline code: `...`
                        src[i] == '`' -> {
                            val end = src.indexOf('`', i + 1)
                            if (end != -1) {
                                withStyle(
                                    SpanStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        background = textColor.copy(alpha = 0.08f),
                                    ),
                                ) {
                                    append(src.substring(i + 1, end))
                                }
                                i = end + 1
                            } else {
                                append(src[i])
                                i++
                            }
                        }

                        // Bold: **...**
                        src.startsWith("**", i) -> {
                            val end = src.indexOf("**", i + 2)
                            if (end != -1) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append(src.substring(i + 2, end))
                                }
                                i = end + 2
                            } else {
                                append(src[i])
                                i++
                            }
                        }

                        // URL: https://...
                        urlPattern.matchAt(src, i) != null -> {
                            val match = urlPattern.matchAt(src, i)!!
                            pushStringAnnotation(tag = "URL", annotation = match.value)
                            withStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ) {
                                append(match.value)
                            }
                            pop()
                            i = match.range.last + 1
                        }

                        // Search match highlight
                        searchQuery.isNotEmpty() &&
                            src.regionMatches(i, searchQuery, 0, searchQuery.length, ignoreCase = true) -> {
                            withStyle(SpanStyle(background = searchHighlightColor, color = Color(0xFF1A1A24))) {
                                append(src.substring(i, i + searchQuery.length))
                            }
                            i += searchQuery.length
                        }

                        else -> {
                            append(src[i])
                            i++
                        }
                    }
                }
            }
        }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        onClick = { offset: Int ->
            annotated
                .getStringAnnotations("URL", offset, offset)
                .firstOrNull()
                ?.let { uriHandler.openUri(it.item) }
        },
    )
}

private fun formatTimestamp(
    timestamp: Long,
    is24Hour: Boolean,
): String {
    val pattern = if (is24Hour) "HH:mm" else "h:mm a"
    return DateTimeFormatter
        .ofPattern(pattern)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))
}

/**
 * Build an AnnotatedString with search matches highlighted.
 */
private fun buildHighlightedString(
    text: String,
    query: String,
    isCurrentMatch: Boolean = false,
): AnnotatedString {
    val highlightColor = if (isCurrentMatch) Color(0xFFF57C00) else Color(0xFFFFF176).copy(alpha = 0.9f)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val matchEnd = text.indexOf(query, i, ignoreCase = true)
            if (matchEnd == -1) {
                // No more matches — append the rest
                append(text.substring(i))
                i = text.length
            } else {
                // Append text before the match
                if (matchEnd > i) {
                    append(text.substring(i, matchEnd))
                }
                // Append the match highlighted
                withStyle(SpanStyle(background = highlightColor, color = Color(0xFF1A1A24))) {
                    append(text.substring(matchEnd, matchEnd + query.length))
                }
                i = matchEnd + query.length
            }
        }
    }
}
