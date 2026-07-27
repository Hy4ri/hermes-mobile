package com.m57.hermescontrol.ui.chat.components

import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Inline Video Player component for chat bubbles and markdown blocks (issue #722).
 */
@Composable
fun InlineVideoPlayer(
    videoUri: String,
    modifier: Modifier = Modifier,
    onFullScreenClick: () -> Unit = {},
) {
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var showControls by remember { mutableStateOf(true) }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            videoViewRef?.let { view ->
                if (view.isPlaying) {
                    currentPositionMs = view.currentPosition.toLong()
                    if (durationMs <= 0 && view.duration > 0) {
                        durationMs = view.duration.toLong()
                    }
                }
            }
            delay(250)
        }
    }

    DisposableEffect(videoUri) {
        onDispose {
            videoViewRef?.stopPlayback()
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { showControls = !showControls }
                .testTag("inline_video_player"),
    ) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setVideoURI(Uri.parse(videoUri))
                    setOnPreparedListener { mp ->
                        durationMs = mp.duration.toLong()
                        mp.isLooping = false
                    }
                    setOnCompletionListener {
                        isPlaying = false
                        currentPositionMs = durationMs
                    }
                }
            },
            update = { view ->
                videoViewRef = view
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Overlay play button when paused
        if (!isPlaying) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(56.dp)
                        .clickable {
                            videoViewRef?.let { view ->
                                view.start()
                                isPlaying = true
                            }
                        }.testTag("video_play_overlay"),
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
        }

        // Floating Control Bar
        AnimatedVisibility(
            visible = showControls || !isPlaying,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            videoViewRef?.let { view ->
                                if (isPlaying) {
                                    view.pause()
                                    isPlaying = false
                                } else {
                                    view.start()
                                    isPlaying = true
                                }
                            }
                        },
                        modifier = Modifier.size(32.dp).testTag("video_play_pause_button"),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = formatMediaDuration(currentPositionMs),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 11.sp,
                    )

                    Slider(
                        value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f,
                        onValueChange = { fraction ->
                            val targetMs = (fraction * durationMs).toLong()
                            currentPositionMs = targetMs
                            videoViewRef?.seekTo(targetMs.toInt())
                        },
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp)
                                .testTag("video_seeker"),
                        colors =
                            SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.inverseOnSurface,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f),
                            ),
                    )

                    Text(
                        text = formatMediaDuration(durationMs),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontSize = 11.sp,
                    )

                    IconButton(
                        onClick = onFullScreenClick,
                        modifier = Modifier.size(32.dp).testTag("video_fullscreen_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Fullscreen,
                            contentDescription = "Full Screen",
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
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
