package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.CommandBlocklist
import com.m57.hermescontrol.data.ws.CommandCatalog
import com.m57.hermescontrol.ui.chat.ChatInputPolicy

/**
 * The chat input bar with a two-row layout: input+send on top,
 * and a toolbar with attach/model chip/reasoning chip/mic below.
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
    pendingAttachments: List<Attachment> = emptyList(),
    onCameraTap: () -> Unit = {},
    onImageTap: () -> Unit = {},
    onFileTap: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    // NEW: composer toolbar wiring
    currentSessionModel: String? = null,
    reasoningLevel: String? = null,
    onModelTap: () -> Unit = {},
    onReasoningTap: () -> Unit = {},
) {
    // Allow sending while the agent is mid-turn or awaiting approval: the
    // gateway's prompt.submit busy-input policy queues it as the next turn
    // (tui_gateway/server.py:_handle_busy_submit), so the message is never
    // dropped. Slash commands were already allowed; regular prompts now are too.
    val canSend = ChatInputPolicy.canSend(inputFieldValue.text, pendingAttachments, isConnected)

    // Attachment menu state
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 0.dp,
        ) {
            Column {
                // Commands hidden from the suggestion menu — desktop/CLI-only and
                // TUI-only commands that don't function on mobile (issue #574).
                // Single source of truth: CommandBlocklist.UNSUPPORTED, which is
                // also enforced at dispatch time (issue #576, deliverable #3).
                val hiddenSlashDisplay = CommandBlocklist.UNSUPPORTED
                val commandNames =
                    commandCatalog.pairs
                        .map { it[0] }
                        .filter { it.lowercase() !in hiddenSlashDisplay }

                androidx.compose.animation.AnimatedVisibility(
                    visible = inputFieldValue.text.startsWith("/") && !inputFieldValue.text.contains(" "),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(),
                ) {
                    val filteredCommands =
                        commandNames.filter { it.startsWith(inputFieldValue.text, ignoreCase = true) }
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
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pendingAttachments.forEachIndexed { index, attachment ->
                            AttachmentChip(
                                attachment = attachment,
                                onRemove = { onRemoveAttachment(index) },
                            )
                        }
                    }
                }

                // ── TOP ROW: Input field with embedded send button ──
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
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

                    BasicTextField(
                        value = inputFieldValue,
                        onValueChange = onInputChange,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 42.dp, max = 120.dp)
                                .padding(vertical = 4.dp)
                                .onFocusChanged { isFocused = it.isFocused }
                                .testTag("chat_input"),
                        enabled = isConnected,
                        textStyle =
                            MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        singleLine = false,
                        maxLines = 4,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                border =
                                    BorderStroke(
                                        width = if (isFocused) 2.dp else 1.dp,
                                        color =
                                            if (isFocused) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                            },
                                    ),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier =
                                        Modifier
                                            .padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp)
                                            .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (inputFieldValue.text.isEmpty()) {
                                            Text(
                                                text = placeholderText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            )
                                        }
                                        innerTextField()
                                    }

                                    // Send button INSIDE the field
                                    AnimatedContent(
                                        targetState = canSend && inputFieldValue.text.isNotBlank(),
                                        transitionSpec = {
                                            (scaleIn(initialScale = 0.8f) + fadeIn())
                                                .togetherWith(scaleOut(targetScale = 0.8f) + fadeOut())
                                        },
                                        label = "send_toggle",
                                    ) { showSend ->
                                        if (showSend) {
                                            IconButton(
                                                onClick = onSend,
                                                enabled = canSend,
                                                colors = IconButtonDefaults.filledTonalIconButtonColors(),
                                                modifier =
                                                    Modifier
                                                        .size(36.dp)
                                                        .testTag("send_button"),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                                    contentDescription = stringResource(R.string.chat_send_desc),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    )
                }

                // ── BOTTOM ROW: Toolbar ──
                ComposerToolbar(
                    isConnected = isConnected,
                    currentSessionModel = currentSessionModel,
                    reasoningLevel = reasoningLevel,
                    isListening = isListening,
                    onAttachTap = { showAttachmentMenu = true },
                    onModelTap = onModelTap,
                    onReasoningTap = onReasoningTap,
                    onMicTap = onMicTap,
                    modifier = Modifier.testTag("chat_composer_toolbar"),
                )

                // Attachment dropdown (anchored to the attach button in ComposerToolbar)
                // Shown overlaid at the toolbar level
                DropdownMenu(
                    expanded = showAttachmentMenu,
                    onDismissRequest = { showAttachmentMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Camera") },
                        onClick = {
                            showAttachmentMenu = false
                            onCameraTap()
                        },
                        leadingIcon = {
                            Text(
                                text = "📷",
                                fontSize = 18.sp,
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Image") },
                        onClick = {
                            showAttachmentMenu = false
                            onImageTap()
                        },
                        leadingIcon = {
                            Text(
                                text = "🖼️",
                                fontSize = 18.sp,
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("File") },
                        onClick = {
                            showAttachmentMenu = false
                            onFileTap()
                        },
                        leadingIcon = {
                            Text(
                                text = "📄",
                                fontSize = 18.sp,
                            )
                        },
                    )
                }
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
                            .size(24.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(18.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
