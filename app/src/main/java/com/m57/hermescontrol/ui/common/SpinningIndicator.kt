package com.m57.hermescontrol.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Custom indeterminate circular progress indicator that guarantees animation.
 *
 * Replaces [CircularProgressIndicator] (Material3) which may appear static
 * on some API levels or Compose versions. Uses a [rememberInfiniteTransition]
 * to rotate a sweeping arc at ~800ms per revolution.
 *
 * Designed as a drop-in replacement: same [color], [strokeWidth], and sizing.
 */
@Composable
fun SpinningIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "rotation",
    )

    Canvas(modifier = modifier) {
        // Sweep ~270° (3/4 circle) so there's a visible gap — matches
        // Material3 indeterminate look without relying on its internal animation.
        val sweep = 270f
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = sweep,
            useCenter = false,
            style =
                Stroke(
                    width = strokeWidth.toPx(),
                    cap = StrokeCap.Round,
                ),
        )
    }
}
