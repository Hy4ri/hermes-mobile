package com.m57.hermescontrol.ui.bots.group.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ProfileInfo
import com.m57.hermescontrol.ui.chat.ChatInputPolicy
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.util.BidiUtils

@Composable
fun GroupChatComposer(
    inputFieldValue: TextFieldValue,
    onInputValueChange: (TextFieldValue) -> Unit,
    groupBots: List<ProfileInfo>,
    groupName: String,
    activeSpeaker: String?,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mentionQuery =
        remember(inputFieldValue.text, inputFieldValue.selection) {
            ChatInputPolicy.extractMentionQuery(
                inputFieldValue.text,
                inputFieldValue.selection.end,
            )
        }

    val showAllOption =
        remember(mentionQuery, groupBots) {
            if (mentionQuery == null || groupBots.isEmpty()) {
                false
            } else {
                "all".startsWith(mentionQuery, ignoreCase = true) ||
                    "everyone".startsWith(mentionQuery, ignoreCase = true)
            }
        }

    val filteredBots =
        remember(mentionQuery, groupBots) {
            if (mentionQuery == null) {
                emptyList()
            } else {
                groupBots.filter { bot ->
                    bot.name.startsWith(mentionQuery, ignoreCase = true) ||
                        bot.effectiveTitle.contains(mentionQuery, ignoreCase = true)
                }
            }
        }

    AnimatedVisibility(
        visible = mentionQuery != null && (showAllOption || filteredBots.isNotEmpty()),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        LazyRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .testTag("group_chat_mention_row"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAllOption) {
                item(key = "mention_all") {
                    SuggestionChip(
                        onClick = {
                            onInputValueChange(ChatInputPolicy.applyMention(inputFieldValue, "all"))
                        },
                        label = {
                            Text(
                                text = "@all",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Groups,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("mention_chip_all"),
                    )
                }
            }

            items(filteredBots, key = { it.name }) { bot ->
                SuggestionChip(
                    onClick = {
                        onInputValueChange(ChatInputPolicy.applyMention(inputFieldValue, bot.name))
                    },
                    label = {
                        Text(
                            text = "@${bot.name}",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    icon = {
                        BotAvatar(
                            name = bot.name,
                            avatar = bot.botMeta()?.avatar,
                            size = 18.dp,
                            showPresence = false,
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("mention_chip_${bot.name}"),
                )
            }
        }
    }

    // ── COMPOSER (uses TextFieldValue + embedded Send button matching ChatComposer) ──
    val ambientLayoutDirection = LocalLayoutDirection.current
    val inputLayoutDirection =
        remember(inputFieldValue.text, ambientLayoutDirection) {
            BidiUtils.resolveLayoutDirection(inputFieldValue.text, fallback = ambientLayoutDirection)
        }
    val isInputRtl = inputLayoutDirection == LayoutDirection.Rtl

    CompositionLocalProvider(LocalLayoutDirection provides inputLayoutDirection) {
        BasicTextField(
            value = inputFieldValue,
            onValueChange = onInputValueChange,
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .testTag("group_chat_input"),
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = if (isInputRtl) TextAlign.Right else TextAlign.Left,
                    textDirection = if (isInputRtl) TextDirection.Rtl else TextDirection.Ltr,
                ),
            singleLine = false,
            maxLines = 4,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            decorationBox = { innerTextField ->
                CompositionLocalProvider(LocalLayoutDirection provides ambientLayoutDirection) {
                    Surface(
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
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.weight(1f),
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
                                            text =
                                                stringResource(
                                                    R.string.group_chat_composer_hint,
                                                    groupName,
                                                ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color =
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                    alpha = 0.6f,
                                                ),
                                            textAlign = if (isInputRtl) TextAlign.Right else TextAlign.Left,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    innerTextField()
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            if (activeSpeaker != null) {
                                IconButton(
                                    onClick = onStop,
                                    colors =
                                        IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                        ),
                                    modifier =
                                        Modifier
                                            .size(36.dp)
                                            .testTag("group_chat_composer_stop_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Stop,
                                        contentDescription = stringResource(R.string.group_chat_action_stop),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = onSend,
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
            },
        )
    }
}
