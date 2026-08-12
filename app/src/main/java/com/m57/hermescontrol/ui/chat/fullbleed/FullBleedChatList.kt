package com.m57.hermescontrol.ui.chat.fullbleed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatSearchState
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.ImageViewerModel
import com.m57.hermescontrol.ui.chat.ToolCallDivider
import com.m57.hermescontrol.ui.chat.components.ChatScrollController
import com.m57.hermescontrol.ui.chat.components.ClarifyBubble
import com.m57.hermescontrol.ui.chat.components.ReasoningCard
import com.m57.hermescontrol.ui.chat.toolCallMilestones
import com.m57.hermescontrol.ui.common.EmptyState

/**
 * The chat message list for FULL-BLEED style (issue #866) — the single chat
 * surface since the bubble renderer was removed. User messages keep their
 * bubble (the universal anchor), agent turns render full-bleed with a turn
 * header, and tool rows / system events render as distinct compact cards.
 * Spacing contract:
 * - intra-turn: entries separated by 6.dp (Column padding on agent turn items)
 * - inter-turn: 12.dp bottom padding after each turn's last item
 *
 * COMPOSE GOTCHA (verified): LazyColumn `item {}` content lambdas execute
 * LAZILY at item-composition time, not during this DSL-building loop. Loop
 * locals that are read inside item lambdas must be captured as immutable
 * vals FIRST (eagerly), or every item sees the loop's final value — the
 * turn header would never render and milestones would be misindexed.
 */
@Composable
fun FullBleedChatList(
    messages: List<ChatMessage>,
    streamingMessage: ChatMessage?,
    searchState: ChatSearchState,
    typingEffectEnabled: Boolean,
    typingEffectDelayMs: Int,
    maxToolCallsPerTurn: Int? = null,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    isDark: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scrollController: ChatScrollController,
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
        val turns =
            remember(messages, streamingMessage) {
                groupIntoTurnsWithStreaming(messages, streamingMessage)
            }
        // messageId → LazyColumn item index. The lazy list has EXTRA items
        // vs the message list (reasoning hoists, tool rows), so search-match
        // scrolling must resolve the lazy index, not use the message index.
        val lazyIndexById =
            remember(turns, isLoadingOlder) {
                messageIdToLazyIndex(turns, leadingItems = if (isLoadingOlder) 1 else 0)
            }

        // Scroll the current search match into view, word-focused. Lives here
        // (not in ChatLifecycleEffects) because only this composable knows
        // the message-id → lazy-item-index mapping. Reads search fields in
        // the effect (not the body), so only this effect restarts on change.
        LaunchedEffect(
            searchState.isActive,
            searchState.currentIndex,
            searchState.matchIndices,
            searchState.matchOffsets,
        ) {
            if (searchState.isActive &&
                searchState.currentIndex >= 0 &&
                searchState.currentIndex < searchState.matchIndices.size
            ) {
                val messageIndex = searchState.matchIndices[searchState.currentIndex]
                if (messageIndex < 0 || messageIndex >= messages.size) return@LaunchedEffect
                val lazyIndex = lazyIndexById[messages[messageIndex].id] ?: return@LaunchedEffect
                val contentOffset = searchState.matchOffsets.getOrElse(searchState.currentIndex) { 0 }
                val contentLength = messages[messageIndex].content.length
                scrollController.scrollToSearchMatch(lazyIndex, contentOffset, contentLength)
            }
        }

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
                        // Eager captures: item lambda reads these at
                        // composition time (lazy), so capture now.
                        val userMessage = turn.message
                        val milestone = toolMilestones[entryIndex]
                        item(key = "user-${userMessage.id}") {
                            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                                renderChatBubble(
                                    message = userMessage,
                                    searchQuery = if (searchState.isActive) searchState.query else "",
                                    isCurrentMatch =
                                        searchState.currentMatchId != null &&
                                            searchState.currentMatchId == userMessage.id,
                                    onOpenAttachment = viewModel::openAttachment,
                                    onSaveAttachment = onSaveAttachment,
                                    savingAttachmentPath = savingAttachmentPath,
                                    onImageClick = onImageClick,
                                )
                                milestone?.let { count ->
                                    ToolCallDivider(count = count, maxPerTurn = maxToolCallsPerTurn)
                                }
                            }
                        }
                        entryIndex++
                    }

                    is ChatTurn.Agent -> {
                        var firstProseSeen = false
                        // Reasoning hoist: the turn's reasoning renders at the
                        // TOP of the turn — above tool rows — so thinking
                        // leads, then the tool work, then the answer. The
                        // matching prose entry renders without its own card.
                        val turnReasoning =
                            turn.entries
                                .filterIsInstance<AgentEntry.Prose>()
                                .firstOrNull { it.message.reasoningText.isNotBlank() }
                        if (turnReasoning != null) {
                            val reasoning = turnReasoning.message
                            item(key = "reasoning-${reasoning.id}") {
                                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                    // Lead the turn with the assistant identity
                                    // and timestamp, then the thinking block.
                                    AssistantTurnHeader(timestamp = reasoning.timestamp)
                                    ReasoningCard(
                                        reasoningText = reasoning.reasoningText,
                                        isStreaming = reasoning.isStreaming,
                                    )
                                }
                            }
                        }
                        turn.entries.forEach { entry ->
                            when (entry) {
                                is AgentEntry.Prose -> {
                                    val proseMessage = entry.message
                                    val showTurnHeader = !firstProseSeen && turnReasoning == null
                                    val hoistedReasoning =
                                        turnReasoning != null &&
                                            proseMessage.id == turnReasoning.message.id
                                    val milestone = toolMilestones[entryIndex]
                                    item(key = "prose-${proseMessage.id}") {
                                        Column(modifier = Modifier.padding(bottom = 12.dp)) {
                                            if (proseMessage.isStreaming && typingEffectEnabled) {
                                                StreamingFullBleedWithTypingEffect(
                                                    streaming = proseMessage,
                                                    typingDelayMs = typingEffectDelayMs,
                                                    isDark = isDark,
                                                    showTurnHeader = showTurnHeader,
                                                    showReasoning = !hoistedReasoning,
                                                )
                                            } else {
                                                FullBleedAgentMessage(
                                                    message = proseMessage,
                                                    showTurnHeader = showTurnHeader,
                                                    isDarkTheme = isDark,
                                                    // Highlight only bubbles that actually contain a match —
                                                    // the rest skip the highlight scan entirely.
                                                    searchQuery =
                                                        if (searchState.isActive &&
                                                            proseMessage.id in searchState.matchedIds
                                                        ) {
                                                            searchState.query
                                                        } else {
                                                            ""
                                                        },
                                                    isCurrentMatch =
                                                        searchState.currentMatchId != null &&
                                                            searchState.currentMatchId == proseMessage.id,
                                                    showReasoning = !hoistedReasoning,
                                                    onOpenAttachment = viewModel::openAttachment,
                                                    onSaveAttachment = onSaveAttachment,
                                                    savingAttachmentPath = savingAttachmentPath,
                                                    canSaveAttachment = savingAttachmentPath == null,
                                                    onImageClick = onImageClick,
                                                )
                                            }
                                            milestone?.let { count ->
                                                ToolCallDivider(count = count, maxPerTurn = maxToolCallsPerTurn)
                                            }
                                        }
                                    }
                                    firstProseSeen = true
                                    entryIndex++
                                }

                                is AgentEntry.ToolRow -> {
                                    val toolMessage = entry.message
                                    val milestone = toolMilestones[entryIndex]
                                    item(key = "tool-${toolMessage.id}") {
                                        Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                            FullBleedToolRow(toolMessage)
                                            milestone?.let { count ->
                                                ToolCallDivider(count = count, maxPerTurn = maxToolCallsPerTurn)
                                            }
                                        }
                                    }
                                    entryIndex++
                                }

                                is AgentEntry.SystemEvent -> {
                                    val sysMessage = entry.message
                                    item(key = "sys-${sysMessage.id}") {
                                        Column(modifier = Modifier.padding(bottom = 6.dp)) {
                                            FullBleedSystemEvent(
                                                message = sysMessage,
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
    searchQuery: String,
    isCurrentMatch: Boolean,
    onOpenAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit,
    onSaveAttachment: (com.m57.hermescontrol.data.model.Attachment) -> Unit,
    savingAttachmentPath: String?,
    onImageClick: (ImageViewerModel) -> Unit,
) {
    ChatBubble(
        message = message,
        searchQuery = searchQuery,
        isCurrentMatch = isCurrentMatch,
        onOpenAttachment = onOpenAttachment,
        onSaveAttachment = onSaveAttachment,
        savingAttachmentPath = savingAttachmentPath,
        canSaveAttachment = savingAttachmentPath == null,
        onImageClick = onImageClick,
    )
}
