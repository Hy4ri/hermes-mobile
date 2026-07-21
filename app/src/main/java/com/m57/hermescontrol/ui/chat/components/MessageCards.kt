package com.m57.hermescontrol.ui.chat.components

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.theme.CodeComment
import com.m57.hermescontrol.theme.CodeKeyword
import com.m57.hermescontrol.theme.CodeNumber
import com.m57.hermescontrol.theme.CodePunctuation
import com.m57.hermescontrol.theme.CodeString
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── 1. ReasoningCard ──────────────────────────────────────────────────────

@Composable
fun ReasoningCard(
    reasoningText: String,
    stepCount: Int,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .animateContentSize(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "\uD83E\uDDD0 Reasoning · $stepCount step${if (stepCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = reasoningText,
                        style =
                            MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 20.sp,
                            ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isStreaming) {
                        Spacer(modifier = Modifier.height(6.dp))
                        ReasoningPulsingDots()
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningPulsingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "reasoning_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "reasoning_alpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) {
            Box(
                modifier =
                    Modifier
                        .size(4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                            shape = RoundedCornerShape(50),
                        ),
            )
            if (it < 2) Spacer(modifier = Modifier.width(3.dp))
        }
    }
}

// ── 2. CodeBlockCard ──────────────────────────────────────────────────────

private val KEYWORD_SET =
    setOf(
        "val",
        "var",
        "fun",
        "class",
        "object",
        "interface",
        "enum",
        "if",
        "else",
        "when",
        "for",
        "while",
        "do",
        "return",
        "import",
        "package",
        "data",
        "sealed",
        "abstract",
        "override",
        "private",
        "public",
        "protected",
        "internal",
        "open",
        "inline",
        "suspend",
        "companion",
        "init",
        "constructor",
        "null",
        "true",
        "false",
        "is",
        "as",
        "in",
        "try",
        "catch",
        "finally",
        "throw",
        "typealias",
        "annotation",
        "infix",
        "tailrec",
        "operator",
    )

private val STRING_RE = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
private val COMMENT_RE = Regex("//.*|/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/")
private val NUMBER_RE = Regex("\\b(\\d+\\.?\\d*[fFL]?|0[xX][0-9a-fA-F]+[lL]?)\\b")
private val KEYWORD_RE = Regex("\\b(${KEYWORD_SET.joinToString("|")})\\b")
private val PUNCTUATION_RE = Regex("[{}()\\[\\];,:.<>+\\-*/%&|^~!?=@#]")

internal fun highlightSyntax(code: String): AnnotatedString =
    buildAnnotatedString {
        data class Region(val start: Int, val end: Int, val color: Color)

        val regions = mutableListOf<Region>()

        // Strings (highest priority)
        STRING_RE.findAll(code).forEach { m ->
            regions.add(Region(m.range.first, m.range.last + 1, CodeString))
        }
        // Comments
        COMMENT_RE.findAll(code).forEach { m ->
            if (regions.none { r -> r.start <= m.range.first && r.end >= m.range.last + 1 }) {
                regions.add(Region(m.range.first, m.range.last + 1, CodeComment))
            }
        }
        // Numbers
        NUMBER_RE.findAll(code).forEach { m ->
            if (regions.none { r -> r.start <= m.range.first && r.end >= m.range.last + 1 }) {
                regions.add(Region(m.range.first, m.range.last + 1, CodeNumber))
            }
        }
        // Keywords
        KEYWORD_RE.findAll(code).forEach { m ->
            if (regions.none { r -> r.start <= m.range.first && r.end >= m.range.last + 1 }) {
                regions.add(Region(m.range.first, m.range.last + 1, CodeKeyword))
            }
        }
        // Punctuation (check per-character to avoid partial overlap)
        PUNCTUATION_RE.findAll(code).forEach { m ->
            val chars = m.value.toList()
            val uncovered =
                chars.filter { c ->
                    regions.none { r -> c.code in r.start until r.end }
                }
            if (uncovered.isNotEmpty()) {
                regions.add(Region(m.range.first, m.range.last + 1, CodePunctuation))
            }
        }

        regions.sortBy { it.start }

        var pos = 0
        for (region in regions) {
            if (region.start > pos) {
                append(code.substring(pos, region.start))
            }
            withStyle(SpanStyle(color = region.color)) {
                append(code.substring(region.start, region.end))
            }
            pos = region.end
        }
        if (pos < code.length) {
            append(code.substring(pos))
        }
    }

@Composable
private fun textColorOnInverse(): Color {
    val bg = MaterialTheme.colorScheme.inverseSurface
    return if (bg.luminance() > 0.5f) Color.Black else Color.White
}

@Composable
fun CodeBlockCard(
    code: String,
    language: String?,
    onCopy: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val highlighted = remember(code) { highlightSyntax(code) }
    val scrollState = rememberScrollState()
    val onInverse = textColorOnInverse()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
    ) {
        Column {
            // Top bar: language badge + copy
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (language != null) {
                    Text(
                        text = language.uppercase(),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        color = onInverse.copy(alpha = 0.6f),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        (
                            onCopy ?: { text ->
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
                                }
                            }
                        )(code)
                        copied = true
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    if (copied) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Copied",
                            tint = onInverse.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy code",
                            tint = onInverse.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // Scrollable code
            Box(
                modifier =
                    Modifier
                        .horizontalScroll(scrollState)
                        .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                Text(
                    text = highlighted,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 20.sp,
                        ),
                    color = onInverse,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ── 3. ClarifyBubble ──────────────────────────────────────────────────────

@Composable
fun ClarifyBubble(
    text: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border =
                BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (options.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        options.forEach { option ->
                            SuggestionChip(
                                onClick = { onOptionSelected(option) },
                                label = { Text(option, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

// ── 4. SubagentCard ───────────────────────────────────────────────────────

@Composable
fun SubagentCard(
    indicator: SubagentIndicator,
    modifier: Modifier = Modifier,
) {
    val isComplete = indicator.type == "subagent.complete"
    val containerColor by animateColorAsState(
        targetValue =
            if (isComplete) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        label = "subagent_bg",
    )

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isComplete) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Complete",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = indicator.goal ?: "Subagent task",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isComplete && !indicator.summary.isNullOrBlank()) {
                    Text(
                        text = indicator.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ── 5. TypingIndicator ────────────────────────────────────────────────────

@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dotSize = 8.dp

    Row(
        modifier =
            modifier
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            TypingDot(
                color = dotColor,
                size = dotSize,
                delayMs = index * 150,
            )
            if (index < 2) Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
private fun TypingDot(
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    delayMs: Int,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing_dot_$delayMs")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 600, delayMillis = delayMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "typing_scale_$delayMs",
    )

    Box(
        modifier =
            Modifier
                .size(size * scale)
                .background(
                    color = color.copy(alpha = 0.6f + 0.4f * scale),
                    shape = RoundedCornerShape(50),
                ),
    )
}
