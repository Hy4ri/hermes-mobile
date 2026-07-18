package com.m57.hermescontrol.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.SubagentIndicator
import com.m57.hermescontrol.ui.common.EmptyState

/**
 * The chat message list.
 *
 * Lay children out vertically so the search bar occupies real layout space
 * ABOVE the message list. Without this container the call site is a Box,
 * which overlays the LazyColumn on top of the search AnimatedVisibility and
 * swallows every tap on the bar (bar visible but not clickable).
 */
@Composable
fun ChatMessageList(
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
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    isDark: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    lastAnimatedMessageId: String?,
    onLastAnimatedMessageIdChange: (String?) -> Unit,
    viewModel: ChatViewModel,
    subagentIndicators: List<SubagentIndicator> = emptyList(),
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Search bar
        AnimatedVisibility(
            visible = isSearchActive,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp,
                border =
                    BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                    ),
            ) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    SearchBarRow(
                        searchQuery = searchQuery,
                        onQueryChange = { viewModel.setSearchQuery(it) },
                        searchMatchCount = searchMatchIndices.size,
                        currentMatchIndex = currentSearchMatchIndex,
                        onNavigateUp = { viewModel.navigateSearchMatch(-1) },
                        onNavigateDown = { viewModel.navigateSearchMatch(1) },
                        onClose = { viewModel.clearSearch() },
                    )
                }
            }
        }

        if (messages.isEmpty() && !isLoading) {
            EmptyState(
                title = stringResource(R.string.chat_empty_title),
                subtitle = stringResource(R.string.chat_empty_subtitle),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
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
            itemsIndexed(
                items = messages,
                key = { _, message -> message.id },
            ) { index, message ->
                val isCurrentMatch =
                    isSearchActive &&
                        currentSearchMatchIndex >= 0 &&
                        currentSearchMatchIndex < searchMatchIndices.size &&
                        searchMatchIndices[currentSearchMatchIndex] == index

                val isLastMessage = index == messages.lastIndex
                val isAssistant = message.role == MessageRole.ASSISTANT

                if (isAssistant && message.reasoningText.isNotBlank()) {
                    ReasoningIndicator(message.reasoningText)
                }

                if (typingEffectEnabled && isLastMessage && isAssistant && message.isStreaming &&
                    lastAnimatedMessageId != message.id
                ) {
                    StreamingBubbleWithTypingEffect(
                        streaming = message,
                        typingDelayMs = typingEffectDelayMs,
                        isDark = isDark,
                        onAnimationComplete = {
                            onLastAnimatedMessageIdChange(message.id)
                        },
                    )
                } else {
                    ChatBubble(
                        message = message,
                        isDarkTheme = isDark,
                        searchQuery = if (isSearchActive) searchQuery else "",
                        isCurrentMatch = isCurrentMatch,
                        onRespondApproval = viewModel::respondToApproval,
                    )
                }
            }

            // Streaming message
            streamingMessage?.let { streaming ->
                item(key = "streaming-${streaming.id}") {
                    if (streaming.reasoningText.isNotBlank()) {
                        ReasoningIndicator(streaming.reasoningText)
                    }
                    if (typingEffectEnabled && streaming.isStreaming) {
                        StreamingBubbleWithTypingEffect(
                            streaming = streaming,
                            typingDelayMs = typingEffectDelayMs,
                            isDark = isDark,
                        )
                    } else {
                        ChatBubble(
                            message = streaming,
                            isDarkTheme = isDark,
                            searchQuery = "",
                            isCurrentMatch = false,
                        )
                    }
                }
            }

            // Thinking indicator
            if (isThinking) {
                item(key = "thinking") {
                    ThinkingIndicator(thinkingText)
                }
            }

            // Subagent indicators
            items(
                items = subagentIndicators,
                key = { indicator -> "subagent-${indicator.subagentId ?: indicator.goal ?: indicator.type}" },
            ) { indicator ->
                SubagentIndicatorRow(indicator = indicator)
            }
        }
    }
}
