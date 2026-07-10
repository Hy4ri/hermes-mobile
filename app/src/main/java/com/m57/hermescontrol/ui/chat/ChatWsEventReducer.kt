package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.toJsonElement
import kotlinx.serialization.encodeToString

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
        streamingState: StreamingState,
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
            return ReducerResult(state = state, streamingState = streamingState)
        }
        return reduceInternal(state, streamingState, event)
    }

    private fun reduceInternal(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent,
    ): ReducerResult =
        when (event) {
            is WsEvent.GatewayReady -> onGatewayReady(state, streamingState)

            is WsEvent.MessageStart -> onMessageStart(state, streamingState, event)

            is WsEvent.MessageToken -> onMessageToken(state, streamingState, event)

            is WsEvent.ThinkingDelta -> onThinkingDelta(state, streamingState, event)

            is WsEvent.MessageComplete -> onMessageComplete(state, streamingState, event)

            is WsEvent.MessageDone -> onMessageDone(state, streamingState)

            is WsEvent.ToolStart -> onToolStart(state, streamingState, event)

            is WsEvent.ToolComplete -> onToolComplete(state, streamingState, event)

            is WsEvent.ClarifyRequest -> onClarifyRequest(state, streamingState, event)

            is WsEvent.RpcError -> onRpcError(state, streamingState, event)

            is WsEvent.SessionUpdated -> onSessionUpdated(state, streamingState)

            is WsEvent.StatusUpdate -> onStatusUpdate(state, streamingState)

            is WsEvent.Unknown -> onUnknown(state, streamingState)

            // SessionInfo is a no-op in the original code
            is WsEvent.SessionInfo -> ReducerResult(state = state, streamingState = streamingState)

            // RpcResult is handled by the ViewModel (needs pending request context)
            is WsEvent.RpcResult -> ReducerResult(state = state, streamingState = streamingState)

            // ApprovalRequest is handled by the ViewModel (needs active session + WS client)
            is WsEvent.ApprovalRequest -> ReducerResult(state = state, streamingState = streamingState)

            // SudoRequest / SecretRequest are handled by the ViewModel (issue #524)
            is WsEvent.SudoRequest -> ReducerResult(state = state, streamingState = streamingState)

            is WsEvent.SecretRequest -> ReducerResult(state = state, streamingState = streamingState)
        }

    // ── GatewayReady ──────────────────────────────────────────────────

    private fun onGatewayReady(
        state: ChatUiState,
        streamingState: StreamingState,
    ): ReducerResult = ReducerResult(state = state, streamingState = streamingState)

    // ── MessageStart ──────────────────────────────────────────────────

    private fun onMessageStart(
        state: ChatUiState,
        streamingState: StreamingState,
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
            if (streamingState.streamingMessage?.content?.isNotEmpty() == true) {
                val finalized = streamingState.streamingMessage.copy(isStreaming = false)
                orphan = finalized
                state.copy(
                    messages = state.messages + finalized,
                    isAgentTyping = true,
                )
            } else {
                state.copy(
                    isAgentTyping = true,
                )
            }
        val newStreamingState = StreamingState(streamingMessage = msg)
        val newState = preState
        val sid = newState.currentSessionId
        if (orphan != null && sid != null) {
            effects.add(ReducerEffect.PersistMessage(orphan, sid))
        }

        return ReducerResult(state = newState, streamingState = newStreamingState, effects = effects)
    }

    // ── MessageToken ──────────────────────────────────────────────────

    private fun onMessageToken(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.MessageToken,
    ): ReducerResult {
        // Token accumulation is done by the ViewModel (streaming buffer + flush timer).
        // The reducer only signals that new content arrived — ViewModel owns the timer.
        return ReducerResult(state = state, streamingState = streamingState)
    }

    // ── ThinkingDelta ─────────────────────────────────────────────────

    private fun onThinkingDelta(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ThinkingDelta,
    ): ReducerResult {
        val currentContent = streamingState.thinkingText + event.token
        return ReducerResult(
            state = state,
            streamingState =
                streamingState.copy(
                    isThinking = true,
                    thinkingText = currentContent,
                ),
        )
    }

    // ── MessageComplete ───────────────────────────────────────────────

    private fun onMessageComplete(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.MessageComplete,
    ): ReducerResult {
        val streaming = streamingState.streamingMessage
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
                    isAgentTyping = false,
                ),
            effects = effects,
        )
    }

    // ── MessageDone ───────────────────────────────────────────────────

    private fun onMessageDone(
        state: ChatUiState,
        streamingState: StreamingState,
    ): ReducerResult {
        val streaming =
            streamingState.streamingMessage ?: return ReducerResult(
                state = state,
                streamingState = streamingState,
            )
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
                    isAgentTyping = false,
                ),
            effects = effects,
        )
    }

    // ── ToolStart ─────────────────────────────────────────────────────

    private fun onToolStart(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ToolStart,
    ): ReducerResult {
        val contentJson =
            event.data?.let {
                OkHttpProvider.json.encodeToString(it.toJsonElement())
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
            if (streamingState.streamingMessage?.content?.isNotEmpty() == true) {
                val finalized = streamingState.streamingMessage.copy(isStreaming = false)
                orphanToPersist = finalized
                state.copy(
                    messages = state.messages + finalized,
                )
            } else {
                state
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
        streamingState: StreamingState,
        event: WsEvent.ToolComplete,
    ): ReducerResult {
        val contentJson =
            event.data?.let {
                OkHttpProvider.json.encodeToString(it.toJsonElement())
            } ?: ""
        val messages = state.messages.toMutableList()
        val toolIdx =
            messages.indexOfLast {
                it.role == MessageRole.TOOL &&
                    it.toolName == event.name &&
                    it.toolStatus == ToolStatus.RUNNING
            }
        if (toolIdx < 0) return ReducerResult(state = state, streamingState = streamingState)

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
            streamingState = streamingState,
            effects = effects,
        )
    }

    // ── ClarifyRequest ────────────────────────────────────────────────

    private fun onClarifyRequest(
        state: ChatUiState,
        streamingState: StreamingState,
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
        streamingState: StreamingState,
        event: WsEvent.RpcError,
    ): ReducerResult =
        ReducerResult(
            state =
                state.copy(
                    isLoading = false,
                    errorMessage = "Error: ${event.error}",
                ),
            streamingState = streamingState,
        )

    // ── SessionUpdated / StatusUpdate / Unknown ───────────────────────

    private fun onSessionUpdated(
        state: ChatUiState,
        streamingState: StreamingState,
    ): ReducerResult = ReducerResult(state = state, streamingState = streamingState)

    private fun onStatusUpdate(
        state: ChatUiState,
        streamingState: StreamingState,
    ): ReducerResult = ReducerResult(state = state, streamingState = streamingState)

    private fun onUnknown(
        state: ChatUiState,
        streamingState: StreamingState,
    ): ReducerResult = ReducerResult(state = state, streamingState = streamingState)
}

/**
 * Result of reducing one event.
 *
 * @param state The new UI state after applying the event.
 * @param streamingState The new streaming state after applying the event.
 * @param effects Side-effects the ViewModel should execute.
 */
data class ReducerResult(
    val state: ChatUiState,
    val streamingState: StreamingState = StreamingState(),
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
