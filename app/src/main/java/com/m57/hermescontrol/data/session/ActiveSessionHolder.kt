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
 *
 * Also propagates session→profile mappings to [SessionProfileTracker] so the
 * notification path can resolve the owning profile for proactive completions
 * from a different profile.
 */
object ActiveSessionHolder {
    private val _activeSessionId = MutableStateFlow<String?>(null)
    private var activeStoredSessionId: String? = null
    private var activeProfileName: String? = null

    /** The currently active chat session id, or null if none is known yet. */
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    /** The currently active profile name, or null. */
    val activeProfile: String? get() = activeProfileName

    /**
     * Update the active session id. Pass null to clear (e.g. on logout).
     *
     * When [profileName] is provided, also registers the session→profile
     * mapping in [SessionProfileTracker] so notification routing can
     * resolve the owning profile for sessions from other profiles.
     */
    @Synchronized
    fun set(
        sessionId: String?,
        storedSessionId: String? = null,
        profileName: String? = null,
    ) {
        _activeSessionId.value = sessionId
        activeStoredSessionId = storedSessionId
        if (profileName != null) {
            activeProfileName = profileName
        }
        // Propagate durable mapping
        if (sessionId != null && profileName != null) {
            SessionProfileTracker.track(sessionId, profileName)
        }
        if (storedSessionId != null && storedSessionId != sessionId && profileName != null) {
            SessionProfileTracker.track(storedSessionId, profileName)
        }
    }

    @Synchronized
    fun resolveStoredSessionId(sessionId: String?): String? =
        if (sessionId == _activeSessionId.value) activeStoredSessionId ?: sessionId else sessionId

    @Synchronized
    fun resolveRuntimeSessionId(sessionId: String): String? =
        _activeSessionId.value.takeIf { activeStoredSessionId == sessionId }

    @Synchronized
    fun clear() {
        _activeSessionId.value = null
        activeStoredSessionId = null
        activeProfileName = null
        // Note: SessionProfileTracker is NOT cleared here — it survives
        // reconnects so the notification path can still resolve profiles
        // for sessions that belong to non-current profiles.
    }
}
