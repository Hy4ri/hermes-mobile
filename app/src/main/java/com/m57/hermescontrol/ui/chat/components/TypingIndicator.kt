package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R

/**
 * Three subtle dots shown while the assistant is waiting to produce visible
 * content. Staggered opacity/scale animation keeps the indicator lightweight
 * without the distracting vertical bounce used by the old chat renderer.
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.chat_agent_status_typing)
    Row(
        modifier =
            modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = description }
                .testTag("typing_indicator"),
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
    val typingSpec: InfiniteRepeatableSpec<Float> =
        remember(delayMs) {
            infiniteRepeatable(
                animation = tween(700, delayMillis = delayMs, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            )
        }
    val offset by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = typingSpec,
        label = "typing_dot_scale_$delayMs",
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = typingSpec,
        label = "typing_dot_alpha_$delayMs",
    )
    Box(
        modifier =
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .graphicsLayer {
                    this.scaleX = offset
                    this.scaleY = offset
                    this.alpha = alpha
                },
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {}
    }
}
