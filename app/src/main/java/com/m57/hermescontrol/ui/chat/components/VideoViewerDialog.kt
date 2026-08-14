package com.m57.hermescontrol.ui.chat.components

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ImageBytesResolver
import com.m57.hermescontrol.ui.chat.MediaImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fullscreen Video Viewer modal dialog with Save & Share (issue #722).
 */
@Composable
fun VideoViewerDialog(
    videoUri: String,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var isBusy by remember { mutableStateOf(false) }

    val savedMsg = stringResource(R.string.image_viewer_saved)
    val saveFailedMsg = stringResource(R.string.image_viewer_save_failed)
    val loadFailedFmt = stringResource(R.string.image_viewer_load_failed)
    val shareTitle = stringResource(R.string.image_viewer_share_title)

    val onSave: () -> Unit = {
        if (!isBusy) {
            isBusy = true
            scope.launch(Dispatchers.IO) {
                val resolved = ImageBytesResolver.resolve(context, videoUri, "video/mp4")
                val result =
                    when (resolved) {
                        is ImageBytesResolver.Result.Bytes -> {
                            val uri =
                                MediaImageStore.saveToDownloads(
                                    context,
                                    resolved.bytes,
                                    "hermes-video.${resolved.extension}",
                                    resolved.mimeType,
                                )
                            if (uri != null) savedMsg else saveFailedMsg
                        }

                        is ImageBytesResolver.Result.Error -> {
                            String.format(loadFailedFmt, resolved.message)
                        }
                    }
                withContext(Dispatchers.Main) {
                    isBusy = false
                    Toast.makeText(context, result, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val onShare: () -> Unit = {
        if (!isBusy) {
            isBusy = true
            scope.launch(Dispatchers.IO) {
                val resolved = ImageBytesResolver.resolve(context, videoUri, "video/mp4")
                val intent =
                    when (resolved) {
                        is ImageBytesResolver.Result.Bytes -> {
                            MediaImageStore.buildShareIntent(
                                context,
                                resolved.bytes,
                                "hermes-video.${resolved.extension}",
                                resolved.mimeType,
                            )
                        }

                        is ImageBytesResolver.Result.Error -> {
                            null
                        }
                    }
                withContext(Dispatchers.Main) {
                    isBusy = false
                    if (intent != null) {
                        context.startActivity(android.content.Intent.createChooser(intent, shareTitle))
                    } else {
                        Toast.makeText(context, saveFailedMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

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
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val uri = Uri.parse(videoUri)
                        val headers = mutableMapOf<String, String>()
                        val cookieVal =
                            com.m57.hermescontrol.data.remote.CookieManager
                                .getSessionCookie()
                        if (!cookieVal.isNullOrBlank()) {
                            headers["Cookie"] = "hermes_session_at=$cookieVal"
                        }
                        val token =
                            com.m57.hermescontrol.data.local.AuthManager
                                .getToken()
                        if (!token.isNullOrBlank()) {
                            headers["Authorization"] = "Bearer $token"
                        }
                        setVideoURI(uri, headers)
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

            // Center overlay play button when paused
            if (!isPlaying) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f),
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(64.dp)
                            .clickable {
                                videoViewRef?.let { view ->
                                    view.start()
                                    isPlaying = true
                                }
                            },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.video_play),
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }

            // Top Bar with Back/Close, Save, Share
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
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.testTag("close_video_dialog_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(horizontal = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                        } else {
                            IconButton(onClick = onSave, enabled = !isBusy) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = "Save",
                                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                                )
                            }
                            IconButton(onClick = onShare, enabled = !isBusy) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = "Share",
                                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                                )
                            }
                        }
                    }
                }
            }

            // Floating Bottom Controls Bar
            AnimatedVisibility(
                visible = showControls || !isPlaying,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.80f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
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
