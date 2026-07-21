package com.m57.hermescontrol.ui.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.chat.ChatBubble
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatViewModel
import com.m57.hermescontrol.ui.chat.ClarifyUi
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
    clarifyRequest: ClarifyUi? = null,
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

            // Clarify request as inline bubble
            if (clarifyRequest != null) {
                item(key = "clarify") {
                    ClarifyBubble(
                        text = clarifyRequest.text,
                        options = clarifyRequest.options,
                        onOptionSelected = viewModel::respondToClarify,
                        onDismiss = viewModel::dismissClarify,
                    )
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
                SubagentCard(indicator = indicator)
            }

            // Typing indicator when assistant is still streaming
            if (streamingMessage?.isStreaming == true) {
                item(key = "typing") {
                    TypingIndicator()
                }
            }
        }
    }
}
