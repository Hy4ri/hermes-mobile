package com.m57.hermescontrol.ui.bots.group

import android.content.ClipData
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.m57.hermescontrol.R
import com.m57.hermescontrol.theme.LocalChatFontScale
import com.m57.hermescontrol.ui.chat.ChatInputPolicy
import com.m57.hermescontrol.ui.chat.MarkdownText
import com.m57.hermescontrol.ui.chat.components.ChatScrollToBottomFab
import com.m57.hermescontrol.ui.chat.components.rememberChatScrollController
import com.m57.hermescontrol.ui.chat.components.tailContentKey
import com.m57.hermescontrol.ui.chat.formatTimestamp
import com.m57.hermescontrol.ui.common.BotAvatar
import com.m57.hermescontrol.ui.common.EmptyState
import com.m57.hermescontrol.ui.common.HermesScaffold
import com.m57.hermescontrol.ui.common.NavIcon
import com.m57.hermescontrol.util.BidiUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun GroupChatScreen(
    groupName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GroupChatViewModel = viewModel { GroupChatViewModel() },
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val scrollController = rememberChatScrollController(listState, scrollScope)
    var inputFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var isFocused by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val handleSend = {
        val text = inputFieldValue.text
        if (text.isNotBlank()) {
            scrollController.jumpToBottom()
            viewModel.sendMessage(text)
            inputFieldValue = TextFieldValue("")
        }
    }

    LaunchedEffect(groupName) {
        viewModel.setGroup(groupName)
    }

    LaunchedEffect(Unit) {
        scrollController.observeUserScrollPosition()
    }

    val streamingMessage = state.messages.find { it.isStreaming }
    val tailKey =
        remember(
            state.messages.size,
            streamingMessage?.id,
            streamingMessage?.text?.length,
            state.activeSpeaker,
        ) {
            tailContentKey(
                messages = state.messages,
                streamingMessage = streamingMessage?.text,
                isThinking = state.activeSpeaker != null,
                subagentIndicators = emptyList<Any>(),
                clarifyRequest = null,
            )
        }

    LaunchedEffect(tailKey) {
        scrollController.onTailChanged(
            tailKey = tailKey,
            messageCount = state.messages.size,
        )
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
        actions = {
            if (state.activeSpeaker != null) {
                IconButton(
                    onClick = { viewModel.stopGeneration() },
                    modifier = Modifier.testTag("group_chat_stop_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.group_chat_action_stop),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(
                onClick = { showSettingsDialog = true },
                modifier = Modifier.testTag("group_chat_settings_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.group_chat_action_settings),
                )
            }
        },
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

                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                    ) {
                        CompositionLocalProvider(LocalDensity provides chatDensity) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
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
                                        val thinkingText = stringResource(R.string.group_chat_thinking, speaker)
                                        val isThinkingRtl = remember(thinkingText) { BidiUtils.isRtlText(thinkingText) }
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
                                                text = thinkingText,
                                                style =
                                                    MaterialTheme.typography.bodySmall.copy(
                                                        textDirection =
                                                            if (isThinkingRtl) {
                                                                TextDirection.Rtl
                                                            } else {
                                                                TextDirection.Ltr
                                                            },
                                                    ),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        ChatScrollToBottomFab(
                            show = !scrollController.isFollowingBottom && state.messages.isNotEmpty(),
                            pendingCount = scrollController.pendingCount,
                            onScrollToBottom = { scrollController.resumeFollowing() },
                        )
                    }
                }
            }

            // ── MENTION SUGGESTION CHIP BAR (Horizontal scrollable chips) ──
            val mentionQuery =
                remember(inputFieldValue.text, inputFieldValue.selection.end) {
                    ChatInputPolicy.extractMentionQuery(
                        inputFieldValue.text,
                        inputFieldValue.selection.end,
                    )
                }

            val groupBots =
                remember(state.members) {
                    state.members.distinctBy { it.name.lowercase() }
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
                                    inputFieldValue = ChatInputPolicy.applyMention(inputFieldValue, "all")
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
                                inputFieldValue = ChatInputPolicy.applyMention(inputFieldValue, bot.name)
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
                    onValueChange = { inputFieldValue = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .onFocusChanged { isFocused = it.isFocused }
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
                    keyboardActions = KeyboardActions(onSend = { handleSend() }),
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
                                                            state.groupName.ifBlank { groupName },
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

                                    if (state.activeSpeaker != null) {
                                        IconButton(
                                            onClick = { viewModel.stopGeneration() },
                                            colors =
                                                IconButtonDefaults.filledTonalIconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                                ),
                                            modifier =
                                                Modifier
                                                    .size(36.dp)
                                                    .testTag("group_chat_stop_button"),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Stop,
                                                contentDescription = stringResource(R.string.group_chat_action_stop),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    } else {
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
                    },
                )
            }
        }
    }

    if (showSettingsDialog) {
        GroupChatSettingsDialog(
            currentMaxMessages = state.maxBotMessages,
            currentMaxPasses = state.maxContinuationPasses,
            currentSystemPrompt = state.systemPrompt,
            onDismiss = { showSettingsDialog = false },
            onSave = { maxMsgs, maxPasses, prompt ->
                viewModel.updateGroupLimits(maxMsgs, maxPasses, prompt)
                showSettingsDialog = false
            },
        )
    }
}

@Composable
private fun GroupChatSettingsDialog(
    currentMaxMessages: Int,
    currentMaxPasses: Int,
    currentSystemPrompt: String?,
    onDismiss: () -> Unit,
    onSave: (Int, Int, String?) -> Unit,
) {
    var maxMessages by remember(currentMaxMessages) { mutableStateOf(currentMaxMessages.toFloat()) }
    var maxPasses by remember(currentMaxPasses) { mutableStateOf(currentMaxPasses.toFloat()) }
    var systemPrompt by remember(currentSystemPrompt) { mutableStateOf(currentSystemPrompt.orEmpty()) }
    var showHighLimitsConfirmation by remember { mutableStateOf(false) }

    val isHighLimit =
        maxMessages.roundToInt() > WARN_MAX_BOT_MESSAGES ||
            maxPasses.roundToInt() > WARN_MAX_CONTINUATION_PASSES

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.group_chat_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Max Bot Messages Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.group_chat_settings_max_messages),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${maxMessages.roundToInt()}",
                            style = MaterialTheme.typography.labelLarge,
                            color =
                                if (maxMessages.roundToInt() > WARN_MAX_BOT_MESSAGES) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text =
                            stringResource(
                                R.string.group_chat_settings_max_messages_desc,
                                maxMessages.roundToInt(),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = maxMessages,
                        onValueChange = { maxMessages = it },
                        valueRange = 1f..MAX_ALLOWED_BOT_MESSAGES.toFloat(),
                        steps = MAX_ALLOWED_BOT_MESSAGES - 2,
                        modifier = Modifier.testTag("max_messages_slider"),
                    )
                }

                // Max Continuation Handoffs Slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.group_chat_settings_max_handoffs),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${maxPasses.roundToInt()}",
                            style = MaterialTheme.typography.labelLarge,
                            color =
                                if (maxPasses.roundToInt() > WARN_MAX_CONTINUATION_PASSES) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        text =
                            stringResource(
                                R.string.group_chat_settings_max_handoffs_desc,
                                maxPasses.roundToInt(),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = maxPasses,
                        onValueChange = { maxPasses = it },
                        valueRange = 0f..MAX_ALLOWED_CONTINUATION_PASSES.toFloat(),
                        steps = MAX_ALLOWED_CONTINUATION_PASSES - 1,
                        modifier = Modifier.testTag("max_handoffs_slider"),
                    )
                }

                // Live In-Dialog Warning Banner (Option C)
                AnimatedVisibility(
                    visible = isHighLimit,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("high_limits_warning_banner"),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text =
                                    stringResource(
                                        R.string.group_chat_settings_high_limits_warning_desc,
                                        maxMessages.roundToInt(),
                                        maxPasses.roundToInt(),
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // Room Instructions / System Prompt
                Column {
                    Text(
                        text = stringResource(R.string.group_chat_settings_system_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.group_chat_settings_system_prompt_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.group_chat_settings_system_prompt_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 160.dp)
                                .testTag("room_system_prompt_input"),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isHighLimit) {
                        showHighLimitsConfirmation = true
                    } else {
                        onSave(
                            maxMessages.roundToInt(),
                            maxPasses.roundToInt(),
                            systemPrompt.trim().ifBlank { null },
                        )
                    }
                },
                modifier = Modifier.testTag("save_settings_button"),
            ) {
                Text(stringResource(R.string.group_chat_settings_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        maxMessages = DEFAULT_MAX_BOT_MESSAGES.toFloat()
                        maxPasses = DEFAULT_MAX_CONTINUATION_PASSES.toFloat()
                        systemPrompt = ""
                    },
                ) {
                    Text(stringResource(R.string.group_chat_settings_reset_default))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )

    if (showHighLimitsConfirmation) {
        AlertDialog(
            onDismissRequest = { showHighLimitsConfirmation = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.group_chat_settings_high_limits_warning_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text =
                        stringResource(
                            R.string.group_chat_settings_high_limits_warning_desc,
                            maxMessages.roundToInt(),
                            maxPasses.roundToInt(),
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showHighLimitsConfirmation = false
                        onSave(
                            maxMessages.roundToInt(),
                            maxPasses.roundToInt(),
                            systemPrompt.trim().ifBlank { null },
                        )
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    modifier = Modifier.testTag("confirm_high_limits_button"),
                ) {
                    Text(stringResource(R.string.group_chat_settings_high_limits_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showHighLimitsConfirmation = false },
                    modifier = Modifier.testTag("dismiss_high_limits_button"),
                ) {
                    Text(stringResource(R.string.group_chat_settings_high_limits_adjust))
                }
            },
        )
    }
}

@Composable
private fun GroupMessageCard(
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

@Composable
private fun GroupChatToolChip(
    tool: GroupChatToolCall,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    val isTerminal =
        tool.name.equals("terminal", ignoreCase = true) ||
            tool.name.equals("execute_code", ignoreCase = true)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        border =
            BorderStroke(
                1.dp,
                if (tool.isError) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                },
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val icon =
                    when (tool.name.lowercase()) {
                        "terminal", "execute_code" -> Icons.Filled.Terminal
                        "web_search", "search_files" -> Icons.Filled.Search
                        "read_file", "write_file", "patch" -> Icons.Filled.Description
                        else -> Icons.Filled.Build
                    }
                Icon(
                    imageVector = icon,
                    contentDescription = tool.name,
                    modifier = Modifier.size(13.dp),
                    tint =
                        if (tool.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
                val label =
                    if (!tool.summary.isNullOrBlank()) {
                        "${tool.name}: ${tool.summary}"
                    } else {
                        tool.name
                    }
                val isLabelRtl = remember(label) { BidiUtils.isRtlText(label) }
                Text(
                    text = label,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            textDirection = if (isLabelRtl) TextDirection.Rtl else TextDirection.Ltr,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (tool.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!tool.command.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = if (isTerminal) "$ ${tool.command}" else tool.command,
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            textDirection = TextDirection.Ltr,
                                        ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }

                    val outputText =
                        when {
                            tool.isRunning -> stringResource(R.string.group_chat_tool_running)
                            !tool.output.isNullOrBlank() -> tool.output.trim()
                            tool.exitCode == 0 -> stringResource(R.string.group_chat_tool_no_output_success)
                            else -> stringResource(R.string.group_chat_tool_no_output)
                        }
                    val isOutputRtl = remember(outputText) { BidiUtils.isRtlText(outputText) }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            Text(
                                text = outputText,
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        textDirection =
                                            if (isOutputRtl) {
                                                TextDirection.Rtl
                                            } else {
                                                TextDirection.Ltr
                                            },
                                    ),
                                color =
                                    if (tool.isError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                maxLines = 15,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
