package com.m57.hermescontrol.ui.chat

import android.content.ClipData
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
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
import coil.compose.AsyncImage
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.theme.AssistantBubble
import com.m57.hermescontrol.theme.AssistantBubbleLight
import com.m57.hermescontrol.theme.StatusGreen
import com.m57.hermescontrol.theme.StatusRed
import com.m57.hermescontrol.theme.SystemMessageColor
import com.m57.hermescontrol.theme.ToolChipColor
import com.m57.hermescontrol.theme.ToolChipColorLight
import com.m57.hermescontrol.theme.UserBubble
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChatBubble(
    message: ChatMessage,
    isDarkTheme: Boolean,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    onRespondApproval: (String) -> Unit = {},
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
                SystemBubble(
                    message = message,
                    onRespondApproval = onRespondApproval,
                    modifier = modifier,
                )
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
                    // Render inline attachments
                    if (!message.attachments.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        message.attachments.forEach { attachment ->
                            InlineAttachment(
                                attachment = attachment,
                                textColor = userBubbleTextColor,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
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
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, message.content)))
                            }
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
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, message.content)))
                            }
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
    onRespondApproval: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message.content,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontStyle = FontStyle.Italic,
                    color = SystemMessageColor,
                ),
        )

        // Approval action buttons
        if (message.approvalInfo != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { onRespondApproval("approve") },
                    modifier =
                        Modifier
                            .height(36.dp)
                            .testTag("approve_button"),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Approve")
                }

                FilledTonalButton(
                    onClick = { onRespondApproval("deny") },
                    modifier =
                        Modifier
                            .height(36.dp)
                            .testTag("deny_button"),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Deny")
                }
            }
        }
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

        // ── Todo-specific formatting ──
        // Extract the structured todos array and summary object from the result,
        // build a clean one-line summary and formatted item list.
        if (resolvedToolName == "todo") {
            val todosArray =
                dataSource.get("todos")
                    ?.takeIf { !it.isJsonNull && it.isJsonArray }
                    ?.asJsonArray
            val summaryObj =
                dataSource.get("summary")
                    ?.takeIf { !it.isJsonNull && it.isJsonObject }
                    ?.asJsonObject

            val todoSummaryText =
                summaryObj?.let { s ->
                    val total = s.get("total")?.asDouble?.toInt() ?: 0
                    val pending = s.get("pending")?.asDouble?.toInt() ?: 0
                    val inProgress = s.get("in_progress")?.asDouble?.toInt() ?: 0
                    val completed = s.get("completed")?.asDouble?.toInt() ?: 0
                    val cancelled = s.get("cancelled")?.asDouble?.toInt() ?: 0
                    val parts = mutableListOf<String>()
                    if (pending > 0) parts.add("$pending pending")
                    if (inProgress > 0) parts.add("$inProgress in_progress")
                    if (completed > 0) parts.add("$completed completed")
                    if (cancelled > 0) parts.add("$cancelled cancelled")
                    val itemWord = if (total == 1) "item" else "items"
                    if (parts.isEmpty()) {
                        "📋 0 items"
                    } else {
                        "📋 $total $itemWord (${parts.joinToString(", ")})"
                    }
                }

            val formattedTodos =
                todosArray?.mapNotNull { element ->
                    if (!element.isJsonObject) return@mapNotNull null
                    val item = element.asJsonObject
                    val id = item.get("id")?.asString ?: ""
                    val content = item.get("content")?.asString ?: ""
                    val status = item.get("status")?.asString ?: "pending"
                    val marker =
                        when (status) {
                            "completed" -> "[x]"
                            "in_progress" -> "[>]"
                            "cancelled" -> "[~]"
                            else -> "[ ]"
                        }
                    "$marker $id. $content"
                }?.joinToString("\n")?.takeIf { it.isNotEmpty() }

            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble

            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = todoSummaryText,
                mainOutput = formattedTodos,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Fact Store-specific formatting ──
        // Extract structured fact results (probe/search/reason/related/contradict → results[],
        // list → facts[], add/update/remove → simple status). Build a clean summary and
        // formatted fact list with category, trust score, and tags.
        if (resolvedToolName == "fact_store") {
            val action = argsObj?.get("action")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val entity = argsObj?.get("entity")?.takeIf { !it.isJsonNull }?.asString
            val query = argsObj?.get("query")?.takeIf { !it.isJsonNull }?.asString

            // Try both key shapes: results[] (probe/search/reason/related/contradict) and facts[] (list)
            val factsArray =
                dataSource.get("results")
                    ?.takeIf { !it.isJsonNull && it.isJsonArray }
                    ?.asJsonArray
                    ?: dataSource.get("facts")
                        ?.takeIf { !it.isJsonNull && it.isJsonArray }
                        ?.asJsonArray

            val count = dataSource.get("count")?.takeIf { !it.isJsonNull }?.asDouble?.toInt() ?: 0
            val status = dataSource.get("status")?.takeIf { !it.isJsonNull }?.asString
            val factId = dataSource.get("fact_id")?.takeIf { !it.isJsonNull }?.asDouble?.toInt()
            val removed = dataSource.get("removed")?.takeIf { !it.isJsonNull }?.asBoolean
            val updated = dataSource.get("updated")?.takeIf { !it.isJsonNull }?.asBoolean

            // Build summary line
            val actionContext =
                when {
                    entity != null -> " ($action: $entity)"
                    query != null -> " ($action: $query)"
                    else -> " ($action)"
                }
            val factSummaryText =
                when {
                    status == "added" && factId != null -> "🧠 Fact added (ID: $factId)"
                    status == "added" -> "🧠 Fact added"
                    removed == true -> "🧠 Fact removed"
                    updated == true -> "🧠 Fact updated"
                    factsArray != null -> "🧠 $count facts$actionContext"
                    else -> "🧠 $action"
                }

            // Format each fact as a compact block: #ID + content on first line, meta on indented second line
            val formattedFacts =
                factsArray?.mapNotNull { element ->
                    if (!element.isJsonObject) return@mapNotNull null
                    val item = element.asJsonObject
                    val fid = item.get("fact_id")?.asDouble?.toInt() ?: 0
                    val content = item.get("content")?.asString ?: ""
                    val category = item.get("category")?.asString
                    val trust = item.get("trust_score")?.asDouble
                    val tags = item.get("tags")?.asString?.takeIf { it.isNotEmpty() }

                    val firstLine = "#$fid  $content"
                    val metaParts = mutableListOf<String>()
                    if (category != null) metaParts.add("[$category]")
                    if (trust != null) metaParts.add("trust: ${"%.2f".format(trust)}")
                    if (tags != null) metaParts.add("🏷️ $tags")
                    if (metaParts.isNotEmpty()) {
                        "$firstLine\n      ${metaParts.joinToString("  ")}"
                    } else {
                        firstLine
                    }
                }?.joinToString("\n")?.takeIf { it.isNotEmpty() }

            val factMainOutput =
                when {
                    status == "added" && factId != null -> "✅ Fact #$factId stored"
                    status == "added" -> "✅ Fact stored"
                    removed == true -> "✅ Removed"
                    updated == true -> "✅ Updated"
                    formattedFacts != null -> formattedFacts
                    else -> null
                }

            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble

            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = factSummaryText,
                mainOutput = factMainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Session Search-specific formatting ──
        // Handles all 4 calling modes: discover (results[] with snippets/bookends),
        // scroll (messages[] with anchor), read (messages[] with session dump),
        // and browse (results[] with recent session metadata).
        if (resolvedToolName == "session_search") {
            val mode = dataSource.get("mode")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val query = dataSource.get("query")?.takeIf { !it.isJsonNull }?.asString
            val resultsArray =
                dataSource.get("results")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray
            val messagesArray =
                dataSource.get("messages")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray
            val count = dataSource.get("count")?.takeIf { !it.isJsonNull }?.asDouble?.toInt()
            val msgCount = dataSource.get("message_count")?.takeIf { !it.isJsonNull }?.asDouble?.toInt()
            val truncated = dataSource.get("truncated")?.takeIf { !it.isJsonNull }?.asBoolean

            // Build summary line
            val ssSummaryText =
                when (mode) {
                    "discover" -> "🔍 ${count ?: 0} session${if (count == 1) "" else "s"}${query?.let {
                        ": ${it.take(
                            60,
                        )}"
                    } ?: ""}"
                    "scroll" -> "📜 ${messagesArray?.size() ?: 0} messages (scroll)"
                    "read" -> "📖 ${msgCount ?: messagesArray?.size() ?: 0} messages${
                        if (truncated == true) " (truncated)" else ""
                    }"
                    "browse" -> "📋 ${count ?: 0} recent sessions"
                    else -> "🔍 session_search"
                }

            // Format results (discover / browse)
            val formattedResults =
                resultsArray?.mapIndexedNotNull { idx, element ->
                    if (!element.isJsonObject) return@mapIndexedNotNull null
                    val item = element.asJsonObject
                    val whenField =
                        item.get("when")?.takeIf { !it.isJsonNull }?.asString
                            ?: item.get("started_at")?.takeIf { !it.isJsonNull }?.asString
                    val source = item.get("source")?.takeIf { !it.isJsonNull }?.asString
                    val title = item.get("title")?.takeIf { !it.isJsonNull }?.asString
                    val snippet = item.get("snippet")?.takeIf { !it.isJsonNull }?.asString
                    val preview = item.get("preview")?.takeIf { !it.isJsonNull }?.asString
                    val model = item.get("model")?.takeIf { !it.isJsonNull }?.asString
                    val sessionMsgCount = item.get("message_count")?.takeIf { !it.isJsonNull }?.asDouble?.toInt()
                    val matchedRole = item.get("matched_role")?.takeIf { !it.isJsonNull }?.asString

                    val header = whenField?.let { "📅 $it" } ?: ""
                    val sourceTag = source?.let { "[$it]" } ?: ""
                    val titleLine = title?.let { "\n     📄 $it" } ?: ""
                    val modelLine = model?.let { "\n     🤖 $it" } ?: ""
                    val matchedLine = matchedRole?.let { "\n     🎯 matched: $it" } ?: ""
                    val countLine = sessionMsgCount?.let { "\n     $it msgs" } ?: ""
                    val snippetText = snippet ?: preview
                    val snippetLine =
                        snippetText?.let {
                            val clean = it.take(200).replace("\n", " ")
                            "\n     ┃ $clean"
                        } ?: ""

                    "━━━ #${idx + 1}  $header$sourceTag$titleLine$modelLine$matchedLine$countLine$snippetLine"
                }?.joinToString("\n")?.takeIf { it.isNotEmpty() }

            // Format messages (scroll / read)
            val anchorId = dataSource.get("around_message_id")?.takeIf { !it.isJsonNull }?.asDouble?.toInt()
            val formattedMessages =
                messagesArray?.mapNotNull { element ->
                    if (!element.isJsonObject) return@mapNotNull null
                    val item = element.asJsonObject
                    val msgId = item.get("id")?.takeIf { !it.isJsonNull }?.asDouble?.toInt()
                    val role = item.get("role")?.takeIf { !it.isJsonNull }?.asString ?: "?"
                    val content = item.get("content")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val toolName = item.get("tool_name")?.takeIf { !it.isJsonNull }?.asString

                    val roleEmoji =
                        when (role) {
                            "user" -> "👤"
                            "assistant" -> "🤖"
                            "tool" -> "🔧"
                            else -> "❓"
                        }
                    val namePart = if (toolName != null) " ($toolName)" else ""
                    val anchor = if (anchorId != null && msgId == anchorId) "  ⬅️" else ""
                    val cleanContent = content.take(300).replace("\n", " ")

                    "[$msgId] $roleEmoji $role$namePart$anchor\n     $cleanContent"
                }?.joinToString("\n")?.takeIf { it.isNotEmpty() }

            val ssMainOutput = formattedResults ?: formattedMessages

            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble

            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = ssSummaryText,
                mainOutput = ssMainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Memory-specific formatting ──
        if (resolvedToolName == "memory") {
            val action = argsObj?.get("action")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val success = dataSource.get("success")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
            val target = argsObj?.get("target")?.takeIf { !it.isJsonNull }?.asString
            val errorMsg = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString
            val usage = dataSource.get("usage")?.takeIf { !it.isJsonNull }?.asString

            val summaryText = "💾 $action${target?.let { " ($it)" } ?: ""}"
            val mainOutput =
                when {
                    errorMsg != null -> "❌ $errorMsg"
                    !success -> "❌ Failed"
                    usage != null -> "✅ $action in ${target ?: "memory"}\n     $usage"
                    else -> "✅ ${target?.capitalize() ?: "Memory"} $action done"
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Cronjob-specific formatting ──
        if (resolvedToolName == "cronjob") {
            val action = argsObj?.get("action")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val jobsArray = dataSource.get("jobs")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray
            val jobId = dataSource.get("job_id")?.takeIf { !it.isJsonNull }?.asDouble?.toInt()
            val jobName = dataSource.get("name")?.takeIf { !it.isJsonNull }?.asString
            val errorMsg = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString

            val summaryText = "🔄 $action${jobName?.let { ": $it" } ?: jobId?.let { " (ID: $it)" } ?: ""}"
            val mainOutput =
                when {
                    errorMsg != null -> "❌ $errorMsg"
                    jobsArray != null -> {
                        jobsArray.mapNotNull { el ->
                            if (!el.isJsonObject) return@mapNotNull null
                            val j = el.asJsonObject
                            val name = j.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "?"
                            val schedule = j.get("schedule")?.takeIf { !it.isJsonNull }?.asString
                            val status = j.get("status")?.takeIf { !it.isJsonNull }?.asString
                            val lastRun = j.get("last_run")?.takeIf { !it.isJsonNull }?.asString
                            val parts = mutableListOf("📌 $name")
                            if (schedule != null) parts.add("     ⏱ $schedule")
                            if (status != null) parts.add("     📊 $status")
                            if (lastRun != null) parts.add("     ⏮ $lastRun")
                            parts.joinToString("\n")
                        }.joinToString("\n\n")
                    }
                    jobId != null -> "✅ $action (ID: $jobId)"
                    else -> "✅ $action done"
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Web Search-specific formatting ──
        if (resolvedToolName == "web_search") {
            val query = argsObj?.get("query")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val success = dataSource.get("success")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
            val errorMsg = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString
            val webArr =
                dataSource.get("data")?.takeIf { !it.isJsonNull && it.isJsonObject }
                    ?.asJsonObject?.get("web")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray

            val summaryText = "🌐 $query${webArr?.let { " (${it.size()} results)" } ?: ""}"
            val mainOutput =
                when {
                    errorMsg != null -> "❌ $errorMsg"
                    webArr != null ->
                        webArr.mapNotNull { el ->
                            if (!el.isJsonObject) return@mapNotNull null
                            val item = el.asJsonObject
                            val title = item.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            val url = item.get("url")?.takeIf { !it.isJsonNull }?.asString
                            val desc = item.get("description")?.takeIf { !it.isJsonNull }?.asString
                            val pos = item.get("position")?.takeIf { !it.isJsonNull }?.asDouble?.toInt()
                            val lines = mutableListOf("${pos?.let { "$it. " } ?: ""}$title")
                            if (desc != null) lines.add("     $desc")
                            if (url != null) lines.add("     🔗 $url")
                            lines.joinToString("\n")
                        }.joinToString("\n\n")
                    !success -> "❌ Search failed"
                    else -> "No results"
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Skills List-specific formatting ──
        if (resolvedToolName == "skills_list") {
            val category = argsObj?.get("category")?.takeIf { !it.isJsonNull }?.asString
            val skillsArr =
                (dataSource.get("skills") ?: dataSource.get("results"))
                    ?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray

            val summaryText = "📚 ${skillsArr?.size() ?: 0} skills${category?.let { " ($it)" } ?: ""}"
            val mainOutput =
                skillsArr?.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val s = el.asJsonObject
                    val name = s.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val desc = s.get("description")?.takeIf { !it.isJsonNull }?.asString
                    val cat = s.get("category")?.takeIf { !it.isJsonNull }?.asString
                    val lines = mutableListOf("📌 $name")
                    if (desc != null) lines.add("     $desc")
                    if (cat != null) lines.add("     [$cat]")
                    lines.joinToString("\n")
                }?.joinToString("\n\n") ?: "📚 No skills found"
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Skill View-specific formatting ──
        if (resolvedToolName == "skill_view") {
            val name = argsObj?.get("name")?.takeIf { !it.isJsonNull }?.asString
            val content = dataSource.get("content")?.takeIf { !it.isJsonNull }?.asString
            val linkedFiles = dataSource.get("linked_files")?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject

            val summaryText = "📖 $name"
            val contentPreview =
                content?.let { c ->
                    if (c.length > 500) c.take(500) + "\n... [${c.length - 500} more chars]" else c
                }
            val filesInfo =
                linkedFiles?.entrySet()?.joinToString("\n") { (k, v) ->
                    val paths =
                        when {
                            v.isJsonArray -> v.asJsonArray.joinToString(", ") { it.asString }
                            v.isJsonPrimitive -> v.asString
                            else -> v.toString()
                        }
                    "     📎 $k: $paths"
                }
            val mainOutput =
                buildString {
                    if (contentPreview != null) append(contentPreview)
                    if (filesInfo != null) append("\n\n━━━ Linked Files ━━━\n$filesInfo")
                    if (content == null && linkedFiles == null) append("📖 No content available")
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Process-specific formatting ──
        if (resolvedToolName == "process") {
            val action = argsObj?.get("action")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val procId = argsObj?.get("session_id")?.takeIf { !it.isJsonNull }?.asString
            val errorMsg = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString
            val status = dataSource.get("status")?.takeIf { !it.isJsonNull }?.asString
            val outputText = dataSource.get("output")?.takeIf { !it.isJsonNull }?.asString
            val processesArr = dataSource.get("processes")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray

            val summaryText = "⚙️ $action${procId?.let { ": $it" } ?: ""}"
            val mainOutput =
                when {
                    errorMsg != null -> "❌ $errorMsg"
                    processesArr != null -> {
                        processesArr.mapNotNull { el ->
                            if (!el.isJsonObject) return@mapNotNull null
                            val p = el.asJsonObject
                            val pid =
                                p.get(
                                    "session_id",
                                )?.takeIf { !it.isJsonNull }?.asString ?: p.get("id")?.asString ?: "?"
                            val pStatus = p.get("status")?.takeIf { !it.isJsonNull }?.asString
                            val cmd = p.get("command")?.takeIf { !it.isJsonNull }?.asString
                            val running = p.get("running")?.takeIf { !it.isJsonNull }?.asBoolean
                            val parts = mutableListOf("📌 $pid${cmd?.let { ": $it" } ?: ""}")
                            if (pStatus != null) parts.add("     Status: $pStatus")
                            if (running != null) parts.add("     Running: $running")
                            parts.joinToString("\n")
                        }.joinToString("\n\n")
                    }
                    outputText != null -> "${status?.let { "Status: $it\n" } ?: ""}$outputText"
                    status != null -> "✅ $status"
                    else -> "✅ $action done"
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── X Search-specific formatting ──
        if (resolvedToolName == "x_search") {
            val query = argsObj?.get("query")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val answer = dataSource.get("answer")?.takeIf { !it.isJsonNull }?.asString
            val citationsArr = dataSource.get("citations")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray
            val degraded = dataSource.get("degraded")?.takeIf { !it.isJsonNull }?.asBoolean
            val errorMsg = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString

            val summaryText = "𝕏 $query${if (degraded == true) " (no citations)" else ""}"
            val mainOutput =
                when {
                    errorMsg != null -> "❌ $errorMsg"
                    answer != null -> {
                        val lines = mutableListOf(answer)
                        if (citationsArr != null && citationsArr.size() > 0) {
                            lines.add("\n━━━ Citations ━━━")
                            citationsArr.forEachIndexed { idx, cit ->
                                if (cit.isJsonObject) {
                                    val url = cit.asJsonObject.get("url")?.asString
                                    val title = cit.asJsonObject.get("title")?.asString
                                    lines.add("${idx + 1}. ${title ?: url ?: "source"}")
                                    if (title != null && url != null) lines.add("   $url")
                                }
                            }
                        }
                        if (degraded == true) lines.add("\n⚠️ No citations — answer based on model's knowledge")
                        lines.joinToString("\n")
                    }
                    else -> "𝕏 No results"
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Vision Analyze-specific formatting ──
        if (resolvedToolName == "vision_analyze") {
            val imageUrl = argsObj?.get("image_url")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val description = dataSource.get("description")?.takeIf { !it.isJsonNull }?.asString
            val content = dataSource.get("content")?.takeIf { !it.isJsonNull }?.asString
            val errorMsg = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString

            val summaryText = "👁️ ${imageUrl.take(60)}"
            val mainOutput =
                when {
                    errorMsg != null -> "❌ $errorMsg"
                    description != null -> description
                    content != null -> content
                    else -> "👁️ No description available"
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Web Extract-specific formatting ──
        if (resolvedToolName == "web_extract") {
            val urls = argsObj?.get("urls")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray
            val content = dataSource.get("content")?.takeIf { !it.isJsonNull }?.asString
            val success = dataSource.get("success")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
            val errorMsg = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString

            val urlSummary = urls?.joinToString(", ") { it.asString?.take(40) ?: "?" } ?: ""
            val summaryText = "🕸️ ${urls?.size() ?: 0} page(s)"
            val mainOutput =
                when {
                    errorMsg != null -> "❌ $errorMsg"
                    content != null ->
                        content.take(
                            3000,
                        ) + if (content.length > 3000) "\n... [${content.length - 3000} more chars]" else ""
                    else -> "🕸️ No content extracted"
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Tool Search-specific formatting ──
        if (resolvedToolName == "tool_search") {
            val query = argsObj?.get("query")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val limit = argsObj?.get("limit")?.takeIf { !it.isJsonNull }?.asDouble?.toInt()
            val matchesArr = dataSource.get("matches")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray

            val summaryText = "🔍 $query${matchesArr?.let { " (${it.size()} matches)" } ?: ""}"
            val mainOutput =
                matchesArr?.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val m = el.asJsonObject
                    val name = m.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "?"
                    val desc = m.get("description")?.takeIf { !it.isJsonNull }?.asString
                    val lines = mutableListOf("🔧 $name")
                    if (desc != null) lines.add("     $desc")
                    lines.joinToString("\n")
                }?.joinToString("\n\n") ?: "🔍 No matching tools"
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Image Generate-specific formatting ──
        if (resolvedToolName == "image_generate") {
            val success = dataSource.get("success")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
            val errorMsg = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString
            val imageUrl = dataSource.get("image")?.takeIf { !it.isJsonNull }?.asString
            val argsImageUrl = argsObj?.get("image_url")?.takeIf { !it.isJsonNull }?.asString
            val modality = dataSource.get("modality")?.takeIf { !it.isJsonNull }?.asString
            val promptUrl = argsObj?.get("prompt")?.takeIf { !it.isJsonNull }?.asString

            val summaryText =
                buildString {
                    append("🎨 ")
                    if (errorMsg != null) {
                        append("❌ $errorMsg")
                    } else {
                        append((imageUrl ?: argsImageUrl ?: "").take(60))
                        if (modality != null) append(" ($modality)")
                    }
                }
            val mainOutput =
                buildString {
                    if (errorMsg != null) {
                        append("❌ $errorMsg")
                        if (imageUrl != null) append("\n🔗 $imageUrl")
                    } else if (imageUrl != null) {
                        append("🖼️ ")
                        if (promptUrl != null) append("$promptUrl\n")
                        append("\n🔗 $imageUrl")
                    } else {
                        append("✅ Generated")
                    }
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Project List-specific formatting ──
        if (resolvedToolName == "project_list") {
            val activeId = dataSource.get("active_id")?.takeIf { !it.isJsonNull }?.asString
            val projectsArr = dataSource.get("projects")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray

            val summaryText = "📂 ${projectsArr?.size() ?: "?"} projects${activeId?.let { " (1 active)" } ?: ""}"
            val mainOutput =
                if (projectsArr != null) {
                    projectsArr.mapNotNull { el ->
                        if (!el.isJsonObject) return@mapNotNull null
                        val p = el.asJsonObject
                        val id = p.get("id")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val name = p.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val slug = p.get("slug")?.takeIf { !it.isJsonNull }?.asString
                        val path = p.get("primary_path")?.takeIf { !it.isJsonNull }?.asString
                        val isActive = p.get("active")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                        val lines = mutableListOf("${if (isActive) "⭐ " else "📁 "}$name")
                        if (slug != null) lines.add("     🏷️ $slug")
                        if (path != null) lines.add("     📍 $path")
                        if (isActive) lines.add("     ✅ ACTIVE PROJECT")
                        lines.joinToString("\n")
                    }.joinToString("\n\n")
                } else {
                    ""
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Project Create/Switch-specific formatting ──
        if (resolvedToolName == "project_create" || resolvedToolName == "project_switch") {
            val success = dataSource.get("success")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
            val errorMsg = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString
            val projName = dataSource.get("name")?.takeIf { !it.isJsonNull }?.asString
            val projSlug = dataSource.get("slug")?.takeIf { !it.isJsonNull }?.asString
            val projPath = dataSource.get("primary_path")?.takeIf { !it.isJsonNull }?.asString
            val actionLabel = if (resolvedToolName == "project_create") "Created" else "Switched to"

            val summaryText = "📂 $actionLabel${projName?.let { " $it" } ?: ""}"
            val mainOutput =
                buildString {
                    if (errorMsg != null) {
                        append("❌ $errorMsg")
                    } else {
                        append("✅ $actionLabel")
                        if (projName != null) append(": $projName")
                        if (projSlug != null) append("\n   🏷️ $projSlug")
                        if (projPath != null) append("\n   📍 $projPath")
                    }
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Read Terminal-specific formatting ──
        if (resolvedToolName == "read_terminal") {
            val totalLines = dataSource.get("total_lines")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val startLine = dataSource.get("start")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val endLine = dataSource.get("end")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val cursorRow = dataSource.get("cursor_row")?.takeIf { !it.isJsonNull }?.asInt
            val text = dataSource.get("text")?.takeIf { !it.isJsonNull }?.asString
            val errorMsg = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString

            val summaryText =
                buildString {
                    append("🖥️ ")
                    if (errorMsg != null) {
                        append("❌ $errorMsg")
                    } else if (text != null) {
                        append("Lines $startLine-$endLine of $totalLines")
                        if (cursorRow != null) append(", cursor at row $cursorRow")
                    } else {
                        append("Terminal output")
                    }
                }
            val mainOutput =
                buildString {
                    if (errorMsg != null) {
                        append("❌ $errorMsg")
                    } else if (text != null) {
                        append("━━━ Terminal ━━━     ($startLine:$endLine / $totalLines)")
                        if (cursorRow != null) append(" │ cursor row $cursorRow")
                        append("\n$text")
                    } else {
                        append("🖥️ No terminal output")
                    }
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryText,
                mainOutput = mainOutput,
                durationSec = duration,
                isRunning = isRunning,
            )
        }

        // ── Computer Use-specific formatting ──
        if (resolvedToolName == "computer_use") {
            val action = dataSource.get("action")?.takeIf { !it.isJsonNull }?.asString
            val ok = dataSource.get("ok")?.takeIf { !it.isJsonNull }?.asBoolean
            val message = dataSource.get("message")?.takeIf { !it.isJsonNull }?.asString
            val errorMsg = dataSource.get("error")?.takeIf { !it.isJsonNull }?.asString
            val isMultimodal = dataSource.get("_multimodal")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            val mode = dataSource.get("mode")?.takeIf { !it.isJsonNull }?.asString
            val elementsArr = dataSource.get("elements")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray
            val appsArr = dataSource.get("apps")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray
            val summaryText = dataSource.get("text_summary")?.takeIf { !it.isJsonNull }?.asString
            val textSummary = dataSource.get("summary")?.takeIf { !it.isJsonNull }?.asString
            val visionAnalysis = dataSource.get("vision_analysis")?.takeIf { !it.isJsonNull }?.asString
            val totalElements = dataSource.get("total_elements")?.takeIf { !it.isJsonNull }?.asInt
            val width = dataSource.get("width")?.takeIf { !it.isJsonNull }?.asInt
            val height = dataSource.get("height")?.takeIf { !it.isJsonNull }?.asInt

            // Try to extract text from multimodal content array
            val multimodalText =
                if (isMultimodal) {
                    val contentArr =
                        dataSource.get("content")?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray
                    contentArr?.mapNotNull { el ->
                        if (el.isJsonObject) {
                            val eObj = el.asJsonObject
                            if (eObj.get("type")?.takeIf { !it.isJsonNull }?.asString == "text") {
                                eObj.get("text")?.takeIf { !it.isJsonNull }?.asString
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }?.joinToString("\n")
                } else {
                    null
                }

            val summaryLine =
                buildString {
                    append("🖥️ ")
                    if (errorMsg != null) {
                        append("❌ $errorMsg")
                    } else if (appsArr != null) {
                        append("${appsArr.size()} apps")
                    } else if (elementsArr != null) {
                        append("${elementsArr.size()} elements${mode?.let { " ($it)" } ?: ""}")
                        if (width != null && height != null) append(" ${width}x$height")
                    } else if (action != null) {
                        append("$action${ok?.let { if (it) " ✓" else " ✗" } ?: ""}")
                    } else {
                        append("Computer use")
                    }
                }
            val mainBody =
                buildString {
                    if (errorMsg != null) {
                        append("❌ $errorMsg")
                    } else if (multimodalText != null) {
                        append(multimodalText)
                        if (textSummary != null && multimodalText != textSummary) {
                            append("\n\n$textSummary")
                        }
                    } else if (summaryText != null) {
                        append(summaryText)
                    } else if (textSummary != null) {
                        append(textSummary)
                    } else if (appsArr != null) {
                        appsArr.forEachIndexed { idx, el ->
                            if (el.isJsonObject) {
                                val name = el.asJsonObject.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "?"
                                val pid = el.asJsonObject.get("pid")?.takeIf { !it.isJsonNull }?.asInt
                                append("${idx + 1}. $name")
                                if (pid != null) append(" (PID: $pid)")
                                append("\n")
                            }
                        }
                    } else if (elementsArr != null) {
                        append("🖥️ ${elementsArr.size()} interactable elements")
                        if (mode != null) append(" (mode: $mode)")
                        if (width != null && height != null) append(" • ${width}x$height")
                        if (textSummary != null) append("\n\n$textSummary")
                        // Show first few elements as preview
                        val previewCount = minOf(elementsArr.size(), 5)
                        if (previewCount > 0) {
                            append("\n\n")
                            for (i in 0 until previewCount) {
                                val el = elementsArr[i]
                                if (el.isJsonObject) {
                                    val idx = el.asJsonObject.get("index")?.takeIf { !it.isJsonNull }?.asInt
                                    val role = el.asJsonObject.get("role")?.takeIf { !it.isJsonNull }?.asString
                                    val label = el.asJsonObject.get("label")?.takeIf { !it.isJsonNull }?.asString
                                    append("  #$idx ${role ?: ""}${label?.let { " '$it'" } ?: ""}\n")
                                }
                            }
                            if (elementsArr.size() > previewCount) {
                                append("  ... and ${elementsArr.size() - previewCount} more")
                            }
                        }
                    } else if (action != null) {
                        append("$action:")
                        if (ok != null) append(" ${if (ok) "✅ ok" else "❌ failed"}")
                        if (message != null) append("\n$message")
                    } else {
                        append("🖥️ Computer use action")
                    }
                    if (visionAnalysis != null) {
                        append("\n\n━━━ Vision Analysis ━━━\n$visionAnalysis")
                    }
                }
            val duration = obj.get("duration_s")?.takeIf { !it.isJsonNull }?.asDouble
            return ParsedToolData(
                toolName = resolvedToolName ?: "",
                args = args,
                result = dataSource.entrySet().associate { it.key to it.value.toString() },
                summaryText = summaryLine,
                mainOutput = mainBody,
                durationSec = duration,
                isRunning = isRunning,
            )
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
                            pushLink(LinkAnnotation.Url(match.value))
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

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = textColor),
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

/**
 * Renders an attachment inline inside a chat bubble.
 * Images are displayed as thumbnails; other files show a compact card.
 */
@Composable
private fun InlineAttachment(
    attachment: Attachment,
    textColor: Color,
) {
    if (attachment.isImage) {
        // Image attachment — show as a rounded thumbnail
        AsyncImage(
            model = attachment.uri,
            contentDescription = attachment.name,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        // Non-image file — show a card with file icon and name
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = textColor.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, textColor.copy(alpha = 0.2f)),
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = textColor.copy(alpha = 0.8f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = attachment.name,
                        color = textColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = attachment.formattedSize,
                        color = textColor.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
