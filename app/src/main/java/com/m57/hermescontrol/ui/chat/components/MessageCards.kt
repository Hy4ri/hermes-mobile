package com.m57.hermescontrol.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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

// ── ReasoningCard ─────────────────────────────────────────────────────────

/**
 * Dark collapsible card showing the assistant's reasoning trace
 * (reasoning-model thinking steps) before the final answer.
 *
 * Collapsed: "🧠 Reasoning · {N} steps" with chevron.
 * Expanded: full reasoning text in monospace bodySmall.
 * Streaming: pulsing indicator at bottom while [isStreaming].
 */
@Composable
fun ReasoningCard(
    reasoningText: String,
    stepCount: Int,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .animateContentSize()
                .testTag("reasoning_card"),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        shape = RoundedCornerShape(12.dp),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🧠",
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Reasoning · $stepCount steps",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Text(
                        text = reasoningText,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (isStreaming) {
                        ReasoningPulsingDot(modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

/**
 * Small pulsing dot shown at the bottom of [ReasoningCard] while streaming.
 */
@Composable
private fun ReasoningPulsingDot(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "reasoning_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "reasoning_dot_alpha",
    )
    Box(
        modifier =
            modifier
                .size(8.dp)
                .clip(CircleShape)
                .graphicsLayer { this.alpha = alpha }
                .testTag("reasoning_pulsing_dot"),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {}
    }
}

// ── CodeBlockCard ─────────────────────────────────────────────────────────

/**
 * Terminal-style card for a fenced code block with syntax highlighting,
 * a language badge, and a copy button.
 */
@Composable
fun CodeBlockCard(
    code: String,
    language: String?,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("code_block"),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1E1E1E), // VS Code–style dark background
        border = BorderStroke(1.dp, Color(0xFF333333)),
    ) {
        Column {
            // Header row: language badge (left) + copy button (right)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!language.isNullOrBlank()) {
                    Text(
                        text = language.uppercase(),
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF808080),
                            ),
                    )
                }
                Spacer(Modifier.weight(1f))
                var copied by remember { mutableStateOf(false) }
                LaunchedEffect(copied) {
                    if (copied) {
                        kotlinx.coroutines.delay(2000)
                        copied = false
                    }
                }
                IconButton(
                    onClick = {
                        copyToClipboard(context, code)
                        copied = true
                        onCopy(code)
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = "Copy code",
                        tint = Color(0xFF808080),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            // Code content with syntax highlighting
            val highlighted = remember(code) { highlightSyntax(code) }
            Text(
                text = highlighted,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = Color(0xFFD4D4D4),
                softWrap = false,
                modifier =
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Builds an [AnnotatedString] from [code] with syntax highlighting colours
 * applied via token regexes. Covers keywords, strings, comments, numbers,
 * and punctuation — everything else remains the default light-grey.
 */
internal fun highlightSyntax(code: String): AnnotatedString =
    buildAnnotatedString {
        // Combine all token patterns — match longest first to avoid overlap
        val tokens =
            listOf(
                // Single-line comment: // ...
                TokenPattern(Regex("""//[^\n]*"""), CodeComment),
                // Multi-line comment: /* ... */
                TokenPattern(Regex("""/\*[\s\S]*?\*/"""), CodeComment),
                // String literals: "..." or '...'
                TokenPattern(Regex(""""[^"\\]*(\\.[^"\\]*)*""""), CodeString),
                TokenPattern(Regex("""'[^'\\]*(\\.[^'\\]*)*'"""), CodeString),
                // Template string: `...`
                TokenPattern(Regex("""`[^`\\]*(\\.[^`\\]*)*`"""), CodeString),
                // Numbers: integer, float, hex
                TokenPattern(Regex("""\b0[xX][0-9a-fA-F]+\b"""), CodeNumber),
                TokenPattern(Regex("""\b\d+\.?\d*(?:[eE][+-]?\d+)?\b"""), CodeNumber),
                // Keywords (common across Kotlin, Python, JS, etc.)
                TokenPattern(
                    Regex(
                        """\b(?:val|var|fun|class|object|interface|enum|data|sealed|open|abstract|""" +
                            """override|private|protected|public|internal|import|package|""" +
                            """if|else|when|for|while|do|return|throw|try|catch|finally|""" +
                            """true|false|null|this|super|is|in|as|typealias|companion|""" +
                            """init|constructor|by|get|set|field|value|suspend|inline|""" +
                            """infix|operator|tailrec|external|annotation)\b""",
                    ),
                    CodeKeyword,
                ),
                // Brackets and punctuation
                TokenPattern(Regex("""[{}()\[\];,.]"""), CodePunctuation),
            )

        var lastIndex = 0
        val matches = mutableListOf<Pair<IntRange, Color>>()

        for (pattern in tokens) {
            for (match in pattern.regex.findAll(code)) {
                matches.add(match.range to pattern.color)
            }
        }

        matches.sortBy { it.first.first }
        // Resolve overlaps: later-in-text wins for same-pos, else first-match
        val resolved = mutableListOf<Pair<IntRange, Color>>()
        for (m in matches) {
            if (resolved.isEmpty()) {
                resolved.add(m)
            } else {
                val last = resolved.last()
                if (m.first.first >= last.first.last + 1) {
                    // Non-overlapping — safe add
                    resolved.add(m)
                } else if (m.first.first > last.first.first) {
                    // Overlap, this match starts later — it wins the overlapping portion
                    // Keep the last match's pre-overlap, then replace
                    resolved.removeLast()
                    // Split: keep text before overlap from last match
                    if (last.first.first < m.first.first) {
                        resolved.add(last.first.first..<m.first.first to last.second)
                    }
                    resolved.add(m)
                    // If last match extends beyond this match, add remainder
                    if (last.first.last > m.first.last) {
                        resolved.add(m.first.last + 1..last.first.last to last.second)
                    }
                }
                // else same start position — first match wins, skip this one
            }
        }

        var pos = 0
        for ((range, color) in resolved) {
            if (range.first > pos) {
                append(code.substring(pos, range.first))
            }
            withStyle(SpanStyle(color = color)) {
                append(code.substring(range.first, range.last + 1))
            }
            pos = range.last + 1
        }
        if (pos < code.length) {
            append(code.substring(pos))
        }
    }

private data class TokenPattern(val regex: Regex, val color: Color)

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
}

// ── ClarifyBubble ─────────────────────────────────────────────────────────

/**
 * Centered dashed-border bubble showing a clarify question with
 * selectable option chips and a dismiss button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClarifyBubble(
    text: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var typedText by remember { mutableStateOf("") }

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp)
                .testTag("clarify_bubble"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
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
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    options.forEach { option ->
                        SuggestionChip(
                            onClick = { onOptionSelected(option) },
                            label = { Text(option) },
                            colors =
                                SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = typedText,
                onValueChange = { typedText = it },
                label = { Text("Your response") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                FilledTonalButton(
                    onClick = {
                        if (typedText.isNotBlank()) {
                            onOptionSelected(typedText)
                            typedText = ""
                        }
                    },
                    enabled = typedText.isNotBlank(),
                ) {
                    Text("Send")
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

// ── SubagentCard ──────────────────────────────────────────────────────────

/**
 * Inline card showing subagent task progress.
 *
 * - While running: [CircularProgressIndicator] + goal text on [tertiaryContainer].
 * - On completion: ✅ checkmark + summary text.
 */
@Composable
fun SubagentCard(
    indicator: SubagentIndicator,
    modifier: Modifier = Modifier,
) {
    val isComplete = indicator.type == "subagent.complete"
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).testTag("subagent_card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isComplete) {
                Text(text = "✅", fontSize = 14.sp)
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            val displayText =
                if (isComplete && !indicator.summary.isNullOrBlank()) {
                    indicator.summary
                } else if (!indicator.goal.isNullOrBlank()) {
                    indicator.goal
                } else {
                    "Subagent task"
                }
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── TypingIndicator ───────────────────────────────────────────────────────

/**
 * Three bouncing dots shown while the assistant is typing / thinking.
 * Staggered animation: 0ms, 150ms, 300ms delay per dot.
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("typing_indicator"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in 0 until 3) {
            TypingDot(delayMs = i * 150)
        }
    }
}

@Composable
private fun TypingDot(delayMs: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing_dot_$delayMs")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(400, delayMillis = delayMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "typing_dot_offset_$delayMs",
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(400, delayMillis = delayMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "typing_dot_alpha_$delayMs",
    )
    Box(
        modifier =
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .graphicsLayer {
                    this.translationY = offset
                    this.alpha = alpha
                },
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {}
    }
}
