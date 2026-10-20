package com.m57.hermescontrol.data.session

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Scope key used by [SessionAttentionStore] for [scope], or null when no server is configured. */
fun sessionAttentionScopeKey(scope: DataScope?): String? = scope?.persistentKey("session-attention")

/**
 * Issue #1642: turns `message.complete` into [SessionAttentionStore] marks for every session this
 * client hears about, on any screen and in the background. History alone cannot do this: it only
 * observes events while it is on screen.
 */
internal class SessionAttentionRecorder(
    private val currentScopeKey: () -> String?,
    private val activeRuntimeToStored: (String) -> String? = { runtimeId ->
        if (runtimeId ==
            ActiveSessionHolder.activeSessionId.value
        ) {
            ActiveSessionHolder.resolveStoredSessionId(runtimeId)
        } else {
            null
        }
    },
) {
    private val storedIdByRuntimeId = mutableMapOf<String, String>()

    fun onEvent(event: WsEvent) {
        when (event) {
            is WsEvent.SessionInfo -> {
                val data = event.data ?: return
                val runtimeId = (data["session_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return
                val storedId = (data["stored_session_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return
                storedIdByRuntimeId[runtimeId] = storedId
            }

            is WsEvent.SessionReclaimed -> {
                event.sessionId?.trim()?.let(storedIdByRuntimeId::remove)
            }

            is WsEvent.MessageStart -> {
                // A new turn makes the previous result stale; its own completion marks it again.
                val storedId = resolve(event.sessionId, null) ?: return
                val scopeKey = currentScopeKey() ?: return
                SessionAttentionStore.clear(scopeKey, storedId)
            }

            is WsEvent.MessageComplete -> {
                onComplete(event)
            }

            else -> {}
        }
    }

    private fun onComplete(event: WsEvent.MessageComplete) {
        // The user stopped this turn, so there is no result to look at.
        val status = event.rawPayload?.get("status") as? String
        if (status == "interrupted") return
        val storedId = resolve(event.sessionId, event.storedSessionId) ?: return
        val scopeKey = currentScopeKey() ?: return
        SessionAttentionStore.markFinished(scopeKey, storedId, failed = status == "error")
    }

    private fun resolve(
        sessionId: String?,
        storedSessionId: String?,
    ): String? {
        val runtimeId = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        return storedSessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: runtimeId?.let { storedIdByRuntimeId[it] ?: activeRuntimeToStored(it) }
    }
}

object SessionAttentionTracker {
    private var job: Job? = null

    /** Start recording completions for the life of [scope]. Idempotent. */
    fun start(
        scope: CoroutineScope,
        events: Flow<WsEvent> = HermesWsClient.events,
    ) {
        if (job?.isActive == true) return
        val recorder =
            SessionAttentionRecorder(currentScopeKey = { sessionAttentionScopeKey(AuthManager.currentDataScope()) })
        job = scope.launch { events.collect(recorder::onEvent) }
    }
}
