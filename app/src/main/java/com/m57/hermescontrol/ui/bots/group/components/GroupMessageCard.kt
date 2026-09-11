package com.m57.hermescontrol.ui.bots.group.components

import android.content.ClipData
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.bots.group.CAPPED_SYSTEM_TEXT
import com.m57.hermescontrol.ui.bots.group.GroupChatMessage
import com.m57.hermescontrol.ui.bots.group.STOPPED_SYSTEM_TEXT
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.chat.formatTimestamp
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.util.BidiUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GroupMessageCard(
    message: GroupChatMessage,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    if (message.isSystem) {
        val displayText =
            when {
                message.isPass -> {
                    val name = message.senderDisplayName.ifBlank { message.senderName }
                    stringResource(R.string.group_chat_bot_passed, name)
                }

                message.text == STOPPED_SYSTEM_TEXT -> {
                    stringResource(R.string.group_chat_stopped_by_user)
                }

                message.text == CAPPED_SYSTEM_TEXT -> {
                    stringResource(R.string.group_chat_capped_limit)
                }

                else -> {
                    message.text
                }
            }
        val isSystemRtl = remember(displayText) { BidiUtils.isRtlText(displayText) }
        val systemDirection = if (isSystemRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
        Box(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (message.isPass) 0.5f else 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides systemDirection) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (message.isPass) {
                            Icon(
                                imageVector = Icons.Filled.FastForward,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                        Text(
                            text = displayText,
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    textDirection =
                                        if (isSystemRtl) {
                                            TextDirection.Rtl
                                        } else {
                                            TextDirection.Ltr
                                        },
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    } else if (message.isUser) {
        val isUserRtl = remember(message.text) { BidiUtils.isRtlText(message.text) }
        val userBubbleDirection = if (isUserRtl) LayoutDirection.Rtl else LocalLayoutDirection.current
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides userBubbleDirection) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        SelectionContainer {
                            Text(
                                text = message.text,
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        textDirection =
                                            if (isUserRtl) {
                                                TextDirection.Rtl
                                            } else {
                                                TextDirection.Ltr
                                            },
                                    ),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText(null, message.text)),
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
                                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                                )
                            }
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text =
                                    formatTimestamp(
                                        message.timestamp,
                                        DateFormat.is24HourFormat(LocalContext.current),
                                    ),
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    } else {
        val isBotNameRtl = remember(message.senderDisplayName) { BidiUtils.isRtlText(message.senderDisplayName) }
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            BotAvatar(
                name = message.senderName,
                avatar = message.avatarMeta,
                size = 32.dp,
                showPresence = false,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = message.senderDisplayName,
                        style =
                            MaterialTheme.typography.labelMedium.copy(
                                textDirection =
                                    if (isBotNameRtl) {
                                        TextDirection.Rtl
                                    } else {
                                        TextDirection.Ltr
                                    },
                            ),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (message.toolCalls.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(bottom = if (message.text.isNotBlank()) 4.dp else 0.dp),
                        ) {
                            message.toolCalls.forEach { tool ->
                                GroupChatToolChip(tool = tool)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (message.isStreaming && message.text.isBlank() && message.toolCalls.none { it.isRunning }) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.group_chat_typing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    } else if (message.text.isNotBlank()) {
                        SelectionContainer {
                            MarkdownText(
                                text = message.text,
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!message.isStreaming && message.text.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText(null, message.text)),
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text =
                                    formatTimestamp(
                                        message.timestamp,
                                        DateFormat.is24HourFormat(LocalContext.current),
                                    ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
