package com.m57.hermescontrol.ui.common

import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Determinate circular progress indicator adapting native [CircularWavyProgressIndicator].
 *
 * @param progress Lambda producing the current progress in 0.0..1.0.
 * @param modifier The modifier to apply to this progress indicator.
 * @param color The progress indicator stroke color.
 * @param strokeWidth The stroke width of the progress indicator (defaults to native 4.dp).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveCircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
) {
    val stroke = with(LocalDensity.current) { Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round) }
    CircularWavyProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        stroke = stroke,
        trackStroke = stroke,
    )
}

/**
 * Indeterminate circular progress indicator adapting native [CircularWavyProgressIndicator].
 *
 * @param modifier The modifier to apply to this progress indicator.
 * @param color The progress indicator stroke color.
 * @param strokeWidth The stroke width of the progress indicator (defaults to native 4.dp).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveCircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
) {
    val stroke = with(LocalDensity.current) { Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round) }
    CircularWavyProgressIndicator(
        modifier = modifier,
        color = color,
        stroke = stroke,
        trackStroke = stroke,
    )
}

/**
 * Determinate linear progress indicator adapting native [LinearWavyProgressIndicator].
 *
 * @param progress Lambda producing the current progress in 0.0..1.0.
 * @param modifier The modifier to apply to this progress indicator.
 * @param color The active progress indicator color.
 * @param trackColor The background track color.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveLinearProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor,
) {
    LinearWavyProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = color,
        trackColor = trackColor,
    )
}

/**
 * Indeterminate linear progress indicator adapting native [LinearWavyProgressIndicator].
 *
 * @param modifier The modifier to apply to this progress indicator.
 * @param color The active progress indicator color.
 * @param trackColor The background track color.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveLinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = WavyProgressIndicatorDefaults.trackColor,
) {
    LinearWavyProgressIndicator(
        modifier = modifier,
        color = color,
        trackColor = trackColor,
    )
}
