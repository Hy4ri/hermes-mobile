package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.mediaNameFromPath

/**
 * Image surface of [MediaViewerDialog]: pinch-zoom + pan, downswipe-to-dismiss,
 * and the shared streaming Save/Share actions. No ExoPlayer is created here.
 */
@Composable
internal fun ImageViewerContent(
    mediaUri: String,
    onDismissRequest: () -> Unit,
    title: String?,
    mimeType: String?,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val displayName =
        remember(mediaUri, title) {
            title?.takeIf { it.isNotBlank() } ?: mediaNameFromPath(mediaUri)
        }
    val export = rememberMediaExportActions(mediaUri, mimeType, displayName)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("media_viewer_dialog"),
            color = MaterialTheme.colorScheme.scrim,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = mediaUri,
                    contentDescription =
                        title?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.image_viewer_content_desc),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag("media_image")
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ).pointerInput(Unit) {
                                detectTransformGestures(
                                    onGesture = { _, pan, zoom, _ ->
                                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                                        // While zoomed in, allow free panning; at min scale,
                                        // only vertical drags are permitted (for dismiss).
                                        scale = newScale
                                        offsetX = if (newScale > 1f) offsetX + pan.x else 0f
                                        offsetY += pan.y
                                        // Downswipe-to-dismiss when at min zoom.
                                        if (newScale <= 1f && offsetY > 120f) {
                                            onDismissRequest()
                                        }
                                    },
                                )
                            },
                    contentScale = ContentScale.Fit,
                )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.testTag("media_close_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.image_viewer_close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row {
                        if (export.isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(horizontal = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            IconButton(
                                onClick = export.onSave,
                                modifier = Modifier.testTag("media_save_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = stringResource(R.string.image_viewer_save),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(
                                onClick = export.onShare,
                                modifier = Modifier.testTag("media_share_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = stringResource(R.string.image_viewer_share),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
