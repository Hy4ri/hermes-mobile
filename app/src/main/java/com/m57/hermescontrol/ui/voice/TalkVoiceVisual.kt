package com.m57.hermescontrol.ui.voice

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.theme.LocalSpacing
import kotlin.math.sqrt

/** Four quiet marks; only listening input is an audio-level visualization. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TalkVoiceVisual(
    phase: String,
    muted: Boolean,
    level: Float,
    interactive: Boolean,
    interruptLabel: String,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listening = !muted && (phase == "listening" || phase == "connected")
    val microphoneLevel =
        if (listening && level.isFinite()) (sqrt(level.coerceIn(0f, 1f)) * 2.5f).coerceAtMost(1f) else 0f
    val input =
        animateFloatAsState(
            targetValue = microphoneLevel,
            animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            label = "talk_microphone_level",
        )
    // Muting input does not stop the assistant's playback activity.
    val speaking = phase == "speaking"
    val active = speaking || (!muted && (phase == "connecting" || phase == "executing"))
    // One collective breath represents state activity, never measured assistant audio.
    // Conditional composition removes the infinite transition completely when settled.
    val activity =
        if (active) {
            rememberInfiniteTransition(label = "talk_activity").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "talk_activity_breath",
            )
        } else {
            null
        }
    val color = MaterialTheme.colorScheme.onSurface
    val spacing = LocalSpacing.current
    val interaction =
        if (interactive) {
            Modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onInterrupt)
                .semantics { contentDescription = interruptLabel }
        } else {
            Modifier.clearAndSetSemantics { }
        }

    Canvas(modifier = modifier.then(interaction)) {
        // State is read in drawing, not composition. Geometry uses inline value types;
        // every frame draws exactly four primitives without lists, paths, or brushes.
        val amplitude = if (listening) input.value else 0f
        val breath = activity?.value ?: 0f
        val diameter = minOf(spacing.xxl.toPx(), size.width / 7f, size.height / 4f)
        val gap = minOf(spacing.lg.toPx(), diameter * 0.5f)
        val groupWidth = diameter * 4f + gap * 3f
        val start = (size.width - groupWidth) / 2f
        val scale = if (active) 0.96f + breath * 0.04f else 1f
        val width = diameter * scale
        val stateStretch = if (speaking) 0.25f + breath * 0.12f else 0f
        for (index in 0 until 4) {
            val inputWeight =
                when (index) {
                    0 -> 0.65f
                    1 -> 1f
                    2 -> 0.9f
                    else -> 0.55f
                }
            val height = width * (1f + amplitude * inputWeight * 1.5f + stateStretch)
            drawRoundRect(
                color = color,
                topLeft =
                    Offset(
                        x = start + index * (diameter + gap) + (diameter - width) / 2f,
                        y = (size.height - height) / 2f,
                    ),
                size = Size(width, height),
                cornerRadius = CornerRadius(width / 2f),
            )
        }
    }
}
