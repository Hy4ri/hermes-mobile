package com.m57.hermescontrol.ui.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.MarkdownText

/**
 * Collapsible card showing the assistant's reasoning trace
 * (reasoning-model thinking steps) before the final answer.
 *
 * Collapsed: "🧠 Reasoning · {N} steps" with chevron.
 * Expanded: completed reasoning is Markdown; streaming text stays raw,
 *           capped at ~40% of the
 *           screen height with internal scroll; "Show full" lifts the cap.
 * Long-press anywhere: copies the reasoning trace to the clipboard.
 * Streaming: pulsing indicator at the bottom while [isStreaming].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReasoningCard(
    reasoningText: String,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var fullHeight by remember { mutableStateOf(false) }
    // Copy feedback: briefly show "Copied" then revert
    var copied by rememberCopyFeedback()
    val scrollState = rememberScrollState()
    val stepCount = remember(reasoningText) { reasoningText.count { it == '\n' } + 1 }
    val capHeight = with(LocalConfiguration.current) { (screenHeightDp * 0.4f).dp }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (!isStreaming) Modifier.animateContentSize() else Modifier)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                ?: return@combinedClickable
                        clipboard.setPrimaryClip(ClipData.newPlainText(null, reasoningText))
                        copied = true
                    },
                ).testTag("reasoning_card"),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🧠",
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.message_reasoning_steps, stepCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (copied) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.reasoning_copied),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    val contentModifier =
                        Modifier
                            .padding(top = 4.dp)
                            .then(
                                if (!fullHeight) {
                                    // Capped at ~40% of the screen with internal scroll.
                                    Modifier.heightIn(max = capHeight).verticalScroll(scrollState)
                                } else {
                                    // "Show full": render the whole trace at natural height.
                                    // A scrollable must NEVER be measured with an infinite
                                    // max height — inside a LazyColumn item (unbounded main
                                    // axis) that combination throws IllegalStateException.
                                    Modifier
                                },
                            )
                    if (isStreaming) {
                        Text(
                            text = reasoningText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = contentModifier,
                        )
                    } else {
                        MarkdownText(
                            text = reasoningText,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = contentModifier,
                        )
                    }
                    if (isStreaming) {
                        ReasoningPulsingDot(modifier = Modifier.padding(top = 6.dp))
                    }
                    if (fullHeight) {
                        TextButton(onClick = { fullHeight = false }) {
                            Text(stringResource(R.string.reasoning_show_less))
                        }
                    } else if (scrollState.maxValue > 0) {
                        TextButton(onClick = { fullHeight = true }) {
                            Text(stringResource(R.string.reasoning_show_full))
                        }
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
    val pulseSpec: InfiniteRepeatableSpec<Float> =
        remember {
            infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            )
        }
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = pulseSpec,
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
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {}
    }
}
