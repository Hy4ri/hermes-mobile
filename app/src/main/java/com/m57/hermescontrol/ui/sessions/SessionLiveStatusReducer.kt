package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.LiveSessionSnapshot
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.data.ws.WsEvent

/**
 * Transient state for tracking live active sessions in History.
 */
data class SessionLiveTrackingState(
    val liveStatuses: Map<String, SessionLiveStatus> = emptyMap(),
    val storedIdByRuntimeId: Map<String, String> = emptyMap(),
)

/**
 * Pure reducer managing live active session transitions.
 */
object SessionLiveStatusReducer {
    fun applySnapshot(
        state: SessionLiveTrackingState,
        snapshot: LiveSessionSnapshot,
    ): SessionLiveTrackingState =
        SessionLiveTrackingState(
            liveStatuses = snapshot.statusByStoredId,
            storedIdByRuntimeId = snapshot.storedIdByRuntimeId,
        )

    fun applyWsEvent(
        state: SessionLiveTrackingState,
        event: WsEvent,
    ): SessionLiveTrackingState =
        when (event) {
            is WsEvent.SessionInfo -> reduceSessionInfo(state, event)
            is WsEvent.MessageStart -> reduceMessageStart(state, event)
            is WsEvent.MessageComplete -> reduceMessageComplete(state, event)
            is WsEvent.MessageDone -> reduceMessageDone(state, event)
            is WsEvent.ApprovalRequest -> reduceApprovalRequest(state, event)
            is WsEvent.ClarifyRequest -> reduceClarifyRequest(state, event)
            else -> state
        }

    fun clear(): SessionLiveTrackingState = SessionLiveTrackingState()

    private fun reduceSessionInfo(
        state: SessionLiveTrackingState,
        event: WsEvent.SessionInfo,
    ): SessionLiveTrackingState {
        val data = event.data ?: return state
        val storedId = (data["stored_session_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val runtimeId = (data["session_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val running = data["running"] as? Boolean

        val targetStoredId = storedId ?: runtimeId?.let { state.storedIdByRuntimeId[it] }
        if (targetStoredId == null && runtimeId == null) {
            return state
        }

        val oldStoredId = runtimeId?.let { state.storedIdByRuntimeId[it] }
        val nextMapping =
            if (runtimeId != null && targetStoredId != null) {
                state.storedIdByRuntimeId + (runtimeId to targetStoredId)
            } else {
                state.storedIdByRuntimeId
            }

        if (targetStoredId == null) {
            return state.copy(storedIdByRuntimeId = nextMapping)
        }

        val nextStatuses = state.liveStatuses.toMutableMap()
        if (oldStoredId != null && oldStoredId != targetStoredId) {
            nextStatuses.remove(oldStoredId)
        }

        when (running) {
            true -> nextStatuses[targetStoredId] = SessionLiveStatus.WORKING
            false -> nextStatuses.remove(targetStoredId)
            null -> {
                // If running is not specified, leave status untouched
            }
        }

        return state.copy(
            liveStatuses = nextStatuses,
            storedIdByRuntimeId = nextMapping,
        )
    }

    private fun reduceMessageStart(
        state: SessionLiveTrackingState,
        event: WsEvent.MessageStart,
    ): SessionLiveTrackingState {
        val runtimeId = event.sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return state
        val storedId = state.storedIdByRuntimeId[runtimeId] ?: return state
        if (state.liveStatuses[storedId] == SessionLiveStatus.WORKING) return state
        return state.copy(
            liveStatuses = state.liveStatuses + (storedId to SessionLiveStatus.WORKING),
        )
    }

    private fun reduceMessageComplete(
        state: SessionLiveTrackingState,
        event: WsEvent.MessageComplete,
    ): SessionLiveTrackingState {
        val runtimeId = event.sessionId?.trim()?.takeIf { it.isNotEmpty() }
        val storedId =
            event.storedSessionId?.trim()?.takeIf { it.isNotEmpty() }
                ?: runtimeId?.let { state.storedIdByRuntimeId[it] }
                ?: return state

        if (!state.liveStatuses.containsKey(storedId)) return state
        return state.copy(
            liveStatuses = state.liveStatuses - storedId,
        )
    }

    private fun reduceMessageDone(
        state: SessionLiveTrackingState,
        event: WsEvent.MessageDone,
    ): SessionLiveTrackingState {
        val runtimeId = event.sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return state
        val storedId = state.storedIdByRuntimeId[runtimeId] ?: return state
        if (!state.liveStatuses.containsKey(storedId)) return state
        return state.copy(
            liveStatuses = state.liveStatuses - storedId,
        )
    }

    private fun reduceApprovalRequest(
        state: SessionLiveTrackingState,
        event: WsEvent.ApprovalRequest,
    ): SessionLiveTrackingState {
        val runtimeId = event.sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return state
        val storedId = state.storedIdByRuntimeId[runtimeId] ?: return state
        return state.copy(
            liveStatuses = state.liveStatuses + (storedId to SessionLiveStatus.WAITING),
        )
    }

    private fun reduceClarifyRequest(
        state: SessionLiveTrackingState,
        event: WsEvent.ClarifyRequest,
    ): SessionLiveTrackingState {
        val runtimeId = event.sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return state
        val storedId = state.storedIdByRuntimeId[runtimeId] ?: return state
        return state.copy(
            liveStatuses = state.liveStatuses + (storedId to SessionLiveStatus.WAITING),
        )
    }
}
