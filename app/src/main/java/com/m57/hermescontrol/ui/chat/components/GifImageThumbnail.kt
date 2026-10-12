package com.m57.hermescontrol.ui.chat.components

import android.graphics.drawable.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.asDrawable
import coil3.compose.AsyncImage
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatImageDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ThumbnailState { LOADING, SUCCESS, ERROR }

@Composable
fun GifImageThumbnail(
    model: Any,
    contentDescription: String?,
    isGif: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diagnosticId: String? = null,
    frameKey: String? = null,
) {
    // #1459: an attachment is not proof that its image loaded. Keep failures visible and retryable.
    var retry by remember(model) { mutableIntStateOf(0) }
    var state by remember(model, retry) { mutableStateOf(ThumbnailState.LOADING) }
    var isPlaying by remember(model, retry) { mutableStateOf(true) }
    var animatableDrawable by remember(model, retry) { mutableStateOf<Animatable?>(null) }

    val togglePlayPause: () -> Unit = {
        val nextState = !isPlaying
        isPlaying = nextState
        animatableDrawable?.let { anim ->
            if (nextState) {
                anim.start()
            } else {
                anim.stop()
            }
        }
    }

    DisposableEffect(model, diagnosticId) {
        onDispose { ChatImageDiagnostics.load("dispose", model, diagnosticId) }
    }
    val context = LocalContext.current
    // #1459: one rectangle per logical image, chosen before the first pixel and never changed afterwards.
    val frameId = frameKey ?: model.toString()
    val isLocal = model is String && (model.startsWith("file:", true) || model.startsWith("content:", true))
    var frameRatio by remember(frameId) {
        mutableStateOf(
            ImageFrameStore.get(frameId) ?: if (isLocal) null else ImageFrameStore.establish(frameId, Float.NaN),
        )
    }
    LaunchedEffect(frameId) {
        if (frameRatio == null) {
            val measured = withContext(Dispatchers.IO) { readLocalImageRatio(context, model as String) }
            frameRatio = ImageFrameStore.establish(frameId, measured ?: Float.NaN)
        }
    }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("chat_image_frame")
                .aspectRatio(frameRatio ?: ImageFrameStore.FALLBACK_RATIO)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = state == ThumbnailState.SUCCESS, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        key(model, retry) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription.takeIf { state == ThumbnailState.SUCCESS },
                onLoading = {
                    state = ThumbnailState.LOADING
                    ChatImageDiagnostics.load("start", model, diagnosticId)
                },
                onError = { result ->
                    state = ThumbnailState.ERROR
                    ChatImageDiagnostics.load("error", model, diagnosticId, result.result.throwable)
                },
                onSuccess = { result ->
                    state = ThumbnailState.SUCCESS
                    ChatImageDiagnostics.load("success", model, diagnosticId)
                    val drawable = result.result.image.asDrawable(context.resources)
                    if (drawable is Animatable) {
                        animatableDrawable = drawable
                        if (isPlaying) {
                            drawable.start()
                        } else {
                            drawable.stop()
                        }
                    }
                },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit,
            )
        }

        if (state != ThumbnailState.SUCCESS) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (state == ThumbnailState.ERROR) {
                        Text(stringResource(R.string.chat_image_load_failed))
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    contentDescription?.takeIf { it.isNotBlank() }?.let { name ->
                        Text(
                            text = name,
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state == ThumbnailState.ERROR) {
                        TextButton(onClick = { retry++ }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            }
        }

        if (isGif && state == ThumbnailState.SUCCESS) {
            // Play / Pause toggle badge
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(16.dp),
                        ).clickable { togglePlayPause() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause GIF" else "Play GIF",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = " GIF",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // Center overlay button when paused
            if (!isPlaying) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .background(
                                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                                shape = CircleShape,
                            ).clickable { togglePlayPause() }
                            .padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.gif_play),
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
