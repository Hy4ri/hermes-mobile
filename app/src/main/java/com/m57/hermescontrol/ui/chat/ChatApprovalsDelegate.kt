package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.data.ws.WsMethods
import com.m57.hermescontrol.data.ws.contract.ApprovalPendingParams
import com.m57.hermescontrol.data.ws.contract.ApprovalReceivedParams
import com.m57.hermescontrol.data.ws.contract.ApprovalRespondParams
import com.m57.hermescontrol.data.ws.contract.RpcMethods
import com.m57.hermescontrol.data.ws.contract.TypedRpcSender
import com.m57.hermescontrol.data.ws.parseApprovalKindFields
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    private val rpc: TypedRpcSender,
    private val trackRequest: (id: String, method: String) -> Unit,
    private val addSystemMessage: (text: String) -> Unit,
    private val respondToServerRequest: ((String, JsonElement) -> Unit)? = null,
) {
    fun handleApprovalRequest(event: WsEvent.ApprovalRequest) {
        val existingIndex =
            uiState.value.messages.indexOfLast { message ->
                val approval = message.approvalInfo ?: return@indexOfLast false
                (event.serverRequestId != null && approval.serverRequestId == event.serverRequestId) ||
                    (event.requestId != null && approval.requestId == event.requestId)
            }
        if (existingIndex >= 0) {
            // `approval.pending` can arrive before the live server request during
            // reconnect. Upgrade that legacy card with the direct server id so
            // the user's next action answers the same JSON-RPC request instead of
            // creating a duplicate prompt.
            if (event.serverRequestId != null) {
                uiState.update { state ->
                    state.copy(
                        messages =
                            state.messages.mapIndexed { index, message ->
                                if (index != existingIndex) {
                                    message
                                } else {
                                    val approval = message.approvalInfo
                                    message.copy(
                                        approvalInfo =
                                            approval?.copy(
                                                command = event.command ?: approval.command,
                                                description = event.description ?: approval.description,
                                                patternKeys = event.patternKeys ?: approval.patternKeys,
                                                requestId = event.requestId ?: approval.requestId,
                                                serverRequestId = event.serverRequestId,
                                                choices = event.choices ?: approval.choices,
                                                allowPermanent = event.allowPermanent ?: approval.allowPermanent,
                                                smartDenied = event.smartDenied ?: approval.smartDenied,
                                                path = event.path ?: approval.path,
                                                access = event.access ?: approval.access,
                                                host = event.host ?: approval.host,
                                                label = event.label ?: approval.label,
                                                risk = event.risk ?: approval.risk,
                                            ),
                                    )
                                }
                            },
                    )
                }
            }
            return
        }
        val description =
            event.description ?: event.command ?: event.path ?: event.host ?: "Unknown command"
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
                        serverRequestId = event.serverRequestId,
                        choices = event.choices,
                        allowPermanent = event.allowPermanent,
                        smartDenied = event.smartDenied,
                        kind = event.kind,
                        path = event.path,
                        access = event.access,
                        host = event.host,
                        label = event.label,
                        risk = event.risk,
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
        if (event.serverRequestId == null && requestId != null && sessionId != null) {
            scope.launch(ioDispatcher) {
                runCatching {
                    rpc.send(
                        RpcMethods.APPROVAL_RECEIVED,
                        ApprovalReceivedParams(
                            sessionId = sessionId,
                            requestId = requestId,
                        ),
                    )
                }
            }
        }
    }

    fun replayPendingApproval(sessionId: String) {
        val targetSessionId = runtimeSessionId() ?: sessionId
        scope.launch(ioDispatcher) {
            rpc.send(
                RpcMethods.APPROVAL_PENDING,
                ApprovalPendingParams(sessionId = targetSessionId),
            ) { id -> trackRequest(id, WsMethods.APPROVAL_PENDING) }
        }
    }

    fun handleApprovalPendingResult(result: Any?) {
        @Suppress("UNCHECKED_CAST")
        val approvals = (result as? Map<*, *>)?.get("approvals") as? List<*>
        val sessionId = runtimeSessionId() ?: uiState.value.currentSessionId
        // A malformed folder/site entry is not parked and not acknowledged: skip to the next valid one.
        val (first, event) =
            approvals
                ?.filterIsInstance<Map<*, *>>()
                ?.firstNotNullOfOrNull { entry ->
                    if (entry["request_id"] !is String) return@firstNotNullOfOrNull null
                    parseApprovalMap(entry, sessionId)?.let { entry to it }
                } ?: return
        val requestId = first["request_id"] as? String ?: return
        val alreadyShown =
            uiState.value.messages.any { it.approvalInfo?.requestId == requestId }
        if (alreadyShown) return
        handleApprovalRequest(event)
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
            parseApprovalMap(map, sessionId)?.let(::handleApprovalRequest)
        }
    }

    fun respondToApproval(
        action: String,
        targetRequestId: String? = null,
        reason: String? = null,
    ) {
        val state = uiState.value
        val approvalMsg =
            if (targetRequestId != null) {
                state.messages.firstOrNull { message ->
                    val info = message.approvalInfo ?: return@firstOrNull false
                    info.requestId == targetRequestId ||
                        info.serverRequestId == targetRequestId ||
                        message.id == targetRequestId
                }
            } else {
                state.messages.lastOrNull { it.approvalInfo != null }
            } ?: return
        val sessionId = runtimeSessionId() ?: state.currentSessionId ?: return
        // Desktop sends `once` for a single run; legacy mobile sent `approve`
        // (any non-deny still unblocks, but stay on-spec going forward).
        val choice = if (action == "approve") "once" else action
        val requestId = approvalMsg.approvalInfo?.requestId
        val serverRequestId = approvalMsg.approvalInfo?.serverRequestId
        // Only a denied folder/site request carries a reason; command approvals ignore it.
        val denyReason =
            reason
                ?.trim()
                ?.takeIf { it.isNotEmpty() && choice == "deny" && approvalMsg.approvalInfo?.kind?.isSandbox == true }

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
            if (serverRequestId != null && respondToServerRequest != null) {
                respondToServerRequest.invoke(
                    serverRequestId,
                    buildJsonObject {
                        put("choice", choice)
                        put("all", false)
                        if (denyReason != null) put("reason", denyReason)
                    },
                )
                return@launch
            }
            rpc.send(
                RpcMethods.APPROVAL_RESPOND,
                ApprovalRespondParams(
                    sessionId = sessionId,
                    choice = choice,
                    all = false,
                    requestId = requestId,
                    reason = denyReason,
                ),
            ) { id -> trackRequest(id, WsMethods.APPROVAL_RESPOND) }
            // The queue can hold more pendings — surface the next one.
            replayPendingApproval(sessionId)
        }
    }

    fun cancelServerRequest(requestId: String) {
        uiState.update { state ->
            state.copy(
                messages =
                    state.messages.map { message ->
                        val info = message.approvalInfo
                        val matches =
                            info != null &&
                                (
                                    info.serverRequestId == requestId ||
                                        info.requestId == requestId ||
                                        message.id == requestId
                                )
                        if (matches) {
                            message.copy(approvalInfo = null)
                        } else {
                            message
                        }
                    },
            )
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
): WsEvent.ApprovalRequest? {
    val kindFields = parseApprovalKindFields(map) ?: return null

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
        serverRequestId = null,
        choices = choices,
        allowPermanent = allowPermanent,
        smartDenied = smartDenied,
        kind = kindFields.kind,
        path = kindFields.path,
        access = kindFields.access,
        host = kindFields.host,
        label = kindFields.label,
        risk = kindFields.risk,
    )
}
