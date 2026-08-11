package com.m57.hermescontrol.ui.chat.fullbleed

import android.content.ClipData
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ImageViewerModel
import com.m57.hermescontrol.ui.chat.InlineAttachment
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.chat.components.ReasoningCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-bleed renderer for ONE agent (assistant) message (issue #866).
 *
 * Unlike [com.m57.hermescontrol.ui.chat.ChatBubble], agent prose renders
 * directly on the background — no bubble container, no width cap — with a
 * turn header (role + time) and a trailing copy affordance. User messages
 * keep their bubbles; this composable is only used for ASSISTANT messages.
 *
 * [showTurnHeader] renders the role/time header — true only for the first
 * prose entry of an agent turn so multi-message turns don't repeat it.
 */
@Composable
internal fun FullBleedAgentMessage(
    message: ChatMessage,
    showTurnHeader: Boolean,
    isDarkTheme: Boolean,
    searchQuery: String = "",
    isCurrentMatch: Boolean = false,
    showReasoning: Boolean = true,
    onOpenAttachment: (Attachment) -> Unit = {},
    onSaveAttachment: (Attachment) -> Unit = {},
    savingAttachmentPath: String? = null,
    canSaveAttachment: Boolean = true,
    onImageClick: (ImageViewerModel) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
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

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("fullbleed_agent_message"),
    ) {
        if (showTurnHeader) {
            AssistantTurnHeader(message.timestamp)
        }

        if (showReasoning && message.reasoningText.isNotBlank()) {
            ReasoningCard(
                reasoningText = message.reasoningText,
                isStreaming = message.isStreaming,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        SelectionContainer {
            MarkdownText(
                text = message.content,
                textColor = textColor,
                isStreaming = message.isStreaming,
                searchQuery = searchQuery,
                isCurrentMatch = isCurrentMatch,
                onImageClick = onImageClick,
            )
        }

        // Render inline attachments (mirrors ChatBubble so agent-delivered
        // media — images, files — shows in full-bleed mode too).
        if (!message.attachments.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            message.attachments.forEach { attachment ->
                InlineAttachment(
                    attachment = attachment,
                    textColor = textColor,
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
        }
    }
}

/**
 * Agent turn header — "Agent · <time>". Shared by the prose message and the
 * hoisted reasoning block so both can lead with the assistant identity and
 * timestamp.
 */
@Composable
internal fun AssistantTurnHeader(
    timestamp: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(bottom = 4.dp).testTag("fullbleed_agent_header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.fullbleed_role_agent),
            style =
                MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            // Meta color (not primary): Nord's light mode reuses its
            // pastel Frost accent as primary, which fails >= 3:1 on a
            // light background. The header is metadata, so the dimmed
            // meta token is both more correct and gate-compliant in
            // every preset.
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text =
                com.m57.hermescontrol.ui.chat.formatTimestamp(
                    timestamp,
                    DateFormat.is24HourFormat(LocalContext.current),
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
