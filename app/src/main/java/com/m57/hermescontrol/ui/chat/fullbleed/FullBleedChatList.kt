package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.ImageViewerModel
import com.m57.hermescontrol.ui.chat.ToolCallDivider
import com.m57.hermescontrol.ui.chat.toolCallMilestones
import com.m57.hermescontrol.ui.chat.components.ClarifyBubble
import com.m57.hermescontrol.ui.chat.components.TypingIndicator
import com.m57.hermescontrol.ui.common.EmptyState

/**
 * The chat message list for FULL-BLEED style (issue #866).
 *
 * Parallel renderer to [com.m57.hermescontrol.ui.chat.components.ChatMessageList]:
 * user messages keep their bubble (the universal anchor), agent turns render
 * full-bleed with a turn header, and tool rows / system events render as
 * distinct compact cards. Spacing contract:
 * - intra-turn: entries separated by 6.dp (Column padding on agent turn items)
 * - inter-turn: 12.dp bottom padding after each turn's last item
 */
@Composable
fun FullBleedChatList(
    messages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
    isThinking: Boolean,
    thinkingText: String,
    isSearchActive: Boolean,
    searchQuery: String,
    currentSearchMatchIndex: Int,
    searchMatchIndices: List<Int>,
    typingEffectEnabled: Boolean,
    typingEffectDelayMs: Int,
    maxToolCallsPerTurn: Int? = null,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    isDark: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    lastAnimatedMessageId: String?,
    onLastAnimatedMessageIdChange: (String?) -> Unit,
    viewModel: ChatViewModel,
    clarifyRequest: ClarifyUi? = null,
    onRespondClarify: ((String) -> Unit)? = null,
    onDismissClarify: (() -> Unit)? = null,
    onSaveAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit = {},
    savingAttachmentPath: String? = null,
    onImageClick: (ImageViewerModel) -> Unit = {},
) {
    if (messages.isEmpty() && !isLoading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                title = stringResource(R.string.chat_empty_title),
                subtitle = stringResource(R.string.chat_empty_subtitle),
            )
        }
    } else {
        val toolMilestones = toolCallMilestones(messages)
        val turns = remember(messages) { groupIntoTurns(messages) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (isLoadingOlder) {
                item(key = "loading-older") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }

            var entryIndex = 0
            turns.forEach { turn ->
                when (turn) {
                    is ChatTurn.User -> {
                        item(key = "user-${turn.message.id}") {
                            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                                renderChatBubble(
                                    message = turn.message,
                                    isDark = isDark,
                                    searchQuery = searchQuery,
                                    isCurrentMatch = isCurrentMatchFor(messages, turn.message.id, isSearchActive, currentSearchMatchIndex, searchMatchIndices),
                                    onOpenAttachment = viewModel::openAttachment,
                                    onSaveAttachment = onSaveAttachment,
                                    savingAttachmentPath = savingAttachmentPath,
                                    onImageClick = onImageClick,
                                )
                                toolMilestones[entryIndex]?.let { count ->
                                    ToolCallDivider(count = count, maxPerTurn = maxToolCallsPerTurn)
                                }
                            }
                        }
                        entryIndex++
                    }

                    is ChatTurn.Agent -> {
                        var firstProseSeen = false
                        turn.entries.forEach { entry ->
                            when (entry) {
                                is AgentEntry.Prose -> {
                                    item(key = "prose-${entry.message.id}") {
                                        Column(modifier = Modifier.padding(bottom = 12.dp)) {
                                            FullBleedAgentMessage(
                                                message = entry.message,
                                                showTurnHeader = !firstProseSeen,
                                                isDarkTheme = isDark,
                                                searchQuery = if (isSearchActive) searchQuery else "",
                                                isCurrentMatch = isCurrentMatchFor(messages, entry.message.id, isSearchActive, currentSearchMatchIndex, searchMatchIndices),
                                                onOpenAttachment = viewModel::openAttachment,
                                                onSaveAttachment = onSaveAttachment,
                                                savingAttachmentPath = savingAttachmentPath,
                                                canSaveAttachment = savingAttachmentPath == null,
                                                onImageClick = onImageClick,
                                            )
                                            toolMilestones[entryIndex]?.let { count ->
                                                ToolCallDivider(count = count, maxPerTurn = maxToolCallsPerTurn)
                                            }
                                        }
                                    }
                                    firstProseSeen = true
                                    entryIndex++
                                }

                                is AgentEntry.ToolRow -> {
                                    item(key = "tool-${entry.message.id}") {
                                        Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                            FullBleedToolRow(entry.message)
                                            toolMilestones[entryIndex]?.let { count ->
                                                ToolCallDivider(count = count, maxPerTurn = maxToolCallsPerTurn)
                                            }
                                        }
                                    }
                                    entryIndex++
                                }

                                is AgentEntry.SystemEvent -> {
                                    item(key = "sys-${entry.message.id}") {
                                        Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                            FullBleedSystemEvent(
                                                message = entry.message,
                                                onRespondApproval = viewModel::respondToApproval,
                                            )
                                        }
                                    }
                                    entryIndex++
                                }
                            }
                        }
                    }
                }
            }

            // Streaming message
            streamingMessage?.let { streaming ->
                item(key = "streaming-${streaming.id}") {
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        if (typingEffectEnabled && streaming.isStreaming) {
                            StreamingFullBleedWithTypingEffect(
                                streaming = streaming,
                                typingDelayMs = typingEffectDelayMs,
                                isDark = isDark,
                            )
                        } else {
                            FullBleedAgentMessage(
                                message = streaming,
                                showTurnHeader = true,
                                isDarkTheme = isDark,
                                searchQuery = "",
                                isCurrentMatch = false,
                                onOpenAttachment = viewModel::openAttachment,
                                onSaveAttachment = onSaveAttachment,
                                savingAttachmentPath = savingAttachmentPath,
                                canSaveAttachment = savingAttachmentPath == null,
                                onImageClick = onImageClick,
                            )
                        }
                    }
                }
            }

            // Typing indicator — bouncing dots
            if (isThinking) {
                item(key = "typing_indicator") {
                    TypingIndicator()
                }
            }

            // Clarify bubble — rendered at the very bottom
            if (clarifyRequest != null) {
                item(key = "clarify_bubble") {
                    ClarifyBubble(
                        text = clarifyRequest.text,
                        options = clarifyRequest.options,
                        onOptionSelected = { option -> onRespondClarify?.invoke(option) },
                        onDismiss = { onDismissClarify?.invoke() },
                    )
                }
            }
        }
    }
}

@Composable
private fun renderChatBubble(
    message: ChatMessage,
    isDark: Boolean,
    searchQuery: String,
    isCurrentMatch: Boolean,
    onOpenAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit,
    onSaveAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit,
    savingAttachmentPath: String?,
    onImageClick: (ImageViewerModel) -> Unit,
) {
    ChatBubble(
        message = message,
        isDarkTheme = isDark,
        searchQuery = searchQuery,
        isCurrentMatch = isCurrentMatch,
        onOpenAttachment = onOpenAttachment,
        onSaveAttachment = onSaveAttachment,
        savingAttachmentPath = savingAttachmentPath,
        canSaveAttachment = savingAttachmentPath == null,
        onImageClick = onImageClick,
    )
}

private fun isCurrentMatchFor(
    messages: List<ChatMessage>,
    messageId: String,
    isSearchActive: Boolean,
    currentSearchMatchIndex: Int,
    searchMatchIndices: List<Int>,
): Boolean {
    if (!isSearchActive || currentSearchMatchIndex < 0 || currentSearchMatchIndex >= searchMatchIndices.size) {
        return false
    }
    val index = messages.indexOfFirst { it.id == messageId }
    if (index < 0) return false
    return searchMatchIndices[currentSearchMatchIndex] == index
}
