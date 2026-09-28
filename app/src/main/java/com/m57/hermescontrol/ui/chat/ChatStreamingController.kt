package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds chat message-streaming state and logic, extracted from [ChatViewModel]
 * to keep the god-object focused on messaging/session concerns.
 *
 * Owns the three throttled streams and their synchronous transition flushes, and mutates
 * the shared [uiState] + [streamingState] flows. The owning ViewModel keeps
 * [streamingState] as the single source of truth for the reduced [StreamingState].
 * [isCurrentSession] and [isTestEnvironment] preserve the existing session
 * filtering and immediate-flush test behavior.
 */
class ChatStreamingController(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val streamingState: MutableStateFlow<StreamingState>,
    private val isCurrentSession: (String?) -> Boolean,
    private val isTestEnvironment: () -> Boolean,
) {
    private companion object {
        const val FLUSH_INTERVAL_MS = 33L
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private val tokens = StreamingBuffer(scope, FLUSH_INTERVAL_MS, ::nowMs, ::flushTokens)
    private val thinking = StreamingBuffer(scope, FLUSH_INTERVAL_MS, ::nowMs, ::flushThinking)
    private val reasoning = StreamingBuffer(scope, FLUSH_INTERVAL_MS, ::nowMs, ::flushReasoning)

    /**
     * Resets all streaming buffers. Centralizes the clear logic that was
     * previously scattered across MessageStart, MessageComplete, MessageDone,
     * ToolStart, ClarifyRequest, session switches, and interrupt handling.
     */
    fun resetStreaming() {
        reasoning.flushPending()
        tokens.clear()
        thinking.clear()
        reasoning.clear()
        // Issue #755: the shared streaming state must not carry the previous
        // message's reasoning into the next one. The finalized message keeps
        // its own copy (persisted to Room), so clearing here is safe.
        streamingState.update {
            it.copy(
                isReasoning = false,
                reasoningText = "",
                // Issue #842: sealed-orphan tracking belongs to one turn only.
                sealedOrphanIds = emptyList(),
            )
        }
    }

    /**
     * Clears ONLY the throttled token buffers (and their flush timers) —
     * never touches `streamingMessage`, `isReasoning` or `reasoningText`.
     *
     * Used at tool.start: the reducer keeps the streaming message + its
     * reasoning alive across the tool call (issue #771), and the VM must
     * not undo that by resetting the shared streaming state. The buffers
     * themselves are drained before reduce, so a
     * buffer-only clear is safe and prevents stale deltas from re-flushing.
     */
    fun clearStreamingBuffers() {
        tokens.clear()
        thinking.clear()
        reasoning.clear()
    }

    /** Resets buffers and starts a fresh streaming message (called on MessageStart). */
    fun beginStreamingMessage() {
        resetStreaming()
    }

    /** Drain throttled reasoning and tokens before the reducer seals or resets the message. */
    fun flushPendingTransition() {
        reasoning.flushPending()
        tokens.flushPending { content ->
            streamingState.update { state ->
                val current = state.streamingMessage ?: return@update state
                state.copy(
                    streamingMessage =
                        current.copy(
                            content = content,
                            reasoningText =
                                if (current.reasoningText.isNotBlank()) current.reasoningText else state.reasoningText,
                        ),
                )
            }
        }
    }

    fun handleMessageToken(event: WsEvent.MessageToken) {
        if (!isCurrentSession(event.sessionId)) return

        tokens.append(event.token, isTestEnvironment())
    }

    private fun flushTokens(currentContent: String) {
        streamingState.update { state ->
            val current = state.streamingMessage
            if (current != null) {
                val currentReasoning =
                    if (current.reasoningText.isNotBlank()) current.reasoningText else state.reasoningText
                state.copy(
                    streamingMessage =
                        current.copy(
                            content = currentContent,
                            reasoningText = currentReasoning,
                        ),
                    isThinking = false,
                )
            } else {
                // Fallback: no MessageStart was received — create one now
                val msg =
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = currentContent,
                        reasoningText = state.reasoningText,
                        isStreaming = true,
                    )
                state.copy(
                    streamingMessage = msg,
                    isThinking = false,
                )
            }
        }
        uiState.update { it.copy(isAgentTyping = true) }
    }

    fun handleThinkingDelta(event: WsEvent.ThinkingDelta) {
        if (!isCurrentSession(event.sessionId)) return

        thinking.append(event.token, isTestEnvironment())
    }

    private fun flushThinking(currentContent: String) {
        streamingState.update { state ->
            state.copy(
                isThinking = true,
                thinkingText = currentContent,
            )
        }
    }

    fun handleReasoningDelta(event: WsEvent.ReasoningDelta) {
        if (!isCurrentSession(event.sessionId)) return

        reasoning.append(event.token, isTestEnvironment())
    }

    private fun flushReasoning(currentContent: String) {
        streamingState.update { state ->
            state.copy(
                isReasoning = true,
                reasoningText = currentContent,
                streamingMessage = state.streamingMessage?.copy(reasoningText = currentContent),
            )
        }
    }
}
