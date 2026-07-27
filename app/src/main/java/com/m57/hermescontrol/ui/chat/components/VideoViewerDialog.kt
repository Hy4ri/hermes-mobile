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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * Fullscreen Video Viewer modal dialog (issue #722).
 */
@Composable
fun VideoViewerDialog(
    videoUri: String,
    onDismissRequest: () -> Unit,
) {
    var isPlaying by remember { mutableStateOf(true) }
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

    Dialog(
        onDismissRequest = onDismissRequest,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim)
                    .clickable { showControls = !showControls }
                    .testTag("video_viewer_dialog"),
        ) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(Uri.parse(videoUri))
                        setOnPreparedListener { mp ->
                            durationMs = mp.duration.toLong()
                            start()
                            isPlaying = true
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

            // Top Bar with Close Button
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.70f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.testTag("close_video_dialog_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                        }
                    }
                }
            }

            // Bottom Controls Bar
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
                                .padding(horizontal = 12.dp, vertical = 8.dp),
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
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Text(
                            text = formatMediaDuration(currentPositionMs),
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontSize = 12.sp,
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
                                    .padding(horizontal = 8.dp),
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
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
