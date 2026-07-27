package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal inline Video Thumbnail component for chat bubbles and markdown blocks (issue #722).
 * Shows a video preview card with a play button badge that launches the full-screen video viewer.
 */
@Composable
fun InlineVideoPlayer(
    videoUri: String,
    modifier: Modifier = Modifier,
    onFullScreenClick: () -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { onFullScreenClick() }
                .testTag("inline_video_player"),
    ) {
        // Play icon badge in center
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .testTag("video_play_overlay"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play Video",
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        // VIDEO label badge in bottom-right corner
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp),
                    ).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Video",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Text(
                    text = " VIDEO",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 10.sp,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
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
