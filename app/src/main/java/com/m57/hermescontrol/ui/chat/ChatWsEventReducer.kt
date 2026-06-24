package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent

/**
 * Pure state reducer for WebSocket events.
 *
 * Transforms [ChatUiState] in response to each [WsEvent] and returns a
 * [ReducerResult] containing the new state plus any side-effects the
 * ViewModel should execute (persistence, navigation, etc.).
 *
 * This is a pure function — no I/O, no mutable state, no dependencies on
 * Android or ViewModel classes. Easy to unit test.
 */
object ChatWsEventReducer {
    fun reduce(
        state: ChatUiState,
        event: WsEvent,
        currentSessionId: String? = null,
    ): ReducerResult {
        val eventSessionId =
            when (event) {
                is WsEvent.MessageStart -> event.sessionId
                is WsEvent.MessageToken -> event.sessionId
                is WsEvent.ThinkingDelta -> event.sessionId
                is WsEvent.MessageComplete -> event.sessionId
                is WsEvent.MessageDone -> event.sessionId
                is WsEvent.ToolStart -> event.sessionId
                is WsEvent.ToolComplete -> event.sessionId
                is WsEvent.ClarifyRequest -> event.sessionId
                else -> null
            }
        if (eventSessionId != null && currentSessionId != null && eventSessionId != currentSessionId) {
            return ReducerResult(state)
        }
        return reduceInternal(state, event)
    }

    private fun reduceInternal(
        state: ChatUiState,
        event: WsEvent,
    ): ReducerResult =
        when (event) {
            is WsEvent.GatewayReady -> onGatewayReady(state)

            is WsEvent.MessageStart -> onMessageStart(state, event)

            is WsEvent.MessageToken -> onMessageToken(state, event)

            is WsEvent.ThinkingDelta -> onThinkingDelta(state, event)

            is WsEvent.MessageComplete -> onMessageComplete(state, event)

            is WsEvent.MessageDone -> onMessageDone(state)

            is WsEvent.ToolStart -> onToolStart(state, event)

            is WsEvent.ToolComplete -> onToolComplete(state, event)

            is WsEvent.ClarifyRequest -> onClarifyRequest(state, event)

            is WsEvent.RpcError -> onRpcError(state, event)

            is WsEvent.SessionUpdated -> onSessionUpdated(state)

            is WsEvent.StatusUpdate -> onStatusUpdate(state)

            is WsEvent.Unknown -> onUnknown(state)

            // SessionInfo is a no-op in the original code
            is WsEvent.SessionInfo -> ReducerResult(state)

            // RpcResult is handled by the ViewModel (needs pending request context)
            is WsEvent.RpcResult -> ReducerResult(state)
        }

    // ── GatewayReady ──────────────────────────────────────────────────

    private fun onGatewayReady(state: ChatUiState): ReducerResult = ReducerResult(state)

    // ── MessageStart ──────────────────────────────────────────────────

    private fun onMessageStart(
        state: ChatUiState,
        event: WsEvent.MessageStart,
    ): ReducerResult {
        val msg =
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                isStreaming = true,
            )
        val effects = mutableListOf<ReducerEffect>()

        // Build new state: finalize any orphan streaming message, then set the new one
        var orphan: ChatMessage? = null
        val preState =
            if (state.streamingMessage?.content?.isNotEmpty() == true) {
                val finalized = state.streamingMessage!!.copy(isStreaming = false)
                orphan = finalized
                state.copy(
                    messages = state.messages + finalized,
                    streamingMessage = null,
                    isAgentTyping = true,
                    isThinking = false,
                    thinkingText = "",
                )
            } else {
                state.copy(
                    isAgentTyping = true,
                    isThinking = false,
                    thinkingText = "",
                )
            }
        val newState = preState.copy(streamingMessage = msg)
        val sid = newState.currentSessionId
        if (orphan != null && sid != null) {
            effects.add(ReducerEffect.PersistMessage(orphan, sid))
        }

        return ReducerResult(newState, effects)
    }

    // ── MessageToken ──────────────────────────────────────────────────

    private fun onMessageToken(
        state: ChatUiState,
        event: WsEvent.MessageToken,
    ): ReducerResult {
        // Token accumulation is done by the ViewModel (streaming buffer + flush timer).
        // The reducer only signals that new content arrived — ViewModel owns the timer.
        return ReducerResult(state)
    }

    // ── ThinkingDelta ─────────────────────────────────────────────────

    private fun onThinkingDelta(
        state: ChatUiState,
        event: WsEvent.ThinkingDelta,
    ): ReducerResult {
        val currentContent = state.thinkingText + event.token
        return ReducerResult(
            state =
                state.copy(
                    isThinking = true,
                    thinkingText = currentContent,
                ),
        )
    }

    // ── MessageComplete ───────────────────────────────────────────────

    private fun onMessageComplete(
        state: ChatUiState,
        event: WsEvent.MessageComplete,
    ): ReducerResult {
        val streaming = state.streamingMessage
        val msg =
            streaming?.copy(
                content = event.text ?: streaming.content,
                isStreaming = false,
            ) ?: ChatMessage(
                role = MessageRole.ASSISTANT,
                content = event.text ?: "",
            )
        val effects = mutableListOf<ReducerEffect>()
        val sid = state.currentSessionId
        if (sid != null) {
            effects.add(ReducerEffect.PersistMessage(msg, sid))
        }
        effects.add(ReducerEffect.RefreshSessions)
        return ReducerResult(
            state =
                state.copy(
                    messages = state.messages + msg,
                    streamingMessage = null,
                    isAgentTyping = false,
                    isThinking = false,
                    thinkingText = "",
                ),
            effects = effects,
        )
    }

    // ── MessageDone ───────────────────────────────────────────────────

    private fun onMessageDone(state: ChatUiState): ReducerResult {
        val streaming = state.streamingMessage ?: return ReducerResult(state)
        val msg = streaming.copy(isStreaming = false)
        val effects = mutableListOf<ReducerEffect>()
        val sid = state.currentSessionId
        if (sid != null) {
            effects.add(ReducerEffect.PersistMessage(msg, sid))
        }
        return ReducerResult(
            state =
                state.copy(
                    messages = state.messages + msg,
                    streamingMessage = null,
                    isAgentTyping = false,
                    isThinking = false,
                    thinkingText = "",
                ),
            effects = effects,
        )
    }

    // ── ToolStart ─────────────────────────────────────────────────────

    private fun onToolStart(
        state: ChatUiState,
        event: WsEvent.ToolStart,
    ): ReducerResult {
        val contentJson =
            event.data?.let {
                com.google.gson
                    .Gson()
                    .toJson(it)
            } ?: ""
        val toolMessage =
            ChatMessage(
                role = MessageRole.TOOL,
                content = contentJson,
                toolName = event.name,
                toolStatus = ToolStatus.RUNNING,
            )

        // Finalize any orphan streaming message
        var orphanToPersist: ChatMessage? = null
        val newState =
            if (state.streamingMessage?.content?.isNotEmpty() == true) {
                val finalized = state.streamingMessage!!.copy(isStreaming = false)
                orphanToPersist = finalized
                state.copy(
                    messages = state.messages + finalized,
                    streamingMessage = null,
                )
            } else {
                state.copy(streamingMessage = null)
            }
        val sid = newState.currentSessionId
        val effects = mutableListOf<ReducerEffect>()
        if (sid != null) {
            if (orphanToPersist != null) {
                effects.add(ReducerEffect.PersistMessage(orphanToPersist, sid))
            }
            effects.add(ReducerEffect.PersistMessage(toolMessage, sid))
        }

        return ReducerResult(
            state = newState.copy(messages = newState.messages + toolMessage),
            effects = effects,
        )
    }

    // ── ToolComplete ──────────────────────────────────────────────────

    private fun onToolComplete(
        state: ChatUiState,
        event: WsEvent.ToolComplete,
    ): ReducerResult {
        val contentJson =
            event.data?.let {
                com.google.gson
                    .Gson()
                    .toJson(it)
            } ?: ""
        val messages = state.messages.toMutableList()
        val toolIdx =
            messages.indexOfLast {
                it.role == MessageRole.TOOL &&
                    it.toolName == event.name &&
                    it.toolStatus == ToolStatus.RUNNING
            }
        if (toolIdx < 0) return ReducerResult(state)

        val updated =
            messages[toolIdx].copy(
                toolStatus = ToolStatus.COMPLETED,
                content = contentJson,
            )
        messages[toolIdx] = updated

        val effects = mutableListOf<ReducerEffect>()
        val sid = state.currentSessionId
        if (sid != null) {
            effects.add(ReducerEffect.PersistMessage(updated, sid))
        }
        return ReducerResult(
            state = state.copy(messages = messages),
            effects = effects,
        )
    }

    // ── ClarifyRequest ────────────────────────────────────────────────

    private fun onClarifyRequest(
        state: ChatUiState,
        event: WsEvent.ClarifyRequest,
    ): ReducerResult =
        ReducerResult(
            state =
                state.copy(
                    clarifyRequest =
                        ClarifyUi(
                            text = event.text.orEmpty(),
                            options = event.options.orEmpty(),
                            clarifyId = event.clarifyId,
                        ),
                    isAgentTyping = false,
                ),
        )

    // ── RpcError ──────────────────────────────────────────────────────

    private fun onRpcError(
        state: ChatUiState,
        event: WsEvent.RpcError,
    ): ReducerResult =
        ReducerResult(
            state =
                state.copy(
                    isLoading = false,
                    errorMessage = "Error: ${event.error}",
                ),
        )

    // ── SessionUpdated / StatusUpdate / Unknown ───────────────────────

    private fun onSessionUpdated(state: ChatUiState): ReducerResult = ReducerResult(state)

    private fun onStatusUpdate(state: ChatUiState): ReducerResult = ReducerResult(state)

    private fun onUnknown(state: ChatUiState): ReducerResult = ReducerResult(state)

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * If there's a non-empty streaming message, finalize it into the message
     * list and return it as an orphan that should be persisted.
     */
    private fun finalizeStreamingMessage(
        state: ChatUiState,
        newMessage: ChatMessage,
    ): Pair<ChatUiState, ChatMessage?> {
        val existing = state.streamingMessage
        if (existing != null && existing.content.isNotEmpty()) {
            val finalized = existing.copy(isStreaming = false)
            return Pair(
                state.copy(
                    messages = state.messages + finalized,
                    streamingMessage = newMessage,
                ),
                finalized,
            )
        }
        return Pair(
            state.copy(streamingMessage = newMessage),
            null,
        )
    }
}

/**
 * Result of reducing one event.
 *
 * @param state The new UI state after applying the event.
 * @param effects Side-effects the ViewModel should execute.
 */
data class ReducerResult(
    val state: ChatUiState,
    val effects: List<ReducerEffect> = emptyList(),
)

/**
 * Side-effects that the reducer cannot perform itself (needs I/O or ViewModel context).
 */
sealed class ReducerEffect {
    data class PersistMessage(
        val message: ChatMessage,
        val sessionId: String,
    ) : ReducerEffect()

    data object CreateNewSession : ReducerEffect()

    data object LoadSessions : ReducerEffect()

    data object RefreshSessions : ReducerEffect()
}
