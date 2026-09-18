package com.m57.hermescontrol.ui.chat.components

import androidx.compose.runtime.Composable

/**
 * Backwards-compatible wrapper delegating to [MediaViewerDialog].
 */
@Composable
fun VideoViewerDialog(
    videoUri: String,
    onDismissRequest: () -> Unit,
) {
    MediaViewerDialog(
        mediaUri = videoUri,
        onDismissRequest = onDismissRequest,
    )
}
