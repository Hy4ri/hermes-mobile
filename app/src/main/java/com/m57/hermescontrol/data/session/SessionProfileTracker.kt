package com.m57.hermescontrol.data.session

import java.util.concurrent.ConcurrentHashMap

/**
 * Durable session→profile mapping that survives WebSocket reconnects and
 * profile switches.
 *
 * Populated by [BotsViewModel] (canonical session from profile data),
 * [ChatViewModel] (sessions created/opened), and [GroupChatViewModel]
 * (group-chat sessions). Read by the notification path to resolve the
 * profile that owns a session before navigating or replying.
 *
 * Unlike [ActiveSessionHolder] (which is cleared on every WebSocket
 * disconnect), this tracker keeps its mappings until explicit [clear]
 * or [removeProfile] (e.g. on logout or profile deletion).
 */
object SessionProfileTracker {
    private val sessionToProfile = ConcurrentHashMap<String, String>()
    private val profileToSession = ConcurrentHashMap<String, String>()

    /**
     * Record that [sessionId] (the stored/idempotent session id) belongs
     * to [profileName]. Overwrites any prior mapping for the same key.
     */
    fun track(
        sessionId: String,
        profileName: String,
    ) {
        if (sessionId.isBlank()) return
        sessionToProfile[sessionId] = profileName
    }

    /**
     * Record that [profileName]'s canonical session is [sessionId].
     * Does NOT overwrite an existing session→profile mapping (first-wins).
     */
    fun trackCanonical(
        sessionId: String,
        profileName: String,
    ) {
        if (sessionId.isBlank()) return
        sessionToProfile.putIfAbsent(sessionId, profileName)
        profileToSession[profileName] = sessionId
    }

    /**
     * Returns the profile name that owns [sessionId], or null if unknown.
     */
    fun resolveProfile(sessionId: String): String? = sessionToProfile[sessionId]

    /**
     * Returns the canonical session id for [profileName], or null.
     */
    fun canonicalSessionId(profileName: String): String? = profileToSession[profileName]

    /**
     * Remove all mappings for [profileName] — both its canonical session
     * and any tracked sessions owned by it.
     */
    fun removeProfile(profileName: String) {
        val canonId = profileToSession.remove(profileName)
        if (canonId != null) {
            sessionToProfile.remove(canonId)
        }
        // Clean up any stray entries owned by this profile
        sessionToProfile.entries
            .filter { it.value == profileName }
            .forEach { sessionToProfile.remove(it.key) }
    }

    /**
     * Clear all mappings (e.g. on logout or full reset).
     */
    fun clear() {
        sessionToProfile.clear()
        profileToSession.clear()
    }

    /**
     * Return the known profiles tracked by this holder (for diagnostics).
     */
    val trackedProfiles: Set<String>
        get() = profileToSession.keys.toSet()

    /**
     * Whether a profile mapping is known.
     */
    fun hasProfile(profileName: String): Boolean = profileToSession.containsKey(profileName)
}
