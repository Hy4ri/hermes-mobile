package com.m57.hermescontrol.ui.chat.components

import android.net.Uri
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.remote.MediaDataSourceProvider
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.ui.chat.MediaExportHelper
import com.m57.hermescontrol.ui.chat.MediaKind
import com.m57.hermescontrol.ui.chat.classifyMedia
import com.m57.hermescontrol.ui.chat.mediaNameFromPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Unified Media Viewer Dialog for both Audio and Video, backed by AndroidX Media3 ExoPlayer.
 */
@OptIn(UnstableApi::class)
@Composable
fun MediaViewerDialog(
    mediaUri: String,
    onDismissRequest: () -> Unit,
    title: String? = null,
    mimeType: String? = null,
) {
    key(mediaUri, mimeType) {
        MediaViewerContent(mediaUri, onDismissRequest, title, mimeType)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MediaViewerContent(
    mediaUri: String,
    onDismissRequest: () -> Unit,
    title: String?,
    mimeType: String?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val mediaKind =
        remember(mediaUri, mimeType, title) {
            classifyMedia(mimeType = mimeType, name = title, uri = mediaUri)
        }
    val isAudio = mediaKind == MediaKind.AUDIO
    val displayName =
        remember(mediaUri, title) {
            title?.takeIf { it.isNotBlank() } ?: mediaNameFromPath(mediaUri)
        }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var isEnded by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }

    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableLongStateOf(0L) }

    var showControls by remember { mutableStateOf(true) }
    var isBusy by remember { mutableStateOf(false) }

    val statusColors = LocalHermesStatusColors.current
    val saveFailed = stringResource(R.string.media_player_save_failed)
    val shareFailed = stringResource(R.string.media_player_share_failed)
    val shareTitle = stringResource(R.string.media_player_share_title)

    val player =
        remember(context, mediaUri) {
            val dataSourceFactory = MediaDataSourceProvider.createDataSourceFactory(context)
            val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
            val audioAttributes =
                AudioAttributes
                    .Builder()
                    .setContentType(if (isAudio) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build()

            ExoPlayer
                .Builder(context)
                .setRenderersFactory(
                    // Emulator guard: the goldfish "hardware" codecs crash on queueInputBuffer
                    // ("Decoder failed: c2.goldfish.h264.decoder"); deprioritize them so the
                    // reliable software codecs are selected instead. Real devices never ship
                    // goldfish codecs, so this has no effect on phones.
                    androidx.media3.exoplayer
                        .DefaultRenderersFactory(context)
                        .setEnableDecoderFallback(true)
                        .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                            androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                                .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                                .sortedBy { it.name.contains("goldfish", ignoreCase = true) }
                        },
                ).setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri(Uri.parse(mediaUri)))
                }
        }

    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            isBuffering = true
                        }

                        Player.STATE_READY -> {
                            isBuffering = false
                            hasError = false
                            durationMs = player.duration.coerceAtLeast(0L)
                        }

                        Player.STATE_ENDED -> {
                            isBuffering = false
                            isPlaying = false
                            isEnded = true
                            currentPositionMs = durationMs
                        }

                        Player.STATE_IDLE -> {
                            isBuffering = false
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    isPlaying = isPlayingNow
                    if (isPlayingNow) {
                        isEnded = false
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    isBuffering = false
                    hasError = true
                }
            }
        player.addListener(listener)
        player.prepare()
        player.playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                    player.pause()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(player, isPlaying, isDragging) {
        while (isPlaying && !isDragging) {
            currentPositionMs = player.currentPosition.coerceAtLeast(0L)
            if (durationMs <= 0L && player.duration > 0L) {
                durationMs = player.duration
            }
            delay(200)
        }
    }

    val onSave: () -> Unit = {
        if (!isBusy) {
            isBusy = true
            scope.launch {
                try {
                    val msg =
                        MediaExportHelper.saveMediaToDownloads(
                            context = context,
                            uri = mediaUri,
                            fallbackMime =
                                mimeType?.takeUnless { it == "application/octet-stream" }
                                    ?: com.m57.hermescontrol.ui.chat
                                        .mediaMimeForPath(displayName),
                            displayName = displayName,
                        )
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
                } finally {
                    isBusy = false
                }
            }
        }
    }

    val onShare: () -> Unit = {
        if (!isBusy) {
            isBusy = true
            scope.launch {
                try {
                    val intent =
                        MediaExportHelper.shareMedia(
                            context = context,
                            uri = mediaUri,
                            fallbackMime =
                                mimeType?.takeUnless { it == "application/octet-stream" }
                                    ?: com.m57.hermescontrol.ui.chat
                                        .mediaMimeForPath(displayName),
                            displayName = displayName,
                        )
                    if (intent != null) {
                        context.startActivity(
                            android.content.Intent.createChooser(
                                intent,
                                shareTitle,
                            ),
                        )
                    } else {
                        Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(context, shareFailed, Toast.LENGTH_SHORT).show()
                } finally {
                    isBusy = false
                }
            }
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
                    .background(MaterialTheme.colorScheme.surface)
                    .testTag("media_viewer_dialog"),
        ) {
            if (!isAudio) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Keep the background gesture out of the controls' ancestor chain.
                Box(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (isPlaying && !isDragging) showControls = !showControls
                    },
                )
            } else if (!isBuffering && !hasError) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(bottom = 180.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(100.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Audiotrack,
                                contentDescription = stringResource(R.string.media_player_audio),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(56.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.size(16.dp))
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Top Bar Controls
            AnimatedVisibility(
                visible = showControls || isAudio || !isPlaying || isDragging,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .fillMaxWidth(),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.testTag("media_close_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.media_player_close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )

                    IconButton(
                        onClick = onSave,
                        enabled = !isBusy,
                        modifier = Modifier.testTag("media_save_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = stringResource(R.string.media_player_save),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    IconButton(
                        onClick = onShare,
                        enabled = !isBusy,
                        modifier = Modifier.testTag("media_share_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.media_player_share),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // Center play/pause/loading/error
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(48.dp)
                            .testTag("media_buffering_indicator"),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (hasError) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.media_player_load_failed, ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColors.error,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    IconButton(
                        onClick = {
                            hasError = false
                            isBuffering = true
                            player.prepare()
                            player.play()
                        },
                        modifier = Modifier.size(56.dp).testTag("media_retry_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.media_player_retry),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            } else {
                AnimatedVisibility(
                    visible = showControls || isAudio || !isPlaying || isDragging,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                        modifier =
                            Modifier
                                .size(64.dp)
                                .testTag("media_play_pause_button")
                                .clickable {
                                    if (isEnded) {
                                        player.seekTo(0)
                                        player.play()
                                        isEnded = false
                                    } else if (isPlaying) {
                                        player.pause()
                                    } else {
                                        player.play()
                                    }
                                },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            val icon =
                                when {
                                    isEnded -> Icons.Filled.Replay
                                    isPlaying -> Icons.Filled.Pause
                                    else -> Icons.Filled.PlayArrow
                                }
                            val desc =
                                when {
                                    isEnded -> stringResource(R.string.media_player_replay)
                                    isPlaying -> stringResource(R.string.media_player_pause)
                                    else -> stringResource(R.string.media_player_play)
                                }
                            Icon(
                                imageVector = icon,
                                contentDescription = desc,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                }
            }

            // Bottom Bar Controls
            AnimatedVisibility(
                visible = showControls || isAudio || !isPlaying || isDragging,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .fillMaxWidth(),
            ) {
                val displayPosition = if (isDragging) dragPositionMs else currentPositionMs
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formatMediaDuration(displayPosition),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(48.dp),
                        )

                        Slider(
                            value = displayPosition.coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
                            onValueChange = {
                                isDragging = true
                                dragPositionMs = it.toLong()
                            },
                            onValueChangeFinished = {
                                player.seekTo(dragPositionMs)
                                currentPositionMs = dragPositionMs
                                isEnded = false
                                isDragging = false
                            },
                            enabled = durationMs > 0 && !hasError && player.isCurrentMediaItemSeekable,
                            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                            modifier = Modifier.weight(1f).testTag("media_seek_slider"),
                            colors =
                                SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                ),
                        )

                        Text(
                            text = formatMediaDuration(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(48.dp),
                        )
                    }
                }
            }
        }
    }
}
