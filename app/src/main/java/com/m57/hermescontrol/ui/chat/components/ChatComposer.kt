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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
 * The chat input bar with attach menu, slash-command dropdown,
 * attachment preview chips, and mic/send/stop button swapping.
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
) {
    // Allow sending while the agent is mid-turn or awaiting approval: the
    // gateway's prompt.submit busy-input policy queues it as the next turn
    // (tui_gateway/server.py:_handle_busy_submit), so the message is never
    // dropped. Slash commands were already allowed; regular prompts now are too.
    val canSend = ChatInputPolicy.canSend(inputFieldValue.text, pendingAttachments, isConnected)

    // Attachment menu state
    var showAttachmentMenu by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            shape = RoundedCornerShape(24.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ),
            border =
                BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
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
                                androidx.compose.foundation.BorderStroke(
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

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        // Single attach button that opens a dropdown menu
                        IconButton(
                            onClick = { showAttachmentMenu = true },
                            enabled = isConnected,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = stringResource(R.string.chat_attach_desc),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

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

                    OutlinedTextField(
                        value = inputFieldValue,
                        onValueChange = onInputChange,
                        modifier =
                            Modifier
                                .weight(1f)
                                .heightIn(min = 36.dp, max = 120.dp)
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                                .testTag("chat_input"),
                        placeholder = {
                            Text(
                                if (!isConnected) {
                                    stringResource(R.string.chat_input_placeholder_not_connected)
                                } else if (ChatInputPolicy.showQueuePlaceholder(inputFieldValue.text, isAgentTyping)) {
                                    stringResource(R.string.chat_input_placeholder_queue)
                                } else if (isAgentTyping) {
                                    stringResource(R.string.chat_input_placeholder_waiting)
                                } else {
                                    stringResource(R.string.chat_input_placeholder_type_message)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        enabled = isConnected,
                        singleLine = false,
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                cursorColor = MaterialTheme.colorScheme.primary,
                            ),
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Mic / Stop / Send button — swaps based on input state
                    AnimatedContent(
                        targetState =
                            when {
                                isListening -> "listening"
                                inputFieldValue.text.isBlank() && pendingAttachments.isEmpty() -> "mic"
                                else -> "send"
                            },
                        transitionSpec = {
                            (scaleIn(initialScale = 0.8f) + fadeIn())
                                .togetherWith(scaleOut(targetScale = 0.8f) + fadeOut())
                        },
                        label = "mic_send_toggle",
                    ) { buttonState ->
                        when (buttonState) {
                            "mic" -> {
                                FilledTonalButton(
                                    onClick = onMicTap,
                                    enabled = isConnected,
                                    modifier =
                                        Modifier
                                            .size(40.dp)
                                            .testTag("mic_button"),
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = stringResource(R.string.chat_mic_desc),
                                    )
                                }
                            }

                            "listening" -> {
                                FilledTonalButton(
                                    onClick = onMicTap,
                                    modifier =
                                        Modifier
                                            .size(40.dp)
                                            .testTag("mic_stop_button"),
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(0.dp),
                                    colors =
                                        ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                        ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Stop,
                                        contentDescription = "Stop listening",
                                        tint = MaterialTheme.colorScheme.onError,
                                    )
                                }
                            }

                            else -> {
                                FilledTonalButton(
                                    onClick = onSend,
                                    enabled = canSend,
                                    modifier =
                                        Modifier
                                            .size(40.dp)
                                            .testTag("send_button"),
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(0.dp),
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
            }
        }
    }
}

/**
 * Compact chip showing a pending attachment with a remove button.
 */
@Composable
fun AttachmentChip(
    attachment: Attachment,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (attachment.isImage) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = attachment.name,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
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
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(18.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.chat_attach_remove_desc),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
