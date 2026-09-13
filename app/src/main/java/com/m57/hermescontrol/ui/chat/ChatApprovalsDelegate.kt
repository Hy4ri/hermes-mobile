package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns approval rendering, deduplication, user response submission, and
 * reconnect/resume pending approval replay.
 *
 * Extracted behavior-preservingly from [ChatViewModel].
 */
class ChatApprovalsDelegate(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val runtimeSessionId: () -> String?,
    private val wsSend: (method: String, params: Map<String, Any>, onSent: ((String) -> Unit)?) -> Unit,
    private val trackRequest: (id: String, method: String) -> Unit,
    private val addSystemMessage: (text: String) -> Unit,
) {
    fun handleApprovalRequest(event: WsEvent.ApprovalRequest) {
        val description = event.description ?: event.command ?: "Unknown command"
        val content = "**Approval Required**\n$description"
        val msg =
            ChatMessage(
                role = MessageRole.SYSTEM,
                content = content,
                approvalInfo =
                    ApprovalInfo(
                        command = event.command,
                        description = event.description,
                        patternKeys = event.patternKeys,
                        requestId = event.requestId,
                        choices = event.choices,
                        allowPermanent = event.allowPermanent,
                        smartDenied = event.smartDenied,
                    ),
            )
        uiState.update { state ->
            state.copy(
                messages = state.messages + msg,
                isAgentTyping = false,
            )
        }
        // Desktop parity (`prompts.ts` receiveApprovalRequest): ack the render
        // so the backend knows this client holds the prompt. Fire-and-forget.
        val requestId = event.requestId
        val sessionId = runtimeSessionId() ?: event.sessionId ?: uiState.value.currentSessionId
        if (requestId != null && sessionId != null) {
            scope.launch(ioDispatcher) {
                runCatching {
                    wsSend(
                        WsMethods.APPROVAL_RECEIVED,
                        mapOf(
                            "session_id" to sessionId,
                            "request_id" to requestId,
                        ),
                        null,
                    )
                }
            }
        }
    }

    fun replayPendingApproval(sessionId: String) {
        val targetSessionId = runtimeSessionId() ?: sessionId
        scope.launch(ioDispatcher) {
            wsSend(
                WsMethods.APPROVAL_PENDING,
                mapOf("session_id" to targetSessionId),
            ) { id -> trackRequest(id, WsMethods.APPROVAL_PENDING) }
        }
    }

    fun handleApprovalPendingResult(result: Any?) {
        @Suppress("UNCHECKED_CAST")
        val approvals = (result as? Map<*, *>)?.get("approvals") as? List<*>
        val first = approvals?.filterIsInstance<Map<*, *>>()?.firstOrNull() ?: return
        val requestId = first["request_id"] as? String ?: return
        val sessionId = runtimeSessionId() ?: uiState.value.currentSessionId
        val alreadyShown =
            uiState.value.messages.any { it.approvalInfo?.requestId == requestId }
        if (alreadyShown) return
        handleApprovalRequest(parseApprovalMap(first, sessionId))
    }

    fun handleApprovalRespondResult(result: Any?) {
        val map = result as? Map<*, *>
        val resolved = (map?.get("resolved") as? Number)?.toInt() ?: 0
        if (resolved > 0) {
            addSystemMessage("Approval submitted")
        }
    }

    fun maybeSurfacePendingApproval(
        map: Map<*, *>,
        sessionId: String?,
    ) {
        val requestId = map["request_id"] as? String
        val alreadyShown =
            requestId != null &&
                uiState.value.messages.any { it.approvalInfo?.requestId == requestId }
        if (!alreadyShown) {
            handleApprovalRequest(parseApprovalMap(map, sessionId))
        }
    }

    fun respondToApproval(action: String) {
        val state = uiState.value
        val approvalMsg = state.messages.lastOrNull { it.approvalInfo != null } ?: return
        val sessionId = runtimeSessionId() ?: state.currentSessionId ?: return
        // Desktop sends `once` for a single run; legacy mobile sent `approve`
        // (any non-deny still unblocks, but stay on-spec going forward).
        val choice = if (action == "approve") "once" else action
        val requestId = approvalMsg.approvalInfo?.requestId

        // Clear buttons immediately
        uiState.update { s ->
            s.copy(
                messages =
                    s.messages.map {
                        if (it.id == approvalMsg.id) {
                            it.copy(approvalInfo = null)
                        } else {
                            it
                        }
                    },
            )
        }

        scope.launch(ioDispatcher) {
            val params =
                mutableMapOf<String, Any>(
                    "session_id" to sessionId,
                    "choice" to choice,
                    "all" to false,
                )
            if (requestId != null) params["request_id"] = requestId
            wsSend(
                WsMethods.APPROVAL_RESPOND,
                params,
            ) { id -> trackRequest(id, WsMethods.APPROVAL_RESPOND) }
            // The queue can hold more pendings — surface the next one.
            replayPendingApproval(sessionId)
        }
    }
}

/**
 * Convert a backend approval map (snake_case, from `approval.pending` or
 * `session.info` `pending_approval`) into a typed event. Mirrors
 * [EventParser] defaults so replay and live paths agree.
 */
internal fun parseApprovalMap(
    map: Map<*, *>,
    sessionId: String?,
): WsEvent.ApprovalRequest {
    @Suppress("UNCHECKED_CAST")
    val patternKeys = (map["pattern_keys"] as? List<*>)?.filterIsInstance<String>()

    @Suppress("UNCHECKED_CAST")
    val rawChoices = (map["choices"] as? List<*>)?.filterIsInstance<String>()
    val allowPermanent = map["allow_permanent"] as? Boolean
    val allowSession = map["allow_session"] as? Boolean
    val smartDenied = map["smart_denied"] as? Boolean
    val choices =
        rawChoices ?: run {
            if (smartDenied == true) {
                listOf("once", "deny")
            } else {
                buildList {
                    add("once")
                    if (allowSession != false) {
                        add("session")
                        if (allowPermanent != false) add("always")
                    }
                    add("deny")
                }
            }
        }
    return WsEvent.ApprovalRequest(
        command = map["command"] as? String,
        description = map["description"] as? String,
        patternKeys = patternKeys,
        sessionId = sessionId,
        requestId = map["request_id"] as? String,
        choices = choices,
        allowPermanent = allowPermanent,
        smartDenied = smartDenied,
    )
}
