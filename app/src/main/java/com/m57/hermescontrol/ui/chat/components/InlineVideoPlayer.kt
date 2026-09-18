package com.m57.hermescontrol.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Backwards-compatible wrapper delegating to [InlineMediaPlayer].
 */
@Composable
fun InlineVideoPlayer(
    videoUri: String,
    modifier: Modifier = Modifier,
    onFullScreenClick: () -> Unit = {},
) {
    InlineMediaPlayer(
        uri = videoUri,
        modifier = modifier,
        onFullScreenClick = onFullScreenClick,
    )
}

/** Formats milliseconds into `M:SS` or `H:MM:SS` */
fun formatMediaDuration(durationMs: Long): String {
    if (durationMs <= 0) return "0:00"
    val totalSeconds = durationMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
