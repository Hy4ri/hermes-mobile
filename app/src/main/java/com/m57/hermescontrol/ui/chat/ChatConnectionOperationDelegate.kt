package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.ConnectionOperationSnapshot
import com.m57.hermescontrol.data.model.ConnectorError
import com.m57.hermescontrol.data.ws.ConnectorParser
import com.m57.hermescontrol.data.ws.ConnectorRepository
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ConnectionPendingAction {
    abstract val opId: String
    abstract val observedSeq: Long

    data class Respond(
        override val opId: String,
        val target: String,
        val approved: Boolean,
        override val observedSeq: Long,
    ) : ConnectionPendingAction()

    data class Continue(
        override val opId: String,
        override val observedSeq: Long,
    ) : ConnectionPendingAction()

    data class Wake(
        override val opId: String,
        override val observedSeq: Long,
    ) : ConnectionPendingAction()
}

data class ConnectionOperationError(
    val message: String,
    val retryable: Boolean = true,
)

data class ConnectionOperationUiState(
    val operation: ConnectionOperationSnapshot? = null,
    val pendingAction: ConnectionPendingAction? = null,
    val error: ConnectionOperationError? = null,
)

fun interface ConnectionOperationRequester {
    suspend fun request(
        method: String,
        params: Map<String, Any>,
    ): Any?
}

internal class ConnectionResumeCheckpoint(
    internal val generation: Long,
    internal val operationId: String?,
    internal val seq: Long?,
)

/** Session-scoped authoritative reducer for backend-owned connector operations. */
class ChatConnectionOperationDelegate(
    private val requester: ConnectionOperationRequester,
) {
    private val _state = MutableStateFlow(ConnectionOperationUiState())
    val state: StateFlow<ConnectionOperationUiState> = _state.asStateFlow()
    private var sessionId: String? = null
    private var generation = 0L
    private val settled = LinkedHashSet<String>()

    @Synchronized
    fun reset(newSessionId: String? = null) {
        generation++
        sessionId = newSessionId
        settled.clear()
        _state.value = ConnectionOperationUiState()
    }

    /** Rebinds the same stored chat to its current runtime session after create/resume/reconnect. */
    @Synchronized
    fun bindSession(newSessionId: String) {
        if (newSessionId.isNotBlank()) sessionId = newSessionId
    }

    @Synchronized
    internal fun resumeCheckpoint(): ConnectionResumeCheckpoint =
        ConnectionResumeCheckpoint(
            generation = generation,
            operationId = _state.value.operation?.opId,
            seq = _state.value.operation?.seq,
        )

    fun acceptRequest(snapshot: ConnectionOperationSnapshot): Boolean = accept(snapshot, allowReplacement = true)

    fun acceptUpdate(snapshot: ConnectionOperationSnapshot): Boolean = accept(snapshot, allowReplacement = false)

    @Synchronized
    private fun accept(
        snapshot: ConnectionOperationSnapshot,
        allowReplacement: Boolean,
    ): Boolean {
        if (!matchesSession(snapshot)) return false
        if (settled.contains(snapshot.opId)) return false
        val current = _state.value.operation
        if (current != null && current.opId == snapshot.opId && snapshot.seq <= current.seq) return false
        if (current != null && current.opId != snapshot.opId && !allowReplacement) return false
        val pending = _state.value.pendingAction
        val keepPending =
            pending != null &&
                pending.opId == snapshot.opId &&
                snapshot.seq <= pending.observedSeq
        _state.value =
            ConnectionOperationUiState(
                operation = snapshot,
                pendingAction = pending.takeIf { keepPending },
            )
        if (snapshot.settled) markSettled(snapshot.opId)
        return true
    }

    /** Applies a resume snapshot only if it cannot regress a live event received after resume began. */
    @Synchronized
    internal fun reconcileResume(
        snapshot: ConnectionOperationSnapshot?,
        checkpoint: ConnectionResumeCheckpoint,
    ): Boolean {
        if (checkpoint.generation != generation) return false
        val current = _state.value.operation
        val unchanged = current?.opId == checkpoint.operationId && current?.seq == checkpoint.seq
        if (snapshot == null) {
            if (unchanged) {
                _state.value = ConnectionOperationUiState()
                return true
            }
            return false
        }
        if (!unchanged && current?.opId != snapshot.opId) return false
        return accept(snapshot, allowReplacement = unchanged)
    }

    suspend fun respond(
        target: String,
        env: Map<String, String>,
        approved: Boolean,
    ) {
        val current = _state.value.operation ?: return
        val targetSnapshot = current.targets.firstOrNull { it.name == target } ?: return
        val safeEnv =
            if (approved) {
                val allowed = targetSnapshot.requiredEnv.mapTo(HashSet()) { it.name }
                env.filterKeys(allowed::contains)
            } else {
                emptyMap()
            }
        val snapshot =
            begin(
                ConnectionPendingAction.Respond(
                    opId = current.opId,
                    target = target,
                    approved = approved,
                    observedSeq = current.seq,
                ),
            ) ?: return
        val answer =
            buildMap<String, Any> {
                put("name", target)
                put("status", if (approved) "approved" else "skipped")
                if (safeEnv.isNotEmpty()) put("env", safeEnv)
            }
        dispatch(
            method = WsMethods.CONNECTION_RESPOND,
            params =
                mapOf(
                    "owner" to ConnectorRepository.sessionOwner(requireSessionId()),
                    "op_id" to snapshot.opId,
                    "result" to mapOf("targets" to listOf(answer)),
                ),
        )
    }

    suspend fun continueOperation() {
        val snapshot = begin(ConnectionPendingAction.Continue(currentOp(), currentSeq())) ?: return
        dispatch(
            method = WsMethods.CONNECTION_RESPOND,
            params =
                mapOf(
                    "owner" to ConnectorRepository.sessionOwner(requireSessionId()),
                    "op_id" to snapshot.opId,
                    "result" to mapOf("settled_by" to "continue"),
                ),
        )
    }

    suspend fun wake(expectedOpId: String) {
        val current = _state.value.operation ?: return
        if (current.opId != expectedOpId) return
        val snapshot = begin(ConnectionPendingAction.Wake(current.opId, current.seq)) ?: return
        dispatch(
            method = WsMethods.CONNECTORS_OPERATION_WAKE,
            params = mapOf("owner" to ConnectorRepository.sessionOwner(requireSessionId()), "op_id" to snapshot.opId),
            clearOnSuccess = true,
            unknownOperationSettles = true,
        )
    }

    private suspend fun dispatch(
        method: String,
        params: Map<String, Any>,
        clearOnSuccess: Boolean = false,
        unknownOperationSettles: Boolean = false,
    ) {
        val action = _state.value.pendingAction
        val actionGeneration = generation
        try {
            requester.request(method, params)
            // Success only acknowledges receipt. Keep the exactly-once lock until
            // a newer authoritative snapshot advances the operation sequence.
            if (clearOnSuccess && generation == actionGeneration && _state.value.pendingAction == action) {
                _state.value = _state.value.copy(pendingAction = null)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (unknownOperationSettles && error is HermesWsClient.HermesRpcException && error.code == 4004) {
                if (generation == actionGeneration && _state.value.pendingAction == action) {
                    action?.opId?.let(::markSettled)
                }
            } else if (generation == actionGeneration && _state.value.pendingAction == action) {
                _state.value =
                    _state.value.copy(
                        pendingAction = null,
                        error = operationError(error),
                    )
            }
        }
    }

    @Synchronized
    private fun begin(action: ConnectionPendingAction): ConnectionOperationSnapshot? {
        val current = _state.value.operation ?: return null
        if (sessionId.isNullOrBlank() ||
            _state.value.pendingAction != null ||
            current.opId != action.opId ||
            current.seq != action.observedSeq
        ) {
            return null
        }
        _state.value = _state.value.copy(pendingAction = action, error = null)
        return current
    }

    /** Typed, sanitized error: ownership/runtime failures cannot succeed on retry (#1281). */
    private fun operationError(error: Exception): ConnectionOperationError {
        val rpc = error as? HermesWsClient.HermesRpcException ?: return ConnectionOperationError(REQUEST_FAILED)
        return when (ConnectorParser.mapRpcError(code = rpc.code, data = rpc.data)) {
            is ConnectorError.NotOwner -> ConnectionOperationError(NOT_OWNER, retryable = false)
            is ConnectorError.UnsupportedRuntime -> ConnectionOperationError(UNSUPPORTED_RUNTIME, retryable = false)
            else -> ConnectionOperationError(REQUEST_FAILED)
        }
    }

    private fun matchesSession(snapshot: ConnectionOperationSnapshot): Boolean =
        !sessionId.isNullOrBlank() && snapshot.sessionId == sessionId

    private fun requireSessionId(): String = checkNotNull(sessionId?.takeIf { it.isNotBlank() })

    private fun currentOp(): String =
        _state.value.operation
            ?.opId
            .orEmpty()

    private fun currentSeq(): Long = _state.value.operation?.seq ?: -1L

    private fun markSettled(opId: String) {
        settled += opId
        while (settled.size > MAX_SETTLED_IDS) settled.remove(settled.first())
        _state.value = ConnectionOperationUiState()
    }

    companion object {
        private const val MAX_SETTLED_IDS = 32
        const val REQUEST_FAILED = "request_failed"
        const val NOT_OWNER = "not_owner"
        const val UNSUPPORTED_RUNTIME = "unsupported_runtime"
    }
}
