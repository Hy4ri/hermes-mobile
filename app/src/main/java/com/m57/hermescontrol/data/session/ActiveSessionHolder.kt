package com.m57.hermescontrol.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide mirror of the active chat session id.
 *
 * The desktop `process.list` / `process.kill` RPCs are session-scoped — the
 * gateway returns `4001 "session not found"` without a valid `session_id`, and
 * mobile has no global "current session" holder (ChatViewModel keeps it
 * privately). This singleton holds the last-known active session id so
 * session-scoped drawer screens (e.g. the Processes screen, issue #532) can
 * issue those RPCs. ChatViewModel writes here on every switch/resume; it is a
 * best-effort mirror and may be null when no chat session has been opened yet.
 */
object ActiveSessionHolder {
    private val _activeSessionId = MutableStateFlow<String?>(null)

    /** The currently active chat session id, or null if none is known yet. */
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /** Update the active session id. Pass null to clear (e.g. on logout). */
    fun set(sessionId: String?) {
        _activeSessionId.value = sessionId
    }
}
