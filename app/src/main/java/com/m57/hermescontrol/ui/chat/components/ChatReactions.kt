package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Reaction hearts animation ──────────────────────────────────────────

/**
 * Lightweight floating-hearts animation triggered by a `reaction` WS event.
 * Hearts rise from the bottom-center of the chat area, drift slightly
 * horizontally, and fade out — purely cosmetic, no persistence.
 */
@Composable
fun ReactionHeartsOverlay(
    reactionKind: String?,
    modifier: Modifier = Modifier,
) {
    val emojis =
        remember(reactionKind) {
            when (reactionKind) {
                "ily", "<3", "good bot" -> {
                    listOf("💗", "❤️", "💕", "💖", "🩷", "💘", "💝")
                }

                else -> {
                    listOf("💗", "❤️", "💕", "💖")
                }
            }
        }

    AnimatedVisibility(
        visible = reactionKind != null,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(400)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            emojis.forEachIndexed { index, emoji ->
                key("heart_$index") {
                    FloatingHeart(
                        emoji = emoji,
                        delayMs = index * 120L + 100L,
                        horizontalOffset = ((index - emojis.size / 2) * 28).dp,
                    )
                }
            }
        }
    }
}

@Composable
fun FloatingHeart(
    emoji: String,
    delayMs: Long,
    horizontalOffset: Dp,
) {
    val alpha = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(delayMs)
        // Phase 1: fade in quickly at the bottom
        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            )
            // Phase 2: fade out slowly while rising
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 1400, easing = LinearEasing),
            )
        }
        // Float upward over the full animation
        launch {
            offsetY.animateTo(
                targetValue = -220f,
                animationSpec = tween(durationMillis = 1600, easing = LinearEasing),
            )
        }
    }

    Text(
        text = emoji,
        fontSize = 24.sp,
        modifier =
            Modifier
                .offset(x = horizontalOffset, y = offsetY.value.dp)
                .graphicsLayer(alpha = alpha.value),
    )
}
