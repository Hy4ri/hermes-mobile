package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent

data class ReducerResult(
    val state: ChatUiState,
    val messageToPersist: ChatMessage? = null,
    val sessionIdToPersist: String? = null,
    val reloadSessions: Boolean = false,
    val trackRpcResultId: String? = null,
    val trackRpcErrorId: String? = null,
)

class ChatWsEventReducer {
    private fun isCurrentSession(
        state: ChatUiState,
        eventSessionId: String?,
    ): Boolean {
        if (eventSessionId == null) return true
        return eventSessionId == state.currentSessionId
    }

    fun reduce(
        state: ChatUiState,
        event: WsEvent,
        streamingMessageId: String?,
    ): Pair<ReducerResult, String?> {
        var currentStreamingMessageId = streamingMessageId

        when (event) {
            is WsEvent.GatewayReady -> {
                return Pair(ReducerResult(state), currentStreamingMessageId)
            }
            is WsEvent.SessionInfo -> {
                return Pair(ReducerResult(state), currentStreamingMessageId)
            }
            is WsEvent.MessageStart -> {
                if (!isCurrentSession(
                        state,
                        event.sessionId,
                    )
                ) {
                    return Pair(ReducerResult(state), currentStreamingMessageId)
                }

                var orphanToPersist: ChatMessage? = null
                val sessionId = state.currentSessionId

                val msg =
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "",
                        isStreaming = true,
                    )
                currentStreamingMessageId = msg.id

                val messages = state.messages.toMutableList()
                val existing = state.streamingMessage
                if (existing != null && existing.content.isNotEmpty()) {
                    val finalized = existing.copy(isStreaming = false)
                    messages.add(finalized)
                    orphanToPersist = finalized
                }

                val newState =
                    state.copy(
                        messages = messages,
                        isAgentTyping = true,
                        streamingMessage = msg,
                        isThinking = false,
                        thinkingText = "",
                    )

                return Pair(
                    ReducerResult(newState, messageToPersist = orphanToPersist, sessionIdToPersist = sessionId),
                    currentStreamingMessageId,
                )
            }
            is WsEvent.MessageToken -> {
                if (!isCurrentSession(
                        state,
                        event.sessionId,
                    )
                ) {
                    return Pair(ReducerResult(state), currentStreamingMessageId)
                }

                val current = state.streamingMessage
                if (current != null) {
                    val newState =
                        state.copy(
                            streamingMessage =
                                current.copy(
                                    content = current.content + event.token,
                                ),
                            isThinking = false,
                        )
                    return Pair(ReducerResult(newState), currentStreamingMessageId)
                } else {
                    val msg =
                        ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = event.token,
                            isStreaming = true,
                        )
                    currentStreamingMessageId = msg.id
                    val newState =
                        state.copy(
                            streamingMessage = msg,
                            isAgentTyping = true,
                            isThinking = false,
                        )
                    return Pair(ReducerResult(newState), currentStreamingMessageId)
                }
            }
            is WsEvent.ThinkingDelta -> {
                if (!isCurrentSession(
                        state,
                        event.sessionId,
                    )
                ) {
                    return Pair(ReducerResult(state), currentStreamingMessageId)
                }
                val newState =
                    state.copy(
                        isThinking = true,
                        thinkingText = state.thinkingText + event.token,
                    )
                return Pair(ReducerResult(newState), currentStreamingMessageId)
            }
            is WsEvent.MessageComplete -> {
                if (!isCurrentSession(
                        state,
                        event.sessionId,
                    )
                ) {
                    return Pair(ReducerResult(state), currentStreamingMessageId)
                }

                val streaming = state.streamingMessage
                val msg =
                    if (streaming != null) {
                        streaming.copy(
                            content = event.text,
                            isStreaming = false,
                        )
                    } else {
                        ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = event.text,
                        )
                    }

                val sessionId = state.currentSessionId
                val newState =
                    state.copy(
                        messages = state.messages + msg,
                        streamingMessage = null,
                        isAgentTyping = false,
                        isThinking = false,
                        thinkingText = "",
                    )
                currentStreamingMessageId = null

                return Pair(
                    ReducerResult(
                        newState,
                        messageToPersist = msg,
                        sessionIdToPersist = sessionId,
                        reloadSessions = true,
                    ),
                    currentStreamingMessageId,
                )
            }
            is WsEvent.MessageDone -> {
                if (!isCurrentSession(
                        state,
                        event.sessionId,
                    )
                ) {
                    return Pair(ReducerResult(state), currentStreamingMessageId)
                }

                val streaming = state.streamingMessage
                val msg = streaming?.copy(isStreaming = false)
                val sessionId = state.currentSessionId

                val newState =
                    if (msg != null) {
                        state.copy(
                            messages = state.messages + msg,
                            streamingMessage = null,
                            isAgentTyping = false,
                            isThinking = false,
                            thinkingText = "",
                        )
                    } else {
                        state.copy(
                            isAgentTyping = false,
                            isThinking = false,
                            thinkingText = "",
                        )
                    }
                currentStreamingMessageId = null

                return Pair(
                    ReducerResult(newState, messageToPersist = msg, sessionIdToPersist = sessionId),
                    currentStreamingMessageId,
                )
            }
            is WsEvent.ToolStart -> {
                if (!isCurrentSession(
                        state,
                        event.sessionId,
                    )
                ) {
                    return Pair(ReducerResult(state), currentStreamingMessageId)
                }

                var orphanToPersist: ChatMessage? = null
                val messages = state.messages.toMutableList()
                val existing = state.streamingMessage
                if (existing != null && existing.content.isNotEmpty()) {
                    val finalized = existing.copy(isStreaming = false)
                    messages.add(finalized)
                    orphanToPersist = finalized
                }
                currentStreamingMessageId = null

                val gson = com.google.gson.GsonBuilder().create()
                val dataJson = if (event.data != null) gson.toJson(event.data) else "{}"
                val msg =
                    ChatMessage(
                        role = MessageRole.TOOL,
                        content = dataJson,
                        isStreaming = false,
                        toolStatus = ToolStatus.RUNNING,
                    )
                messages.add(msg)

                val sessionId = state.currentSessionId
                val newState =
                    state.copy(
                        messages = messages,
                        isAgentTyping = true,
                        streamingMessage = null,
                        isThinking = false,
                        thinkingText = "",
                    )
                return Pair(
                    ReducerResult(newState, messageToPersist = msg, sessionIdToPersist = sessionId),
                    currentStreamingMessageId,
                )
            }
            is WsEvent.ToolComplete -> {
                if (!isCurrentSession(
                        state,
                        event.sessionId,
                    )
                ) {
                    return Pair(ReducerResult(state), currentStreamingMessageId)
                }
                val messages = state.messages.toMutableList()
                if (messages.isNotEmpty() && messages.last().role == MessageRole.TOOL) {
                    messages[messages.lastIndex] =
                        messages.last().copy(
                            content = com.google.gson.Gson().toJson(event.data ?: mapOf<String, Any>()),
                            toolStatus = ToolStatus.COMPLETED,
                        )
                } else {
                    messages.add(
                        ChatMessage(
                            role = MessageRole.TOOL,
                            content = com.google.gson.Gson().toJson(event.data ?: mapOf<String, Any>()),
                            isStreaming = false,
                            toolStatus = ToolStatus.COMPLETED,
                        ),
                    )
                }
                val msg = messages.last()

                val sessionId = state.currentSessionId
                val newState =
                    state.copy(
                        messages = messages,
                    )
                return Pair(
                    ReducerResult(newState, messageToPersist = msg, sessionIdToPersist = sessionId),
                    currentStreamingMessageId,
                )
            }
            is WsEvent.ClarifyRequest -> {
                if (!isCurrentSession(
                        state,
                        event.sessionId,
                    )
                ) {
                    return Pair(ReducerResult(state), currentStreamingMessageId)
                }
                val newState =
                    state.copy(
                        clarifyRequest =
                            ClarifyUi(
                                text = event.text.orEmpty(),
                                options = event.options.orEmpty(),
                                clarifyId = event.clarifyId,
                            ),
                    )
                return Pair(ReducerResult(newState), currentStreamingMessageId)
            }
            is WsEvent.StatusUpdate -> {
                return Pair(ReducerResult(state), currentStreamingMessageId)
            }
            is WsEvent.SessionUpdated -> {
                return Pair(ReducerResult(state, reloadSessions = true), currentStreamingMessageId)
            }
            is WsEvent.RpcResult -> {
                return Pair(ReducerResult(state, trackRpcResultId = event.id), currentStreamingMessageId)
            }
            is WsEvent.RpcError -> {
                return Pair(ReducerResult(state, trackRpcErrorId = event.id), currentStreamingMessageId)
            }
            is WsEvent.Unknown -> {
                return Pair(ReducerResult(state), currentStreamingMessageId)
            }
        }
    }
}
