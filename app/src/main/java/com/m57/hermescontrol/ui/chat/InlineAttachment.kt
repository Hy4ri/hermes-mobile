package com.m57.hermescontrol.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment

/**
 * Renders a message's attachments, shared by user bubbles and agent messages.
 * Emits directly into the caller's Column (no wrapper) so spacing is unchanged:
 * 6dp before the list, 4dp after each item. Renders nothing when empty.
 */
@Composable
internal fun InlineAttachmentList(
    attachments: List<Attachment>?,
    textColor: Color,
    onOpen: (Attachment) -> Unit,
    onSave: (Attachment) -> Unit,
    savingPath: String?,
    openingPath: String?,
    canSave: Boolean,
    onImageClick: (ImageViewerModel) -> Unit,
    diagnosticId: String? = null,
    frameKeyPrefix: String? = null,
) {
    if (attachments.isNullOrEmpty()) return
    Spacer(modifier = Modifier.height(6.dp))
    attachments.forEachIndexed { index, attachment ->
        InlineAttachment(
            attachment = attachment,
            diagnosticId = diagnosticId?.let { "$it:$index" },
            frameKey = frameKeyPrefix?.let { "$it:$index" },
            textColor = textColor,
            onOpen = onOpen,
            onSave = onSave,
            savingPath = savingPath,
            openingPath = openingPath,
            canSave = canSave,
            onImageClick = onImageClick,
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/**
 * Renders an attachment inline inside a chat bubble.
 * Images are displayed as thumbnails; other files show a compact card.
 */
@Composable
internal fun InlineAttachment(
    attachment: Attachment,
    textColor: Color,
    onOpen: (Attachment) -> Unit,
    onSave: (Attachment) -> Unit,
    savingPath: String?,
    openingPath: String?,
    canSave: Boolean,
    onImageClick: (ImageViewerModel) -> Unit,
    diagnosticId: String? = null,
    frameKey: String? = null,
) {
    val attachmentPath = attachment.gatewayUrl?.let(::gatewayPathFromUrl) ?: attachment.name
    val isSaving = savingPath != null && savingPath == attachmentPath
    val isOpening = openingPath != null && openingPath == attachmentPath
    val clickable = Modifier.clickable { onOpen(attachment) }
    if (attachment.isVideo || attachment.isAudio) {
        var showMediaDialog by remember(attachment.uri) { mutableStateOf(false) }
        com.m57.hermescontrol.ui.chat.components.InlineMediaPlayer(
            uri = attachment.gatewayUrl ?: attachment.uri,
            title = attachment.name,
            mimeType = attachment.mimeType,
            onFullScreenClick = { showMediaDialog = true },
        )
        if (showMediaDialog) {
            com.m57.hermescontrol.ui.chat.components.MediaViewerDialog(
                mediaUri = attachment.gatewayUrl ?: attachment.uri,
                title = attachment.name,
                mimeType = attachment.mimeType,
                onDismissRequest = { showMediaDialog = false },
            )
        }
    } else if (attachment.isImage) {
        // Image / GIF attachment — show thumbnail with GIF badge & tap-to-play animation.
        com.m57.hermescontrol.ui.chat.components.GifImageThumbnail(
            model = attachment.uri,
            diagnosticId = diagnosticId,
            frameKey = frameKey,
            contentDescription = attachment.name,
            isGif = attachment.isGif,
            onClick = {
                onImageClick(
                    ImageViewerModel(
                        model = attachment.uri,
                        name = attachment.name,
                        mimeType = if (attachment.isGif) "image/gif" else attachment.mimeType,
                    ),
                )
            },
        )
    } else {
        // Non-image file — native save picker on the trailing action.
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = textColor.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, textColor.copy(alpha = 0.16f)),
            modifier = clickable,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.name,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = attachment.formattedSize,
                        color = textColor.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (isOpening) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                if (attachment.gatewayUrl != null) {
                    IconButton(
                        onClick = { onSave(attachment) },
                        enabled = canSave,
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = stringResource(R.string.chat_save_attachment, attachment.name),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
