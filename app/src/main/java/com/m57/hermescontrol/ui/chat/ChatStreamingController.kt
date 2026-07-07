package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds chat message-streaming state and logic, extracted from [ChatViewModel]
 * to keep the god-object focused on messaging/session concerns.
 *
 * Behavior is identical to the original inline implementation: it owns the
 * streaming buffers, the currently-streaming message id, and the two
 * token/delta handlers, and mutates the shared [uiState] + [streamingState]
 * flows. The owning ViewModel keeps [streamingState] as the single source of
 * truth for the reduced [StreamingState] (applied via the WS event reducer);
 * this controller only writes into it for buffer-driven flushes.
 *
 * [isCurrentSession] and [isTestEnvironment] are injected so the controller
 * stays free of ViewModel-specific context while preserving exact behavior.
 */
class ChatStreamingController(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val streamingState: MutableStateFlow<StreamingState>,
    private val isCurrentSession: (String?) -> Boolean,
    private val isTestEnvironment: () -> Boolean,
) {
    /** Tracks the ID of the currently streaming assistant message. */
    private var streamingMessageId: String? = null

    private val streamingBuffer = java.lang.StringBuilder()
    private var lastFlushMs = 0L

    private val thinkingBuffer = java.lang.StringBuilder()
    private var lastThinkingFlushMs = 0L

    /**
     * Resets all streaming buffers and the in-flight message id. Centralizes
     * the clear logic that was previously scattered across MessageStart,
     * MessageComplete, MessageDone, ToolStart, ClarifyRequest, session
     * switches, and interrupt handling.
     */
    fun resetStreaming() {
        streamingMessageId = null
        streamingBuffer.clear()
        thinkingBuffer.clear()
        lastFlushMs = 0L
        lastThinkingFlushMs = 0L
    }

    /** Generates a fresh streaming message id (called on MessageStart). */
    fun beginStreamingMessage() {
        streamingMessageId =
            java.util.UUID
                .randomUUID()
                .toString()
        streamingBuffer.clear()
        thinkingBuffer.clear()
        lastFlushMs = 0L
        lastThinkingFlushMs = 0L
    }

    fun handleMessageToken(event: WsEvent.MessageToken) {
        if (!isCurrentSession(event.sessionId)) return

        streamingBuffer.append(event.token)
        val now = System.currentTimeMillis()

        // Always flush in tests, or if enough time has passed
        val shouldFlush = (now - lastFlushMs >= 33L) || lastFlushMs == 0L || isTestEnvironment()
        if (shouldFlush) {
            val currentContent = streamingBuffer.toString()
            lastFlushMs = now
            streamingState.update { state ->
                val current = state.streamingMessage
                if (current != null) {
                    state.copy(
                        streamingMessage =
                            current.copy(
                                content = currentContent,
                            ),
                        isThinking = false,
                    )
                } else {
                    // Fallback: no MessageStart was received — create one now
                    val msg =
                        ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = currentContent,
                            isStreaming = true,
                        )
                    streamingMessageId = msg.id
                    state.copy(
                        streamingMessage = msg,
                        isThinking = false,
                    )
                }
            }
            uiState.update { it.copy(isAgentTyping = true) }
        }
    }

    fun handleThinkingDelta(event: WsEvent.ThinkingDelta) {
        if (!isCurrentSession(event.sessionId)) return

        thinkingBuffer.append(event.token)
        val now = System.currentTimeMillis()

        // Always flush in tests, or if enough time has passed
        val shouldFlush =
            (now - lastThinkingFlushMs >= 33L) || lastThinkingFlushMs == 0L || isTestEnvironment()
        if (shouldFlush) {
            val currentContent = thinkingBuffer.toString()
            lastThinkingFlushMs = now
            streamingState.update { state ->
                state.copy(
                    isThinking = true,
                    thinkingText = currentContent,
                )
            }
        }
    }
}
