package com.m57.hermescontrol.ui.chat

import android.widget.Toast
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.m57.hermescontrol.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen image viewer (issue #723).
 *
 * Opens from any chat image (bubble thumbnail, markdown `![alt](url)`, inline
 * attachment). Provides pinch-zoom + pan, a downswipe-to-dismiss gesture, and a
 * top bar with **Save** (writes to device Downloads via [MediaImageStore]) and
 * **Share** (Android share sheet) — both fully device-local, never touching the
 * Hermes server.
 *
 * Save/Share resolve the image bytes through [ImageBytesResolver], which handles
 * the same model kinds Coil already renders (`data:`, `content://`, `http(s)`).
 */
@Composable
fun ImageViewerDialog(
    image: ImageViewerModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var isBusy by remember { mutableStateOf(false) }

    // Resolve string resources at composable scope (Lint forbids reading
    // resource *values* from LocalContext.current inside coroutine lambdas).
    val savedMsg = stringResource(R.string.image_viewer_saved)
    val saveFailedMsg = stringResource(R.string.image_viewer_save_failed)
    val loadFailedFmt = stringResource(R.string.image_viewer_load_failed)
    val shareTitle = stringResource(R.string.image_viewer_share_title)
    val shareFailedMsg = stringResource(R.string.image_viewer_share_failed)

    val onSave: () -> Unit = {
        if (!isBusy) {
            isBusy = true
            scope.launch(Dispatchers.IO) {
                val resolved = ImageBytesResolver.resolve(context, image.model, image.mimeType)
                val result =
                    when (resolved) {
                        is ImageBytesResolver.Result.Bytes -> {
                            val name = image.name.ifBlank { "hermes-image.${resolved.extension}" }
                            val uri =
                                MediaImageStore.saveToDownloads(
                                    context,
                                    resolved.bytes,
                                    name,
                                    resolved.mimeType,
                                )
                            if (uri != null) {
                                savedMsg
                            } else {
                                saveFailedMsg
                            }
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
                val resolved = ImageBytesResolver.resolve(context, image.model, image.mimeType)
                val intent =
                    when (resolved) {
                        is ImageBytesResolver.Result.Bytes -> {
                            val name = image.name.ifBlank { "hermes-image.${resolved.extension}" }
                            MediaImageStore.buildShareIntent(
                                context,
                                resolved.bytes,
                                name,
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
                        context.startActivity(
                            android.content.Intent
                                .createChooser(intent, shareTitle),
                        )
                    } else {
                        Toast
                            .makeText(
                                context,
                                shareFailedMsg,
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.scrim,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Zoomable / pannable image.
                AsyncImage(
                    model = image.model,
                    contentDescription =
                        image.name.ifBlank { stringResource(R.string.image_viewer_content_desc) },
                    modifier =
                        Modifier
                            .fillMaxSize()
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
                                            onDismiss()
                                        }
                                    },
                                )
                            },
                    contentScale = ContentScale.Fit,
                )

                // Top action bar — Save / Share / Close.
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.image_viewer_close),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Row {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(horizontal = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            IconButton(onClick = onSave, enabled = !isBusy) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = stringResource(R.string.image_viewer_save),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            IconButton(onClick = onShare, enabled = !isBusy) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = stringResource(R.string.image_viewer_share),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
