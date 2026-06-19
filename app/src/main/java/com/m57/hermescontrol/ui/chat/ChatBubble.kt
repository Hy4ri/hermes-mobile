package com.m57.hermescontrol.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatBubble(
    message: ChatMessage,
    isDarkTheme: Boolean,
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
            MessageRole.USER -> UserBubble(message, maxBubbleWidth, modifier)
            MessageRole.ASSISTANT -> AssistantBubble(message, maxBubbleWidth, isDarkTheme, modifier)
            MessageRole.SYSTEM -> SystemBubble(message, modifier)
            MessageRole.TOOL -> ToolBubble(message, isDarkTheme, modifier)
        }
    }
}

@Composable
private fun UserBubble(
    message: ChatMessage,
    maxWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val clipboardManager = LocalClipboardManager.current
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
                                clipboardManager.setText(AnnotatedString(message.content))
                            },
                        )
                    },
            color = Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                SelectionContainer {
                    Text(
                        text = message.content,
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
    }
}

@Composable
private fun AssistantBubble(
    message: ChatMessage,
    maxWidth: androidx.compose.ui.unit.Dp,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    val bubbleColor = if (isDarkTheme) AssistantBubble else AssistantBubbleLight
    val textColor = MaterialTheme.colorScheme.onSurface
    val clipboardManager = LocalClipboardManager.current

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
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
                    )
                    .pointerInput(message.content) {
                        detectTapGestures(
                            onLongPress = {
                                clipboardManager.setText(AnnotatedString(message.content))
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

@Composable
private fun ToolBubble(
    message: ChatMessage,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val chipColor = if (isDarkTheme) ToolChipColor else ToolChipColorLight
    val contentColor = if (isDarkTheme) Color.White else Color.Black

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
                    Text(
                        text = message.content,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                color = contentColor.copy(alpha = 0.8f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            ),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
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
) {
    val uriHandler = LocalUriHandler.current
    val urlPattern = remember { Regex("""https?://[^\s)>\]\u0022\u0027]+""") }
    val annotated =
        remember(text) {
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
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ) {
                                append(match.value)
                            }
                            pop()
                            i = match.range.last + 1
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
            annotated.getStringAnnotations("URL", offset, offset)
                .firstOrNull()?.let { uriHandler.openUri(it.item) }
        },
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
