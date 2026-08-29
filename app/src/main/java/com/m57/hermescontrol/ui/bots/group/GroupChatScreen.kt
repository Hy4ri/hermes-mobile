package com.m57.hermescontrol.ui.bots.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalChatFontScale
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon

@Composable
fun GroupChatScreen(
    groupName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupChatViewModel = viewModel { GroupChatViewModel() },
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var inputFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var isFocused by remember { mutableStateOf(false) }

    val handleSend = {
        val text = inputFieldValue.text
        if (text.isNotBlank()) {
            viewModel.sendMessage(text)
            inputFieldValue = TextFieldValue("")
        }
    }

    LaunchedEffect(groupName) {
        viewModel.setGroup(groupName)
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    HermesScaffold(
        title = {
            Column {
                Text(
                    text = state.groupName.ifBlank { groupName },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (state.members.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.group_chat_members_count, state.members.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = NavIcon.Back(onBack),
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(bottom = 8.dp)
                    .imePadding(),
        ) {
            when {
                state.isLoading -> {
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                }

                state.messages.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.group_chat_empty_title),
                        subtitle = stringResource(R.string.group_chat_empty_subtitle),
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                    )
                }

                else -> {
                    val currentDensity = LocalDensity.current
                    val chatFontScale = LocalChatFontScale.current
                    val chatDensity =
                        remember(currentDensity, chatFontScale) {
                            Density(
                                density = currentDensity.density,
                                fontScale = currentDensity.fontScale * chatFontScale,
                            )
                        }

                    CompositionLocalProvider(LocalDensity provides chatDensity) {
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = state.messages,
                                key = { it.id },
                            ) { message ->
                                GroupMessageCard(message = message)
                            }

                            state.activeSpeaker?.let { speaker ->
                                item(key = "active_speaker") {
                                    Row(
                                        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.group_chat_thinking, speaker),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── COMPOSER (uses TextFieldValue + embedded Send button matching ChatComposer) ──
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(20.dp),
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
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (inputFieldValue.text.isEmpty()) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.group_chat_composer_hint,
                                        state.groupName.ifBlank { groupName },
                                    ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        BasicTextField(
                            value = inputFieldValue,
                            onValueChange = { inputFieldValue = it },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 24.dp, max = 120.dp)
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .testTag("group_chat_input"),
                            textStyle =
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            singleLine = false,
                            maxLines = 4,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { handleSend() }),
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = handleSend,
                        enabled = inputFieldValue.text.isNotBlank(),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(),
                        modifier =
                            Modifier
                                .size(36.dp)
                                .testTag("group_chat_send_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupMessageCard(
    message: GroupChatMessage,
    modifier: Modifier = Modifier,
) {
    if (message.isSystem) {
        Box(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    } else if (message.isUser) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    } else {
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
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (message.isStreaming && message.text.isBlank()) {
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
                        MarkdownText(
                            text = message.text,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
