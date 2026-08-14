package com.m57.hermescontrol.ui.chat

import android.content.ClipData
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.theme.DarkOnSurface
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.LightOnSurface
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.onColorFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The user-message bubble — the universal anchor in the full-bleed chat
 * renderer (issue #866). Agent prose renders full-bleed; user messages keep
 * this bubble so the conversation stays scannable.
 */
@Composable
fun ChatBubble(
    message: ChatMessage,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    onOpenAttachment: (Attachment) -> Unit = {},
    onSaveAttachment: (Attachment) -> Unit = {},
    savingAttachmentPath: String? = null,
    canSaveAttachment: Boolean = true,
    onImageClick: (ImageViewerModel) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val maxBubbleWidth = screenWidth * 0.80f

    AnimatedVisibility(
        visible = true,
        enter =
            fadeIn() +
                expandVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                ),
    ) {
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        var copied by remember { mutableStateOf(false) }

        // Copy feedback: briefly show ✓ then revert
        LaunchedEffect(copied) {
            if (copied) {
                delay(1500)
                copied = false
            }
        }

        val statusColors = LocalHermesStatusColors.current

        val highlightedText =
            remember(message.content, searchQuery, isCurrentMatch, statusColors) {
                if (searchQuery.isNotBlank()) {
                    buildHighlightedString(
                        message.content,
                        searchQuery,
                        isCurrentMatch,
                        statusColors,
                    )
                } else {
                    AnnotatedString(message.content)
                }
            }
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            val primary = MaterialTheme.colorScheme.primary
            val userBubbleTextColor =
                if (primary.luminance() > 0.5f) {
                    if (MaterialTheme.colorScheme.onPrimary.luminance() < 0.5f) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        LightOnSurface
                    }
                } else {
                    if (MaterialTheme.colorScheme.onPrimary.luminance() > 0.5f) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        DarkOnSurface
                    }
                }
            Box {
                Surface(
                    modifier =
                        Modifier
                            .widthIn(max = maxBubbleWidth)
                            .clip(
                                RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = 16.dp,
                                    bottomEnd = 4.dp,
                                ),
                            ).background(color = primary)
                            .testTag("chat_bubble_user"),
                    color = Color.Transparent,
                    tonalElevation = 0.dp,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        SelectionContainer {
                            Text(
                                text = highlightedText,
                                color = userBubbleTextColor,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        // Render inline attachments
                        if (!message.attachments.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            message.attachments.forEach { attachment ->
                                InlineAttachment(
                                    attachment = attachment,
                                    textColor = userBubbleTextColor,
                                    onOpen = { onOpenAttachment(it) },
                                    onSave = { onSaveAttachment(it) },
                                    savingPath = savingAttachmentPath,
                                    canSave = canSaveAttachment,
                                    onImageClick = onImageClick,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                        if (!message.isStreaming) {
                            Row(
                                modifier =
                                    Modifier
                                        .align(Alignment.End)
                                        .padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            clipboard.setClipEntry(
                                                ClipEntry(ClipData.newPlainText(null, message.content)),
                                            )
                                        }
                                        copied = true
                                    },
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Icon(
                                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                        contentDescription = stringResource(R.string.content_desc_copy),
                                        modifier = Modifier.size(12.dp),
                                        tint = userBubbleTextColor.copy(alpha = 0.7f),
                                    )
                                }
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text =
                                        formatTimestamp(
                                            message.timestamp,
                                            DateFormat.is24HourFormat(LocalContext.current),
                                        ),
                                    color = userBubbleTextColor.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelfImprovementReviewCard(
    content: String,
    modifier: Modifier = Modifier,
) {
    val cleanText =
        content
            .removePrefix("💾")
            .replace(Regex("^\\s*Self-improvement review:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
    val isSkill =
        cleanText.contains("skill", ignoreCase = true) ||
            cleanText.contains("SKILL.md", ignoreCase = true)
    val icon = if (isSkill) "🛠" else "🧠"
    val title =
        if (isSkill) {
            "Self-Improvement Review • Skill Patched"
        } else {
            "Self-Improvement Review • Memory Updated"
        }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag("self_improvement_review_card"),
        shape = RoundedCornerShape(10.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = icon, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = cleanText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun SystemBubble(
    message: ChatMessage,
    onRespondApproval: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (message.content.contains("Self-improvement review:", ignoreCase = true)) {
        SelfImprovementReviewCard(content = message.content, modifier = modifier)
        return
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message.content,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        )

        // Approval action buttons
        if (message.approvalInfo != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = { onRespondApproval("approve") },
                    modifier =
                        Modifier
                            .height(36.dp)
                            .testTag("approve_button"),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Approve")
                }

                FilledTonalButton(
                    onClick = { onRespondApproval("deny") },
                    modifier =
                        Modifier
                            .height(36.dp)
                            .testTag("deny_button"),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Deny")
                }
            }
        }
    }
}

internal fun formatTimestamp(
    timestamp: Long,
    is24Hour: Boolean,
): String {
    val pattern = if (is24Hour) "HH:mm" else "h:mm a"
    return DateTimeFormatter
        .ofPattern(pattern)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))
}

/**
 * Build an AnnotatedString with search matches highlighted.
 */
private fun buildHighlightedString(
    text: String,
    query: String,
    isCurrentMatch: Boolean = false,
    statusColors: HermesStatusColors,
): AnnotatedString {
    val (highlightColor, highlightText) =
        if (isCurrentMatch) {
            statusColors.warning to statusColors.onWarning
        } else {
            statusColors.warningContainer to onColorFor(statusColors.warningContainer)
        }
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val matchEnd = text.indexOf(query, i, ignoreCase = true)
            if (matchEnd == -1) {
                // No more matches — append the rest
                append(text.substring(i))
                i = text.length
            } else {
                // Append text before the match
                if (matchEnd > i) {
                    append(text.substring(i, matchEnd))
                }
                // Append the match highlighted
                withStyle(SpanStyle(background = highlightColor, color = highlightText)) {
                    append(text.substring(matchEnd, matchEnd + query.length))
                }
                i = matchEnd + query.length
            }
        }
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
    canSave: Boolean,
    onImageClick: (ImageViewerModel) -> Unit,
) {
    val attachmentPath = attachment.gatewayUrl?.let(::gatewayPathFromUrl) ?: attachment.name
    val isSaving = savingPath != null && savingPath == attachmentPath
    val clickable = Modifier.clickable { onOpen(attachment) }
    if (attachment.isVideo) {
        var showVideoDialog by remember { mutableStateOf(false) }
        com.m57.hermescontrol.ui.chat.components.InlineVideoPlayer(
            videoUri = attachment.uri,
            onFullScreenClick = { showVideoDialog = true },
        )
        if (showVideoDialog) {
            com.m57.hermescontrol.ui.chat.components.VideoViewerDialog(
                videoUri = attachment.uri,
                onDismissRequest = { showVideoDialog = false },
            )
        }
    } else if (attachment.isImage) {
        // Image / GIF attachment — show thumbnail with GIF badge & tap-to-play animation.
        com.m57.hermescontrol.ui.chat.components.GifImageThumbnail(
            model = attachment.uri,
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
