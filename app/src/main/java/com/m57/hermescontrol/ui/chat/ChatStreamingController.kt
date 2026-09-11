package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds chat message-streaming state and logic, extracted from [ChatViewModel]
 * to keep the god-object focused on messaging/session concerns.
 *
 * Behavior is identical to the original inline implementation: it owns the
 * streaming buffers and the two token/delta handlers, and mutates the shared
 * [uiState] + [streamingState] flows. The owning ViewModel keeps [streamingState]
 * as the single source of truth for the reduced [StreamingState] (applied via the
 * WS event reducer); this controller only writes into it for buffer-driven flushes.
 *
 * [isCurrentSession] and [isTestEnvironment] are injected so the controller
 * stays free of ViewModel-specific context while preserving exact behavior.
 * [scope] mirrors the [com.m57.hermescontrol.ui.chat.ChatSearchDelegate] seam
 * and is reserved for future streaming coroutine work.
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

    private val streamingBuffer = java.lang.StringBuilder()
    private var lastFlushMs = 0L
    private var trailingMessageFlushJob: Job? = null

    private val thinkingBuffer = java.lang.StringBuilder()
    private var lastThinkingFlushMs = 0L
    private var trailingThinkingFlushJob: Job? = null

    private val reasoningBuffer = java.lang.StringBuilder()
    private var lastReasoningFlushMs = 0L
    private var trailingReasoningFlushJob: Job? = null

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /**
     * Resets all streaming buffers. Centralizes the clear logic that was
     * previously scattered across MessageStart, MessageComplete, MessageDone,
     * ToolStart, ClarifyRequest, session switches, and interrupt handling.
     */
    fun resetStreaming() {
        trailingMessageFlushJob?.cancel()
        trailingThinkingFlushJob?.cancel()
        trailingReasoningFlushJob?.cancel()
        flushReasoning()
        streamingBuffer.clear()
        thinkingBuffer.clear()
        reasoningBuffer.clear()
        lastFlushMs = 0L
        lastThinkingFlushMs = 0L
        lastReasoningFlushMs = 0L
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
     * themselves are drained (flushPendingReasoning) before reduce, so a
     * buffer-only clear is safe and prevents stale deltas from re-flushing.
     */
    fun clearStreamingBuffers() {
        trailingMessageFlushJob?.cancel()
        trailingThinkingFlushJob?.cancel()
        trailingReasoningFlushJob?.cancel()
        streamingBuffer.clear()
        thinkingBuffer.clear()
        reasoningBuffer.clear()
        lastFlushMs = 0L
        lastThinkingFlushMs = 0L
        lastReasoningFlushMs = 0L
    }

    /** Resets buffers and starts a fresh streaming message (called on MessageStart). */
    fun beginStreamingMessage() {
        resetStreaming()
    }

    /**
     * Flushes any throttled reasoning buffer before a state-transition event
     * (MessageStart / MessageComplete / MessageDone / ToolStart). Without this,
     * a reasoning delta that arrived just before a transition could be dropped
     * because the next flush only fires on the ~33ms timer — so we force it out
     * synchronously so the finalized message carries the latest reasoning.
     */
    fun flushPendingReasoning() {
        trailingReasoningFlushJob?.cancel()
        flushReasoning()
    }

    /**
     * Force-flushes any throttled TOKEN buffer onto the streaming message
     * before a state-transition event (ToolStart / MessageComplete /
     * MessageDone). `handleMessageToken` only flushes when >=33ms have passed
     * since the last flush, so a delta that lands just before the transition
     * can still be sitting in the buffer. The reducer seals the streaming
     * message into a finalized orphan at tool.start (issue #842) — sealing a
     * stale copy truncated the narration's tail (observed on-device: the
     * orphan ended "...chickpea" while the REST row ended "...chickpea
     * goodness:"), which then failed the logical-match against the clean
     * REST row and re-added the commentary as a ghost bubble. Flushing the
     * tokens synchronously first makes the sealed orphan complete.
     */
    fun flushPendingTokens() {
        trailingMessageFlushJob?.cancel()
        val currentContent = streamingBuffer.toString()
        if (currentContent.isEmpty()) return
        lastFlushMs = nowMs()
        streamingState.update { state ->
            val current = state.streamingMessage ?: return@update state
            state.copy(
                streamingMessage =
                    current.copy(
                        content = currentContent,
                        reasoningText =
                            if (current.reasoningText.isNotBlank()) current.reasoningText else state.reasoningText,
                    ),
            )
        }
    }

    fun handleMessageToken(event: WsEvent.MessageToken) {
        if (!isCurrentSession(event.sessionId)) return

        streamingBuffer.append(event.token)
        val now = nowMs()

        // Always flush in tests, or if enough time has passed
        val shouldFlush = (now - lastFlushMs >= FLUSH_INTERVAL_MS) || lastFlushMs == 0L || isTestEnvironment()
        if (shouldFlush) {
            trailingMessageFlushJob?.cancel()
            flushTokens(now)
        } else {
            scheduleTrailingMessageFlush()
        }
    }

    private fun scheduleTrailingMessageFlush() {
        if (trailingMessageFlushJob?.isActive == true) return
        trailingMessageFlushJob =
            scope.launch {
                delay(FLUSH_INTERVAL_MS)
                flushTokens(nowMs())
            }
    }

    private fun flushTokens(now: Long) {
        val currentContent = streamingBuffer.toString()
        if (currentContent.isEmpty()) return
        lastFlushMs = now
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

        thinkingBuffer.append(event.token)
        val now = nowMs()

        // Always flush in tests, or if enough time has passed
        val shouldFlush =
            (now - lastThinkingFlushMs >= FLUSH_INTERVAL_MS) || lastThinkingFlushMs == 0L || isTestEnvironment()
        if (shouldFlush) {
            trailingThinkingFlushJob?.cancel()
            flushThinking(now)
        } else {
            scheduleTrailingThinkingFlush()
        }
    }

    private fun scheduleTrailingThinkingFlush() {
        if (trailingThinkingFlushJob?.isActive == true) return
        trailingThinkingFlushJob =
            scope.launch {
                delay(FLUSH_INTERVAL_MS)
                flushThinking(nowMs())
            }
    }

    private fun flushThinking(now: Long) {
        val currentContent = thinkingBuffer.toString()
        if (currentContent.isEmpty()) return
        lastThinkingFlushMs = now
        streamingState.update { state ->
            state.copy(
                isThinking = true,
                thinkingText = currentContent,
            )
        }
    }

    fun handleReasoningDelta(event: WsEvent.ReasoningDelta) {
        if (!isCurrentSession(event.sessionId)) return

        reasoningBuffer.append(event.token)
        val now = nowMs()

        // Always flush in tests, or if enough time has passed
        val shouldFlush =
            (now - lastReasoningFlushMs >= FLUSH_INTERVAL_MS) || lastReasoningFlushMs == 0L || isTestEnvironment()
        if (shouldFlush) {
            trailingReasoningFlushJob?.cancel()
            flushReasoning(now)
        } else {
            scheduleTrailingReasoningFlush()
        }
    }

    private fun scheduleTrailingReasoningFlush() {
        if (trailingReasoningFlushJob?.isActive == true) return
        trailingReasoningFlushJob =
            scope.launch {
                delay(FLUSH_INTERVAL_MS)
                flushReasoning(nowMs())
            }
    }

    private fun flushReasoning(now: Long = nowMs()) {
        val currentContent = reasoningBuffer.toString()
        if (currentContent.isEmpty()) return
        lastReasoningFlushMs = now
        streamingState.update { state ->
            state.copy(
                isReasoning = true,
                reasoningText = currentContent,
                streamingMessage = state.streamingMessage?.copy(reasoningText = currentContent),
            )
        }
    }
}
