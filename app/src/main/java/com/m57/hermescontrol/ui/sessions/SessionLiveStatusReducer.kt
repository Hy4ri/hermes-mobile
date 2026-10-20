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
            // Issue #1642: `session.active_list` only says "waiting". Keep the reason an event already gave us
            // (approval / clarify) instead of downgrading it to the generic label on every poll.
            liveStatuses =
                snapshot.statusByStoredId.mapValues { (storedId, status) ->
                    val previous = state.liveStatuses[storedId]
                    if (status == SessionLiveStatus.WAITING && previous?.isWaiting == true) previous else status
                },
            storedIdByRuntimeId = snapshot.storedIdByRuntimeId,
        )

    /**
     * Stored ids that were live (working or waiting) in [before] and are no longer live in [after]: the turn
     * ended. Used to mark sessions finished when no `message.complete` reached this client (issue #1642).
     */
    fun finishedBetween(
        before: SessionLiveTrackingState,
        after: SessionLiveTrackingState,
    ): Set<String> =
        before.liveStatuses
            .filter { (id, status) -> isLive(status) && !isLive(after.liveStatuses[id]) }
            .keys

    private fun isLive(status: SessionLiveStatus?): Boolean =
        status == SessionLiveStatus.WORKING || status?.isWaiting == true

    fun applyWsEvent(
        state: SessionLiveTrackingState,
        event: WsEvent,
    ): SessionLiveTrackingState =
        when (event) {
            is WsEvent.SessionInfo -> reduceSessionInfo(state, event)

            is WsEvent.MessageStart -> reduceMessageStart(state, event)

            is WsEvent.MessageComplete -> reduceMessageComplete(state, event)

            is WsEvent.MessageDone -> reduceMessageDone(state, event)

            is WsEvent.ApprovalRequest -> markWaiting(state, event.sessionId, SessionLiveStatus.WAITING_APPROVAL)

            is WsEvent.ClarifyRequest -> markWaiting(state, event.sessionId, SessionLiveStatus.WAITING_CLARIFY)

            is WsEvent.SudoRequest -> markWaiting(state, event.sessionId, SessionLiveStatus.WAITING)

            is WsEvent.SecretRequest -> markWaiting(state, event.sessionId, SessionLiveStatus.WAITING)

            is WsEvent.VaultUnlockRequest -> markWaiting(state, event.sessionId, SessionLiveStatus.WAITING)

            is WsEvent.VaultSaveLoginRequest -> markWaiting(state, event.sessionId, SessionLiveStatus.WAITING)

            is WsEvent.VaultCodeRequest -> markWaiting(state, event.sessionId, SessionLiveStatus.WAITING)

            // Issue #1642: the prompt is gone or the agent moved on, so the session is running again.
            is WsEvent.ClarifyExpire -> resumeWorking(state, event.sessionId)

            is WsEvent.SudoExpire -> resumeWorking(state, event.sessionId)

            is WsEvent.SecretExpire -> resumeWorking(state, event.sessionId)

            is WsEvent.VaultUnlockExpire -> resumeWorking(state, event.sessionId)

            is WsEvent.VaultSaveLoginExpire -> resumeWorking(state, event.sessionId)

            is WsEvent.VaultCodeExpire -> resumeWorking(state, event.sessionId)

            is WsEvent.ServerRequestCancelled -> resumeWorking(state, event.sessionId)

            is WsEvent.MessageToken -> resumeWorking(state, event.sessionId)

            is WsEvent.ReasoningDelta -> resumeWorking(state, event.sessionId)

            is WsEvent.ThinkingDelta -> resumeWorking(state, event.sessionId)

            is WsEvent.ToolStart -> resumeWorking(state, event.sessionId)

            is WsEvent.SessionReclaimed -> reduceSessionReclaimed(state, event)

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
            true -> {
                nextStatuses[targetStoredId] = SessionLiveStatus.WORKING
            }

            false -> {
                nextStatuses.remove(targetStoredId)
            }

            null -> {
                // If running is not specified, leave status untouched
            }
        }

        return state.copy(
            liveStatuses = nextStatuses,
            storedIdByRuntimeId = nextMapping,
        )
    }

    /**
     * Issue #1463: a reclaimed runtime is no longer live. Drop its runtime mapping and stale indicator, but keep the
     * indicator when another runtime still maps to the same stored conversation.
     */
    private fun reduceSessionReclaimed(
        state: SessionLiveTrackingState,
        event: WsEvent.SessionReclaimed,
    ): SessionLiveTrackingState {
        val runtimeId = event.sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return state
        val storedId = state.storedIdByRuntimeId[runtimeId] ?: return state
        val remaining = state.storedIdByRuntimeId - runtimeId
        val stillLive = remaining.containsValue(storedId)
        return state.copy(
            liveStatuses = if (stillLive) state.liveStatuses else state.liveStatuses - storedId,
            storedIdByRuntimeId = remaining,
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

    private fun markWaiting(
        state: SessionLiveTrackingState,
        sessionId: String?,
        status: SessionLiveStatus,
    ): SessionLiveTrackingState {
        val runtimeId = sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return state
        val storedId = state.storedIdByRuntimeId[runtimeId] ?: return state
        return state.copy(
            liveStatuses = state.liveStatuses + (storedId to status),
        )
    }

    /** A waiting session whose prompt expired or that produced new output is running again. */
    private fun resumeWorking(
        state: SessionLiveTrackingState,
        sessionId: String?,
    ): SessionLiveTrackingState {
        val runtimeId = sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return state
        val storedId = state.storedIdByRuntimeId[runtimeId] ?: return state
        if (state.liveStatuses[storedId]?.isWaiting != true) return state
        return state.copy(
            liveStatuses = state.liveStatuses + (storedId to SessionLiveStatus.WORKING),
        )
    }
}
