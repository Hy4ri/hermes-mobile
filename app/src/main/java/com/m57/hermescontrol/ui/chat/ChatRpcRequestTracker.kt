package com.m57.hermescontrol.ui.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks an in-flight RPC request associated with a specific session generation
 * and resume sequence to guard against stale results/errors from previous sessions.
 */
data class TrackedSessionRequest(
    val generation: Long,
    val resumeSequence: Long = 0L,
    val sessionId: String? = null,
)

/**
 * Owns RPC id-to-method mapping, session request generations, hydration sequences,
 * and staleness detection.
 *
 * Extracted behavior-preservingly from [ChatViewModel] to ensure deterministic
 * RPC tracking across session creation, switches, resumes, and reconnects.
 */
class ChatRpcRequestTracker(
    private val getCurrentSessionId: () -> String?,
) {
    /** Maps an in-flight RPC id to its method for UI error labeling. */
    private val idToMethod = ConcurrentHashMap<String, String>()
    private val sessionRequestById = ConcurrentHashMap<String, TrackedSessionRequest>()

    var sessionGeneration: Long = 0L
        private set

    var resumeRequestSequence: Long = 0L
        private set
    var activeResumeRequestSequence: Long = 0L
        private set

    var hydrationRequestSequence: Long = 0L
        private set
    var activeHydrationRequestSequence: Long = 0L
        private set

    var resumedGeneration: Long = -1L
    var hydratedGeneration: Long = -1L

    fun nextSessionGeneration(): Long {
        sessionGeneration++
        return sessionGeneration
    }

    fun nextResumeSequence(): Long {
        resumeRequestSequence++
        activeResumeRequestSequence = resumeRequestSequence
        return resumeRequestSequence
    }

    fun nextHydrationSequence(): Long {
        hydrationRequestSequence++
        activeHydrationRequestSequence = hydrationRequestSequence
        return hydrationRequestSequence
    }

    fun trackRequest(
        id: String,
        method: String,
    ) {
        idToMethod[id] = method
    }

    fun trackSessionRequest(
        id: String,
        method: String,
        generation: Long,
        resumeSequence: Long = 0L,
        sessionId: String? = null,
    ) {
        sessionRequestById[id] = TrackedSessionRequest(generation, resumeSequence, sessionId)
        trackRequest(id, method)
    }

    fun isCurrentSessionRequest(
        sessionId: String,
        generation: Long,
    ): Boolean = generation == sessionGeneration && sessionId == getCurrentSessionId()

    fun isCurrentHydration(
        sessionId: String,
        generation: Long,
        requestSequence: Long,
    ): Boolean = requestSequence == activeHydrationRequestSequence && isCurrentSessionRequest(sessionId, generation)

    fun isStaleSessionRequest(id: String): Boolean = sessionRequestById[id]?.let(::isStaleSessionRequest) == true

    fun isStaleSessionRequest(request: TrackedSessionRequest): Boolean =
        request.generation != sessionGeneration ||
            (request.sessionId != null && request.sessionId != getCurrentSessionId()) ||
            (request.resumeSequence != 0L && request.resumeSequence != activeResumeRequestSequence)

    fun removeMethod(id: String): String? = idToMethod.remove(id)

    fun removeSessionRequest(id: String): TrackedSessionRequest? = sessionRequestById.remove(id)

    fun forgetRequest(id: String) {
        idToMethod.remove(id)
        sessionRequestById.remove(id)
    }

    fun resetSessionStateTracking() {
        resumedGeneration = -1L
        hydratedGeneration = -1L
    }
}
