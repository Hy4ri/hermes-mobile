package com.m57.hermescontrol.ui.chat.fullbleed

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ImageViewerModel
import com.m57.hermescontrol.ui.chat.InlineAttachment
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.chat.MessageSegment
import com.m57.hermescontrol.ui.chat.TokenEstimator
import com.m57.hermescontrol.ui.chat.components.ReasoningCard
import com.m57.hermescontrol.ui.chat.components.rememberCopyFeedback
import com.m57.hermescontrol.ui.chat.splitByMedia
import kotlinx.coroutines.launch

/**
 * Full-bleed renderer for ONE agent (assistant) message (issue #866).
 *
 * Unlike [com.m57.hermescontrol.ui.chat.UserBubble], agent prose renders
 * directly on the background — no bubble container, no width cap — with a
 * trailing copy affordance. User messages keep their bubbles; this composable
 * is only used for ASSISTANT messages.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FullBleedAgentMessage(
    message: ChatMessage,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    reasoningSearchQuery: String = "",
    isCurrentReasoningMatch: Boolean = false,
    reasoningSearchOffset: Int = 0,
    showReasoning: Boolean = true,
    onOpenAttachment: (Attachment) -> Unit = {},
    onSaveAttachment: (Attachment) -> Unit = {},
    savingAttachmentPath: String? = null,
    openingAttachmentPath: String? = null,
    canSaveAttachment: Boolean = true,
    onImageClick: (ImageViewerModel) -> Unit = {},
    isSpeaking: Boolean = false,
    onToggleSpeak: (() -> Unit)? = null,
    messageStatsEnabled: Boolean = false,
    showAssistantMessageTokens: Boolean = true,
    showTokensPerSecond: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Copy feedback: briefly show ✓ then revert
    var copied by rememberCopyFeedback()

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("fullbleed_agent_message"),
    ) {
        if (showReasoning && message.reasoningText.isNotBlank()) {
            ReasoningCard(
                reasoningText = message.reasoningText,
                isStreaming = message.isStreaming,
                searchQuery = reasoningSearchQuery,
                isCurrentMatch = isCurrentReasoningMatch,
                searchOffset = reasoningSearchOffset,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Defense-in-depth: never render an empty prose block (blank bubble +
        // lone Copy button). Blank rows are tool-call placeholders that slipped
        // through upstream mapping; the parent list renders the live status
        // indicator until the first visible delta lands.
        // Issue #1367: agent media renders where its `MEDIA:` directive sat in the
        // prose; attachments without a recorded position trail the text.
        val segments =
            remember(message.content, message.attachments) {
                splitByMedia(message.content, message.attachments)
            }
        segments.forEach { segment ->
            when (segment) {
                is MessageSegment.Text -> {
                    SelectionContainer {
                        MarkdownText(
                            text = segment.text,
                            textColor = textColor,
                            isStreaming = message.isStreaming,
                            searchQuery = searchQuery,
                            isCurrentMatch = isCurrentMatch,
                            onImageClick = onImageClick,
                        )
                    }
                }

                is MessageSegment.Media -> {
                    Spacer(modifier = Modifier.height(6.dp))
                    InlineAttachment(
                        attachment = segment.attachment,
                        textColor = textColor,
                        onOpen = onOpenAttachment,
                        onSave = onSaveAttachment,
                        savingPath = savingAttachmentPath,
                        openingPath = openingAttachmentPath,
                        canSave = canSaveAttachment,
                        onImageClick = onImageClick,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        if (!message.isStreaming && message.content.isNotBlank()) {
            val showTokenStat =
                messageStatsEnabled && showAssistantMessageTokens &&
                    message.tokenCount != null && message.tokenCount > 0
            val showTpsStat =
                messageStatsEnabled && showTokensPerSecond &&
                    message.tps != null && message.tps > 0.0
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, message.content)))
                        }
                        copied = true
                    },
                    modifier = Modifier.size(28.dp).testTag("fullbleed_copy"),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.content_desc_copy),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onToggleSpeak != null) {
                    IconButton(
                        onClick = onToggleSpeak,
                        modifier = Modifier.size(28.dp).testTag("fullbleed_speak"),
                    ) {
                        Icon(
                            imageVector =
                                if (isSpeaking) {
                                    Icons.Filled.Stop
                                } else {
                                    Icons.AutoMirrored.Filled.VolumeUp
                                },
                            contentDescription =
                                stringResource(
                                    if (isSpeaking) {
                                        R.string.content_desc_stop_speaking
                                    } else {
                                        R.string.content_desc_speak
                                    },
                                ),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showTokenStat) {
                    AssistantStatItem(
                        value =
                            stringResource(
                                R.string.chat_msg_tokens,
                                TokenEstimator.formatTokenCount(message.tokenCount),
                            ),
                        testTag = "fullbleed_token_count",
                    )
                }
                if (showTpsStat) {
                    AssistantStatItem(
                        value =
                            stringResource(
                                R.string.chat_msg_tps,
                                TokenEstimator.formatTps(message.tps),
                            ),
                        testTag = "fullbleed_tps",
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantStatItem(
    value: String,
    testTag: String,
) {
    Text(
        text = value,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(testTag),
    )
}
