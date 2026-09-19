package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.data.ws.CommandBlocklist
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.ui.chat.ChatInputPolicy
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.util.BidiUtils

/**
 * The chat input bar: a single rounded card with the input on top and a
 * controls row (attach, model/reasoning pill, mic, send) inside it below.
 */
@Composable
fun ChatInputBar(
    inputFieldValue: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onMicTap: () -> Unit,
    isListening: Boolean,
    isAgentTyping: Boolean,
    isConnected: Boolean,
    commandCatalog: CommandCatalog,
    isSessionReady: Boolean,
    sessionPreparationFailed: Boolean = false,
    slashUsageCounts: Map<String, Int> = emptyMap(),
    pendingAttachments: List<Attachment> = emptyList(),
    onCameraTap: () -> Unit = {},
    onImageTap: () -> Unit = {},
    onFileTap: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onPreviewAttachment: (Attachment) -> Unit = {},
    // NEW: composer toolbar wiring
    currentSessionModel: String? = null,
    reasoningLevel: String? = null,
    onModelTap: () -> Unit = {},
    onReasoningTap: (String?) -> Unit = {},
    canDisableReasoning: Boolean? = null,
    supportsReasoning: Boolean? = null,
    fastMode: Boolean = false,
    fastSupported: Boolean = false,
    isFastModeChanging: Boolean = false,
    onToggleFastMode: () -> Unit = {},
    showModelProvider: Boolean = false,
) {
    // Allow sending while the agent is mid-turn or awaiting approval: the
    // gateway's prompt.submit busy-input policy queues it as the next turn
    // (tui_gateway/server.py:_handle_busy_submit), so the message is never
    // dropped. Slash commands were already allowed; regular prompts now are too.
    val canSend = ChatInputPolicy.canSend(inputFieldValue.text, pendingAttachments, isConnected, isSessionReady)

    // Attachment tray state
    var showAttachmentTray by remember { mutableStateOf(false) }
    val palette = composerPalette()

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        // One floating card holds the whole composer: suggestions, attachments,
        // the input and the controls row. Flat fill, hairline edge, no shadow.
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.large,
            color = palette.card,
            border = BorderStroke(width = 1.dp, color = palette.cardBorder),
        ) {
            Column(modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)) {
                // Commands hidden from the suggestion menu — desktop/CLI-only and
                // TUI-only commands that don't function on mobile (issue #574).
                // Single source of truth: CommandBlocklist.UNSUPPORTED, which is
                // also enforced at dispatch time (issue #576, deliverable #3).
                val hiddenSlashDisplay = CommandBlocklist.UNSUPPORTED
                val commandNames =
                    (commandCatalog.pairs.map { it[0] } + listOf("/btw", "/queue", "/fork", "/model", "/new", "/stop"))
                        .distinct()
                        .filter { it.lowercase() !in hiddenSlashDisplay }

                androidx.compose.animation.AnimatedVisibility(
                    visible = inputFieldValue.text.startsWith("/") && !inputFieldValue.text.contains(" "),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                ) {
                    val filteredCommands =
                        ChatInputPolicy.sortSlashSuggestions(
                            commandNames.filter { it.startsWith(inputFieldValue.text, ignoreCase = true) },
                            slashUsageCounts,
                        )
                    if (filteredCommands.isNotEmpty()) {
                        androidx.compose.material3.Surface(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border =
                                BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                ),
                        ) {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 200.dp),
                            ) {
                                items(filteredCommands, key = { it }) { cmd ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                cmd,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        },
                                        onClick = { onInputChange(ChatInputPolicy.commandFieldValue(cmd)) },
                                    )
                                }
                            }
                        }
                    }
                }

                // Attachment preview chips
                AnimatedVisibility(
                    visible = pendingAttachments.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    LazyRow(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(pendingAttachments) { index, attachment ->
                            AttachmentChip(
                                attachment = attachment,
                                onPreview = { onPreviewAttachment(attachment) },
                                onRemove = { onRemoveAttachment(index) },
                            )
                        }
                    }
                }

                if (isConnected && !isSessionReady) {
                    Text(
                        text =
                            stringResource(
                                if (sessionPreparationFailed) {
                                    R.string.chat_session_not_ready
                                } else {
                                    R.string.chat_session_preparing
                                },
                            ),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.placeholder,
                        modifier = Modifier.padding(horizontal = 20.dp).testTag("chat_session_preparing"),
                    )
                }

                // ── TOP ROW: Borderless input field ──
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val placeholderText =
                        when {
                            !isConnected -> {
                                stringResource(R.string.chat_input_placeholder_not_connected)
                            }

                            ChatInputPolicy.showQueuePlaceholder(inputFieldValue.text, isAgentTyping) -> {
                                stringResource(R.string.chat_input_placeholder_queue)
                            }

                            isAgentTyping -> {
                                stringResource(R.string.chat_input_placeholder_waiting)
                            }

                            else -> {
                                stringResource(R.string.chat_input_placeholder_type_message)
                            }
                        }

                    val ambientLayoutDirection = LocalLayoutDirection.current
                    val inputLayoutDirection =
                        remember(inputFieldValue.text, ambientLayoutDirection) {
                            BidiUtils.resolveLayoutDirection(inputFieldValue.text, fallback = ambientLayoutDirection)
                        }
                    val isInputRtl = inputLayoutDirection == LayoutDirection.Rtl

                    CompositionLocalProvider(LocalLayoutDirection provides inputLayoutDirection) {
                        BasicTextField(
                            value = inputFieldValue,
                            onValueChange = onInputChange,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 42.dp, max = 200.dp)
                                    .padding(vertical = 4.dp)
                                    .testTag("chat_input"),
                            enabled = isConnected,
                            textStyle =
                                MaterialTheme.typography.bodyLarge.copy(
                                    color = palette.text,
                                    textAlign = if (isInputRtl) TextAlign.Right else TextAlign.Left,
                                    textDirection = if (isInputRtl) TextDirection.Rtl else TextDirection.Ltr,
                                ),
                            singleLine = false,
                            maxLines = 8,
                            cursorBrush = SolidColor(palette.text),
                            decorationBox = { innerTextField ->
                                CompositionLocalProvider(LocalLayoutDirection provides ambientLayoutDirection) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment =
                                            if (isInputRtl) {
                                                Alignment.CenterEnd
                                            } else {
                                                Alignment.CenterStart
                                            },
                                    ) {
                                        CompositionLocalProvider(LocalLayoutDirection provides inputLayoutDirection) {
                                            if (inputFieldValue.text.isEmpty()) {
                                                Text(
                                                    text = placeholderText,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    textAlign = if (isInputRtl) TextAlign.Right else TextAlign.Left,
                                                    color = palette.placeholder,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                }
                            },
                        )
                    }
                }

                // ── BOTTOM ROW: Toolbar ──
                ComposerToolbar(
                    isConnected = isConnected,
                    currentSessionModel = currentSessionModel,
                    reasoningLevel = reasoningLevel,
                    isListening = isListening,
                    canSend = canSend,
                    showSend = inputFieldValue.text.isNotBlank() || pendingAttachments.isNotEmpty(),
                    onSend = onSend,
                    onAttachTap = { showAttachmentTray = !showAttachmentTray },
                    onModelTap = onModelTap,
                    onReasoningSelected = onReasoningTap,
                    onMicTap = onMicTap,
                    modifier = Modifier.testTag("chat_composer_toolbar"),
                    canDisableReasoning = canDisableReasoning,
                    supportsReasoning = supportsReasoning,
                    fastMode = fastMode,
                    fastSupported = fastSupported,
                    isFastModeChanging = isFastModeChanging,
                    onToggleFastMode = onToggleFastMode,
                    showModelProvider = showModelProvider,
                )

                AttachmentTray(
                    visible = showAttachmentTray,
                    onDismissRequest = { showAttachmentTray = false },
                    onCameraTap = onCameraTap,
                    onImageTap = onImageTap,
                    onFileTap = onFileTap,
                )
            }
        }
    }
}

/**
 * Reusable attachment chip composable for showing a pending attachment
 * with a remove button.
 */
@Composable
fun AttachmentChip(
    attachment: Attachment,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnail = attachment.uri
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (attachment.isImage) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = attachment.name,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onPreview)
                            .testTag("attachment_preview"),
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = attachment.name,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(18.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.chat_attach_remove_desc),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
