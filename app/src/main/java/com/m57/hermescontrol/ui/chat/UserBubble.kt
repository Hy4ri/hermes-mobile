package com.m57.hermescontrol.ui.chat

import android.content.ClipData
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.theme.DarkOnSurface
import com.m57.hermescontrol.theme.HermesStatusColors
import com.m57.hermescontrol.theme.LightOnSurface
import com.m57.hermescontrol.theme.LocalHermesStatusColors
import com.m57.hermescontrol.theme.onColorFor
import com.m57.hermescontrol.ui.chat.components.rememberCopyFeedback
import com.m57.hermescontrol.util.BidiUtils
import kotlinx.coroutines.launch

/**
 * The user-message bubble — the universal anchor in the full-bleed chat
 * renderer (issue #866). Agent prose renders full-bleed; user messages keep
 * this bubble so the conversation stays scannable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserBubble(
    message: ChatMessage,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    onOpenAttachment: (Attachment) -> Unit = {},
    onSaveAttachment: (Attachment) -> Unit = {},
    savingAttachmentPath: String? = null,
    openingAttachmentPath: String? = null,
    canSaveAttachment: Boolean = true,
    onImageClick: (ImageViewerModel) -> Unit = {},
    messageStatsEnabled: Boolean = false,
    showUserMessageTokens: Boolean = true,
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
        // Copy feedback: briefly show ✓ then revert
        var copied by rememberCopyFeedback()

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
                    val isRtl = remember(message.content) { BidiUtils.isRtlText(message.content) }
                    val bubbleDirection = if (isRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
                    CompositionLocalProvider(LocalLayoutDirection provides bubbleDirection) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            SelectionContainer {
                                Text(
                                    text = highlightedText,
                                    color = userBubbleTextColor,
                                    style =
                                        MaterialTheme.typography.bodyMedium.copy(
                                            textDirection =
                                                if (isRtl) {
                                                    TextDirection.Rtl
                                                } else {
                                                    TextDirection.Ltr
                                                },
                                        ),
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
                                        openingPath = openingAttachmentPath,
                                        canSave = canSaveAttachment,
                                        onImageClick = onImageClick,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                            if (!message.isStreaming) {
                                FlowRow(
                                    modifier =
                                        Modifier
                                            .align(Alignment.End)
                                            .padding(top = 4.dp),
                                    itemVerticalAlignment = Alignment.CenterVertically,
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
                                    if (messageStatsEnabled && showUserMessageTokens &&
                                        message.tokenCount != null && message.tokenCount > 0
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "•",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = userBubbleTextColor.copy(alpha = 0.4f),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text =
                                                    stringResource(
                                                        R.string.chat_msg_tokens,
                                                        TokenEstimator.formatTokenCount(message.tokenCount),
                                                    ),
                                                color = userBubbleTextColor.copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.testTag("bubble_token_count"),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
