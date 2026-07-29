package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.remote.OkHttpProvider
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.toJsonElement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
                is WsEvent.ReasoningDelta -> event.sessionId
                is WsEvent.MessageComplete -> event.sessionId
                is WsEvent.MessageDone -> event.sessionId
                is WsEvent.ToolStart -> event.sessionId
                is WsEvent.ToolComplete -> event.sessionId
                is WsEvent.ToolOutputRisk -> event.sessionId
                is WsEvent.ClarifyRequest -> event.sessionId
                is WsEvent.ToolProgress -> event.sessionId
                is WsEvent.ToolGenerating -> event.sessionId
                is WsEvent.SubagentEvent -> event.sessionId
                is WsEvent.ReviewSummary -> event.sessionId
                else -> null
            }
        if (eventSessionId != null && currentSessionId != null && eventSessionId != currentSessionId) {
            return ReducerResult(state = state, streamingState = streamingState)
        }
        val result = reduceInternal(state, streamingState, event)
        val hydratedTodos =
            if (result.state.todos.isEmpty() && result.state.messages.isNotEmpty()) {
                hydrateTodosFromMessages(result.state.messages)
            } else {
                result.state.todos
            }
        return if (hydratedTodos !== result.state.todos) {
            result.copy(state = result.state.copy(todos = hydratedTodos))
        } else {
            result
        }
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

            is WsEvent.ReasoningDelta -> onReasoningDelta(state, streamingState, event)

            is WsEvent.ReasoningAvailable -> onReasoningAvailable(state, streamingState, event)

            is WsEvent.MessageComplete -> onMessageComplete(state, streamingState, event)

            is WsEvent.MessageDone -> onMessageDone(state, streamingState)

            is WsEvent.ToolStart -> onToolStart(state, streamingState, event)

            is WsEvent.ToolComplete -> onToolComplete(state, streamingState, event)

            is WsEvent.ToolOutputRisk -> onToolOutputRisk(state, streamingState, event)

            is WsEvent.ToolProgress -> onToolProgress(state, streamingState, event)

            is WsEvent.ToolGenerating -> onToolGenerating(state, streamingState, event)

            is WsEvent.SubagentEvent -> onSubagentEvent(state, streamingState, event)

            is WsEvent.ClarifyRequest -> onClarifyRequest(state, streamingState, event)

            is WsEvent.ReviewSummary -> onReviewSummary(state, streamingState, event)

            is WsEvent.RpcError -> onRpcError(state, streamingState, event)

            is WsEvent.GatewayError -> onGatewayError(state, streamingState, event)

            is WsEvent.BackgroundComplete -> onBackgroundComplete(state, streamingState, event)

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

            // ReactionEvent is handled by the ViewModel — purely cosmetic animation
            is WsEvent.ReactionEvent -> ReducerResult(state = state, streamingState = streamingState)
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
                reasoningText = streamingState.reasoningText,
                isStreaming = true,
            )
        val effects = mutableListOf<ReducerEffect>()

        val cleanedSubagents =
            if (state.subagentIndicators.all { it.isComplete || it.isFailed }) {
                emptyList()
            } else {
                state.subagentIndicators
            }

        // Build new state: finalize any orphan streaming message, then set the new one
        var orphan: ChatMessage? = null
        val preState =
            if (streamingState.streamingMessage?.content?.isNotEmpty() == true) {
                val finalized = streamingState.streamingMessage.copy(isStreaming = false)
                orphan = finalized
                state.copy(
                    messages = state.messages + finalized,
                    isAgentTyping = true,
                    subagentIndicators = cleanedSubagents,
                )
            } else {
                state.copy(
                    isAgentTyping = true,
                    subagentIndicators = cleanedSubagents,
                )
            }
        val newStreamingState =
            StreamingState(
                streamingMessage = msg,
                isReasoning = streamingState.isReasoning,
                reasoningText = streamingState.reasoningText,
            )
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

    // ── ReasoningDelta ────────────────────────────────────────────────
    private fun onReasoningDelta(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ReasoningDelta,
    ): ReducerResult {
        val currentContent = streamingState.reasoningText + event.token
        return ReducerResult(
            state = state,
            streamingState =
                streamingState.copy(
                    isReasoning = true,
                    reasoningText = currentContent,
                ),
        )
    }

    // ── ReasoningAvailable ────────────────────────────────────────────
    private fun onReasoningAvailable(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ReasoningAvailable,
    ): ReducerResult =
        ReducerResult(
            state = state,
            streamingState = streamingState.copy(isReasoning = true),
        )

    // ── MessageComplete ───────────────────────────────────────────────

    private fun onMessageComplete(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.MessageComplete,
    ): ReducerResult {
        val streaming = streamingState.streamingMessage
        val reasoning =
            if (streamingState.reasoningText.isNotBlank()) {
                streamingState.reasoningText
            } else {
                streaming?.reasoningText ?: ""
            }
        val msg =
            streaming?.copy(
                content = event.text,
                isStreaming = false,
                reasoningText = reasoning,
            ) ?: ChatMessage(
                role = MessageRole.ASSISTANT,
                content = event.text,
                reasoningText = reasoning,
            )
        val effects = mutableListOf<ReducerEffect>()
        val sid = state.currentSessionId
        if (sid != null) {
            effects.add(ReducerEffect.PersistMessage(msg, sid))
        }
        effects.add(ReducerEffect.RefreshSessions)
        // Refresh the context meter immediately on completion instead of
        // waiting for the next 5s session-sync poll.
        effects.add(ReducerEffect.RefreshContextUsage)
        // Mobile-only media fix (issue #724): the WS stream delivers the raw
        // `MEDIA:<host-path>` directive. Turn it into real attachments (images
        // inline, every other file tappable) via the gateway /api/files/download
        // endpoint — works on a remote phone too.
        if (msg.role == MessageRole.ASSISTANT && sid != null) {
            effects.add(ReducerEffect.AttachHostMedia(sid, msg.id))
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
        val reasoning =
            if (streamingState.reasoningText.isNotBlank()) {
                streamingState.reasoningText
            } else {
                streaming.reasoningText
            }
        val msg = streaming.copy(isStreaming = false, reasoningText = reasoning)
        val effects = mutableListOf<ReducerEffect>()
        val sid = state.currentSessionId
        if (sid != null) {
            effects.add(ReducerEffect.PersistMessage(msg, sid))
        }
        // Mobile-only media fix (issue #724): attach any `MEDIA:<path>` file
        // carried in the finalized assistant message as a real attachment. See
        // the ReducerEffect.AttachHostMedia KDoc for the rationale.
        if (msg.role == MessageRole.ASSISTANT && sid != null) {
            effects.add(ReducerEffect.AttachHostMedia(sid, msg.id))
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
                val reasoning =
                    if (streamingState.reasoningText.isNotBlank()) {
                        streamingState.reasoningText
                    } else {
                        streamingState.streamingMessage.reasoningText
                    }
                val finalized =
                    streamingState.streamingMessage.copy(
                        isStreaming = false,
                        reasoningText = reasoning,
                    )
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

        val parsedTodos = if (event.name == "todo") extractTodosFromMap(event.data) else null
        val nextTodos = parsedTodos ?: newState.todos

        return ReducerResult(
            state = newState.copy(messages = newState.messages + toolMessage, todos = nextTodos),
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

        val parsedTodos = if (event.name == "todo") extractTodosFromMap(event.data) else null
        val nextTodos = parsedTodos ?: state.todos

        return ReducerResult(
            state = state.copy(messages = messages, todos = nextTodos),
            streamingState = streamingState,
            effects = effects,
        )
    }

    // ── ToolOutputRisk ────────────────────────────────────────────────

    /**
     * Attaches [ToolOutputRiskData] to the matching tool message.
     *
     * Finds the last RUNNING tool message with matching [WsEvent.ToolOutputRisk.name].
     * If no RUNNING message exists (tool already completed), falls back to the last
     * COMPLETED tool with that name. This covers the case where the risk event arrives
     * after tool.complete.
     */
    private fun onToolOutputRisk(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ToolOutputRisk,
    ): ReducerResult {
        val riskData =
            ToolOutputRiskData(
                risk = event.risk,
                findings = event.findings,
                redacted = event.redacted,
            )

        val messages = state.messages.toMutableList()

        // Prefer RUNNING tool, fall back to COMPLETED
        var toolIdx =
            messages.indexOfLast {
                it.role == MessageRole.TOOL &&
                    it.toolName == event.name &&
                    it.toolStatus == ToolStatus.RUNNING
            }
        if (toolIdx < 0) {
            toolIdx =
                messages.indexOfLast {
                    it.role == MessageRole.TOOL &&
                        it.toolName == event.name &&
                        it.toolStatus == ToolStatus.COMPLETED
                }
        }
        if (toolIdx < 0) return ReducerResult(state = state, streamingState = streamingState)

        messages[toolIdx] = messages[toolIdx].copy(toolOutputRiskData = riskData)

        // No persist — risk data is transient (not stored in SQLite)
        return ReducerResult(
            state = state.copy(messages = messages),
            streamingState = streamingState,
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

    // ── ReviewSummary (Self-improvement background review) ──────────────

    private fun onReviewSummary(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ReviewSummary,
    ): ReducerResult {
        if (event.text.isBlank()) return ReducerResult(state = state, streamingState = streamingState)
        val systemMessage =
            ChatMessage(
                role = MessageRole.SYSTEM,
                content = event.text,
            )
        return ReducerResult(
            state = state.copy(messages = state.messages + systemMessage),
            streamingState = streamingState,
        )
    }

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

    // ── GatewayError ──────────────────────────────────────────────────
    // Backend/unhandled failure surfaced by the gateway (issue #527).
    // Mirrors onRpcError: surfaces the message in the existing error banner.
    private fun onGatewayError(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.GatewayError,
    ): ReducerResult =
        ReducerResult(
            state =
                state.copy(
                    isLoading = false,
                    errorMessage = "⚠️ Backend error: ${event.message ?: "Unknown gateway error"}",
                ),
            streamingState = streamingState,
        )

    // ── BackgroundComplete ────────────────────────────────────────────
    // A scheduled/background job finished (issue #527). The ViewModel turns
    // this into a non-blocking snackbar. No state mutation beyond passing it
    // through; the ViewModel owns the snackbar trigger.
    private fun onBackgroundComplete(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.BackgroundComplete,
    ): ReducerResult {
        val message =
            when (val label = event.data?.get("label")) {
                is String -> label
                else -> event.data?.get("name") as? String
            }?.let { "✅ Background job finished: $it" }
                ?: "✅ Background job finished"
        return ReducerResult(
            state = state.copy(backgroundCompleteMessage = message),
            streamingState = streamingState,
        )
    }

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

    private fun onToolProgress(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ToolProgress,
    ): ReducerResult {
        val messages = state.messages.toMutableList()
        val toolIdx =
            messages.indexOfLast {
                it.role == MessageRole.TOOL &&
                    it.toolName == event.name &&
                    it.toolStatus == ToolStatus.RUNNING
            }
        if (toolIdx < 0) return ReducerResult(state = state, streamingState = streamingState)

        messages[toolIdx] = messages[toolIdx].copy(progressPreview = event.preview ?: "")
        return ReducerResult(
            state = state.copy(messages = messages),
            streamingState = streamingState,
        )
    }

    private fun onToolGenerating(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.ToolGenerating,
    ): ReducerResult {
        val messages = state.messages.toMutableList()
        val toolIdx =
            messages.indexOfLast {
                it.role == MessageRole.TOOL &&
                    it.toolName == event.name &&
                    it.toolStatus == ToolStatus.RUNNING
            }
        if (toolIdx < 0) return ReducerResult(state = state, streamingState = streamingState)

        messages[toolIdx] = messages[toolIdx].copy(progressPreview = "")
        return ReducerResult(
            state = state.copy(messages = messages),
            streamingState = streamingState,
        )
    }

    private fun onSubagentEvent(
        state: ChatUiState,
        streamingState: StreamingState,
        event: WsEvent.SubagentEvent,
    ): ReducerResult {
        val goal = event.payload?.get("goal") as? String
        val taskIndex = (event.payload?.get("task_index") as? Number)?.toInt()
        val taskCount = (event.payload?.get("task_count") as? Number)?.toInt()
        val text = event.payload?.get("text") as? String
        val status = event.payload?.get("status") as? String
        val summary = event.payload?.get("summary") as? String
        val durationSeconds = (event.payload?.get("duration_seconds") as? Number)?.toDouble()
        val model = event.payload?.get("model") as? String
        val subagentId =
            event.payload?.get(
                "subagent_id",
            ) as? String ?: event.payload?.get("child_session_id") as? String

        val indicators = state.subagentIndicators.toMutableList()
        val idx =
            if (subagentId != null) {
                indicators.indexOfLast { it.subagentId == subagentId }
            } else if (goal != null) {
                indicators.indexOfLast { it.goal == goal }
            } else {
                -1
            }

        val existingLogs = if (idx >= 0) indicators[idx].logs else emptyList()
        val newLogs = existingLogs.toMutableList()
        if (!text.isNullOrBlank() && newLogs.lastOrNull()?.text != text) {
            newLogs.add(SubagentLogLine(text = text, isError = status == "failed"))
        }
        if (event.type == "subagent.complete" && !summary.isNullOrBlank() && newLogs.lastOrNull()?.text != summary) {
            newLogs.add(SubagentLogLine(text = summary, isSummary = true))
        }
        val trimmedLogs = newLogs.takeLast(30)

        val finalStatus =
            if (event.type == "subagent.complete") {
                status ?: "completed"
            } else {
                status ?: (if (idx >= 0) indicators[idx].status else "running")
            }

        val indicator =
            SubagentIndicator(
                type = event.type,
                goal = goal ?: (if (idx >= 0) indicators[idx].goal else null),
                taskIndex = taskIndex ?: (if (idx >= 0) indicators[idx].taskIndex else null),
                taskCount = taskCount ?: (if (idx >= 0) indicators[idx].taskCount else null),
                text = summary ?: text ?: (if (idx >= 0) indicators[idx].text else null),
                status = finalStatus,
                summary = summary ?: (if (idx >= 0) indicators[idx].summary else null),
                subagentId = subagentId ?: (if (idx >= 0) indicators[idx].subagentId else null),
                logs = trimmedLogs,
                durationSeconds = durationSeconds ?: (if (idx >= 0) indicators[idx].durationSeconds else null),
                model = model ?: (if (idx >= 0) indicators[idx].model else null),
            )

        if (idx >= 0) {
            indicators[idx] = indicator
        } else {
            indicators.add(indicator)
        }

        return ReducerResult(
            state = state.copy(subagentIndicators = indicators),
            streamingState = streamingState,
        )
    }
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

    data object RefreshContextUsage : ReducerEffect()

    /**
     * Ask the ViewModel to turn any `MEDIA:<host-path>` directives in the
     * just-finalized assistant message into real [Attachment]s (images inline,
     * every other file type tappable) and strip the directives from the text.
     *
     * Why: the gateway's WebSocket stream delivers the raw `MEDIA:<path>`
     * directive (the same one the desktop app resolves via `mediaExternalUrl`
     * to the authenticated `/api/files/download` endpoint, which streams the
     * host file over HTTP). Attaching them lets the renderer show images inline
     * and offer every other file as a fetchable attachment — even on a remote
     * phone (real HTTP, unlike a host-local file read). Mirrors desktop's
     * `mediaExternalUrl`; mobile-only, backend untouched. See issue #724.
     */
    data class AttachHostMedia(
        val sessionId: String,
        val messageId: String,
    ) : ReducerEffect()
}

@Suppress("UNCHECKED_CAST")
fun extractTodosFromMap(data: Map<String, Any?>?): List<TodoItem>? {
    if (data == null) return null
    val rawList =
        (data["todos"] as? List<*>)
            ?: ((data["args"] as? Map<String, Any?>)?.get("todos") as? List<*>)
            ?: ((data["parameters"] as? Map<String, Any?>)?.get("todos") as? List<*>)
            ?: ((data["result"] as? Map<String, Any?>)?.get("todos") as? List<*>)
            ?: return null

    val items = mutableListOf<TodoItem>()
    for (elem in rawList) {
        val map = elem as? Map<String, Any?> ?: continue
        val id = (map["id"] as? String) ?: continue
        val content = (map["content"] as? String) ?: (map["text"] as? String) ?: ""
        val status = (map["status"] as? String) ?: "pending"
        items.add(TodoItem(id = id, content = content, status = status))
    }
    return items.takeIf { it.isNotEmpty() }
}

fun extractTodosFromJson(content: String): List<TodoItem>? {
    if (content.isBlank()) return null
    return try {
        val element = OkHttpProvider.json.parseToJsonElement(content)
        val obj = element as? JsonObject ?: return null
        val todosArray =
            (obj["todos"] as? JsonArray)
                ?: ((obj["args"] as? JsonObject)?.get("todos") as? JsonArray)
                ?: ((obj["result"] as? JsonObject)?.get("todos") as? JsonArray)
                ?: ((obj["parameters"] as? JsonObject)?.get("todos") as? JsonArray)
                ?: return null

        todosArray.mapNotNull { item ->
            val itemObj = item as? JsonObject ?: return@mapNotNull null
            val id = (itemObj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val contentStr =
                (itemObj["content"] as? JsonPrimitive)?.content
                    ?: (itemObj["text"] as? JsonPrimitive)?.content ?: ""
            val status = (itemObj["status"] as? JsonPrimitive)?.content ?: "pending"
            TodoItem(id = id, content = contentStr, status = status)
        }.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

fun hydrateTodosFromMessages(messages: List<ChatMessage>): List<TodoItem> {
    for (msg in messages.asReversed()) {
        if (msg.role == MessageRole.TOOL && (msg.toolName == "todo" || msg.content.contains("\"todos\""))) {
            val todos = extractTodosFromJson(msg.content)
            if (!todos.isNullOrEmpty()) {
                return todos
            }
        }
    }
    return emptyList()
}
