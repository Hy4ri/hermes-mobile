package com.m57.hermescontrol.ui.chat

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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.AssistantBubble
import com.m57.hermescontrol.theme.AssistantBubbleLight
import com.m57.hermescontrol.theme.StatusGreen
import com.m57.hermescontrol.theme.StatusRed
import com.m57.hermescontrol.theme.SystemMessageColor
import com.m57.hermescontrol.theme.ToolChipColor
import com.m57.hermescontrol.theme.ToolChipColorLight
import com.m57.hermescontrol.theme.UserBubble
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                .padding(horizontal = 12.dp, vertical = 3.dp),
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
                        .pointerInput(message.content) {
                            detectTapGestures(
                                onLongPress = {
                                    showCopyButton = true
                                },
                            )
                        },
                color = Color.Transparent,
                tonalElevation = 0.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SelectionContainer {
                        Text(
                            text = highlightedText,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = formatTimestamp(message.timestamp),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier =
                            Modifier
                                .align(Alignment.End)
                                .padding(top = 4.dp),
                    )
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
                            contentDescription = "Copy",
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
private fun AssistantBubble(
    message: ChatMessage,
    maxWidth: androidx.compose.ui.unit.Dp,
    isDarkTheme: Boolean,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bubbleColor = if (isDarkTheme) AssistantBubble else AssistantBubbleLight
    val textColor = MaterialTheme.colorScheme.onSurface
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
                .padding(horizontal = 12.dp, vertical = 3.dp),
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
                        ).pointerInput(message.content) {
                            detectTapGestures(
                                onLongPress = {
                                    showCopyButton = true
                                },
                            )
                        },
                color = bubbleColor,
                border =
                    BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                    ),
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
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
                            text = formatTimestamp(message.timestamp),
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
                            contentDescription = "Copy",
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
                .padding(horizontal = 24.dp, vertical = 6.dp),
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

private data class ParsedToolOutput(
    val isTerminal: Boolean = false,
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Int? = null,
    val error: String? = null,
    val mainOutput: String? = null,
    val genericFields: Map<String, String> = emptyMap(),
)

private fun parseToolOutput(content: String): ParsedToolOutput? {
    val trimmed = content.trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
    return try {
        val element =
            com.google.gson.JsonParser
                .parseString(trimmed)
        if (element.isJsonObject) {
            val obj = element.asJsonObject

            val hasStdout = obj.has("stdout")
            val hasStderr = obj.has("stderr")
            val hasExitCode = obj.has("exit_code") || obj.has("exitCode")

            if (hasStdout || hasStderr || hasExitCode) {
                val stdout =
                    obj
                        .get("stdout")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString
                        ?.takeIf { it.isNotEmpty() }
                val stderr =
                    obj
                        .get("stderr")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString
                        ?.takeIf { it.isNotEmpty() }
                val exitCode = (obj.get("exit_code") ?: obj.get("exitCode"))?.takeIf { !it.isJsonNull }?.asInt
                val error =
                    obj
                        .get("error")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString
                        ?.takeIf { it.isNotEmpty() }

                ParsedToolOutput(
                    isTerminal = true,
                    stdout = stdout,
                    stderr = stderr,
                    exitCode = exitCode,
                    error = error,
                )
            } else {
                val mainOutput =
                    (obj.get("output") ?: obj.get("result") ?: obj.get("content") ?: obj.get("text"))
                        ?.takeIf { !it.isJsonNull }
                        ?.let {
                            if (it.isJsonPrimitive) it.asString else it.toString()
                        }?.takeIf { it.isNotEmpty() }

                val genericFields = mutableMapOf<String, String>()
                obj.entrySet().forEach { (key, value) ->
                    if (key != "output" && key != "result" && key != "content" && key != "text" && !value.isJsonNull) {
                        val valStr = if (value.isJsonPrimitive) value.asString else value.toString()
                        if (valStr.isNotEmpty() && valStr != "null") {
                            genericFields[key] = valStr
                        }
                    }
                }

                ParsedToolOutput(
                    mainOutput = mainOutput,
                    genericFields = genericFields,
                )
            }
        } else if (element.isJsonArray) {
            val arr = element.asJsonArray
            ParsedToolOutput(
                mainOutput = arr.toString(),
            )
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun ParsedToolContent(
    parsed: ParsedToolOutput,
    contentColor: Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (parsed.isTerminal) {
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
                    text = "Error Output (stderr):\n$it",
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
                    text = "Execution Error: $it",
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
                        text = "Exit Code: $code",
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                color = StatusRed,
                                fontWeight = FontWeight.Medium,
                            ),
                    )
                }
            }
        } else {
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
            parsed.genericFields.forEach { (key, value) ->
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

    val parsedOutput = remember(message.content) { parseToolOutput(message.content) }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
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
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val (icon, tint) =
                        when (message.toolStatus) {
                            ToolStatus.RUNNING -> Icons.Filled.Build to MaterialTheme.colorScheme.secondary
                            ToolStatus.COMPLETED -> Icons.Filled.CheckCircle to StatusGreen
                            ToolStatus.FAILED -> Icons.Filled.Error to StatusRed
                            null -> Icons.Filled.Build to contentColor.copy(alpha = 0.6f)
                        }

                    if (message.toolStatus == ToolStatus.RUNNING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = tint,
                        )
                    }

                    Text(
                        text = message.toolName ?: "Tool",
                        style =
                            MaterialTheme.typography.labelMedium.copy(
                                color = contentColor,
                                fontFamily = FontFamily.Monospace,
                            ),
                    )
                }

                if (expanded && message.content.isNotBlank()) {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        if (parsedOutput != null && !showRawJson) {
                            ParsedToolContent(parsedOutput, contentColor)

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Show Raw JSON",
                                style =
                                    MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                modifier =
                                    Modifier.pointerInput(Unit) {
                                        detectTapGestures(onTap = { showRawJson = true })
                                    },
                            )
                        } else {
                            Text(
                                text = message.content,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        color = contentColor.copy(alpha = 0.8f),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    ),
                            )

                            if (parsedOutput != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Show Parsed Output",
                                    style =
                                        MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                    modifier =
                                        Modifier.pointerInput(Unit) {
                                            detectTapGestures(onTap = { showRawJson = false })
                                        },
                                )
                            }
                        }
                    }
                }

                // Timestamp — consistent with UserBubble/AssistantBubble
                val textColor = if (isDarkTheme) Color.White else Color.Black
                Text(
                    text = formatTimestamp(message.timestamp),
                    color = textColor.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
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
                            withStyle(SpanStyle(background = searchHighlightColor)) {
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

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
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
                withStyle(SpanStyle(background = highlightColor)) {
                    append(text.substring(matchEnd, matchEnd + query.length))
                }
                i = matchEnd + query.length
            }
        }
    }
}
