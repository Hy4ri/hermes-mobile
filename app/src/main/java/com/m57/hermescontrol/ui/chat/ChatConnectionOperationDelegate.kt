package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.ConnectionOperationSnapshot
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ConnectionPendingAction {
    abstract val opId: String
    abstract val observedSeq: Long
    data class Respond(override val opId: String, val target: String, val approved: Boolean, override val observedSeq: Long) : ConnectionPendingAction()
    data class Continue(override val opId: String, override val observedSeq: Long) : ConnectionPendingAction()
    data class Wake(override val opId: String, override val observedSeq: Long) : ConnectionPendingAction()
}

data class ConnectionOperationError(val message: String, val retryable: Boolean = true)
data class ConnectionOperationUiState(
    val operation: ConnectionOperationSnapshot? = null,
    val pendingAction: ConnectionPendingAction? = null,
    val error: ConnectionOperationError? = null,
)

fun interface ConnectionOperationRequester {
    suspend fun request(method: String, params: Map<String, Any>): Any?
}

/** Session-scoped authoritative reducer for backend-owned connector operations. */
class ChatConnectionOperationDelegate(private val requester: ConnectionOperationRequester) {
    private val _state = MutableStateFlow(ConnectionOperationUiState())
    val state: StateFlow<ConnectionOperationUiState> = _state.asStateFlow()
    private var sessionId: String? = null
    private val settled = LinkedHashSet<String>()

    fun reset(newSessionId: String?) {
        sessionId = newSessionId
        settled.clear()
        _state.value = ConnectionOperationUiState()
    }

    fun accept(snapshot: ConnectionOperationSnapshot): Boolean {
        if (sessionId == null) sessionId = snapshot.sessionId
        if (snapshot.sessionId != null && snapshot.sessionId != sessionId) return false
        if (settled.contains(snapshot.opId)) return false
        val current = _state.value.operation
        if (current != null && current.opId == snapshot.opId && snapshot.seq <= current.seq) return false
        if (current != null && current.opId != snapshot.opId) _state.value = ConnectionOperationUiState()
        _state.value = _state.value.copy(operation = snapshot, pendingAction = if (snapshot.seq > (_state.value.pendingAction?.observedSeq ?: -1)) null else _state.value.pendingAction)
        if (snapshot.settled) markSettled(snapshot.opId)
        return true
    }

    fun clearResumeIfStill(operationId: String?, startedSeq: Long) {
        val current = _state.value.operation
        if (current != null && current.opId == operationId && current.seq <= startedSeq) _state.value = ConnectionOperationUiState()
    }

    suspend fun respond(target: String, env: Map<String, String>, approved: Boolean) {
        val snapshot = begin(ConnectionPendingAction.Respond(currentOp(), target, approved, currentSeq())) ?: return
        val result = mapOf("targets" to listOf(mapOf("name" to target, "status" to if (approved) "approved" else "skipped", "env" to env).filterValues { it != emptyMap<String, String>() }))
        dispatch(WsMethods.CONNECTION_RESPOND, mapOf("session_id" to sessionId.orEmpty(), "op_id" to snapshot.opId, "result" to result))
    }

    suspend fun continueOperation() {
        val snapshot = begin(ConnectionPendingAction.Continue(currentOp(), currentSeq())) ?: return
        dispatch(WsMethods.CONNECTION_RESPOND, mapOf("session_id" to sessionId.orEmpty(), "op_id" to snapshot.opId, "result" to mapOf("settled_by" to "continue")))
    }

    suspend fun wake() {
        val snapshot = begin(ConnectionPendingAction.Wake(currentOp(), currentSeq())) ?: return
        dispatch(WsMethods.CONNECTORS_OPERATION_WAKE, mapOf("session_id" to sessionId.orEmpty(), "op_id" to snapshot.opId))
    }

    private suspend fun dispatch(method: String, params: Map<String, Any>) {
        try { requester.request(method, params) } catch (_: Exception) { _state.value = _state.value.copy(pendingAction = null, error = ConnectionOperationError("Connection operation failed")) }
    }
    private fun begin(action: ConnectionPendingAction): ConnectionOperationSnapshot? = _state.value.operation?.takeIf { _state.value.pendingAction == null && it.opId == action.opId }?.also { _state.value = _state.value.copy(pendingAction = action, error = null) }
    private fun currentOp() = _state.value.operation?.opId ?: ""
    private fun currentSeq() = _state.value.operation?.seq ?: -1
    private fun markSettled(opId: String) { settled += opId; while (settled.size > 32) settled.remove(settled.first()); _state.value = ConnectionOperationUiState() }
}
