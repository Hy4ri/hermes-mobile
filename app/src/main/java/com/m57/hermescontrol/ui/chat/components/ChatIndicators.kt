package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.SubagentIndicator

/**
 * Animated "🤔 Thinking…" card shown while the assistant is thinking
 * but has not yet produced visible text.
 */
@Composable
fun ThinkingIndicator(thinkingText: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "thinking_alpha",
    )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = LocalHermesStatusColors.current.infoContainer.copy(alpha = 0.5f),
                ),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🤔",
                    fontSize = 14.sp,
                    modifier = Modifier.graphicsLayer { this.alpha = alpha },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        if (thinkingText.isNotBlank()) {
                            val display = thinkingText.takeLast(100)
                            stringResource(R.string.chat_thinking_param, display)
                        } else {
                            stringResource(R.string.chat_thinking)
                        },
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            color = LocalHermesStatusColors.current.info,
                        ),
                    maxLines = 2,
                    modifier = Modifier.animateContentSize(),
                )
            }
        }
    }
}

/**
 * Row showing a subagent task progress chip in the message list.
 */
@Composable
fun SubagentIndicatorRow(
    indicator: SubagentIndicator,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "🔀",
                style = MaterialTheme.typography.bodySmall,
            )

            val taskProgressText =
                if (indicator.taskIndex != null && indicator.taskCount != null) {
                    " (${indicator.taskIndex}/${indicator.taskCount})"
                } else {
                    ""
                }

            val goalText = indicator.goal ?: "Subagent task"
            val textPreview =
                if (!indicator.text.isNullOrEmpty()) {
                    ": ${indicator.text}"
                } else if (!indicator.summary.isNullOrEmpty()) {
                    ": ${indicator.summary}"
                } else {
                    ""
                }

            Text(
                text = "$goalText$taskProgressText$textPreview",
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
