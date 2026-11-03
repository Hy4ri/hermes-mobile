package com.m57.hermescontrol.data.ws

/**
 * Wire identity of one prompt turn's streamed reply (hermes-agent
 * `tui_gateway/stream_segments.py`, v0.22.6).
 *
 * `message.delta` carries [turnId] + [segment] (interim seals before it) +
 * [offset] (UTF-16 code units of that segment already sent).
 * `message.interim` carries [turnId] + the [segment] it seals; [offset] is null.
 */
data class SegmentStamp(
    val turnId: String,
    val segment: Int,
    val offset: Int? = null,
)

/**
 * Reads the stamp from a parsed `message.delta` / `message.interim` payload.
 * Returns null for older backends and non-prompt emitters, which then take the old path.
 */
internal fun segmentStampOf(
    turnId: Any?,
    segment: Any?,
    offset: Any?,
): SegmentStamp? {
    val id = (turnId as? String)?.takeIf { it.isNotEmpty() } ?: return null
    val seg = nonNegativeInt(segment) ?: return null
    return SegmentStamp(id, seg, nonNegativeInt(offset))
}

internal fun turnIdOf(value: Any?): String? = (value as? String)?.takeIf { it.isNotEmpty() }

private fun nonNegativeInt(value: Any?): Int? =
    when (value) {
        is Int -> value.takeIf { it >= 0 }
        is Long -> value.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        else -> null
    }

/**
 * Result of admitting a stream event through [StreamSegmentGate].
 */
sealed class GateAdmission {
    /** The event should be emitted / applied to the transcript (possibly trimmed). */
    data class Accept(
        val event: WsEvent,
    ) : GateAdmission()

    /** The event is a duplicate / re-sent frame that has already been applied; drop it. */
    data object Drop : GateAdmission()

    /**
     * A gap in offsets was detected: the incoming delta begins after [expectedOffset].
     * To prevent appending disconnected text or skipping missing tokens permanently,
     * the consumer quarantines this turn until authoritative completion/reconciliation.
     */
    data class GapDetected(
        val sessionId: String,
        val turnId: String,
        val expectedOffset: Int,
        val incomingOffset: Int,
    ) : GateAdmission()
}

/**
 * Drops re-sent reply frames at the application boundary (issue #382).
 *
 * The gate keeps per-session, per-turn deduplication history with bounded retention
 * (LRU eviction per session, and LRU eviction of turns within a session):
 * - a stamped delta passes only its unseen tail (a full repeat is dropped),
 * - missing or null offset deltas pass through unchanged,
 * - gap detection: if incoming offset > appliedEnd in the current segment, a [GateAdmission.GapDetected]
 *   is returned and the turn is quarantined from further incremental appends,
 * - duplicate segment seals (`message.interim`) are dropped using monotonic highest sealed segment,
 * - duplicate `message.start` within known turns or from earlier retired turns (by monotonic turnIndex) are dropped,
 * - accessing an older turn does NOT rewind the session's highest observed turn counter,
 * - scopes are isolated (by DataScope key) so switching profile/server invalidates cache,
 *   while normal same-scope reconnect preserves replay deduplication.
 *
 * Unstamped frames pass unchanged. Identity, not content, decides: text the
 * agent repeats in a later segment still passes.
 */
internal class StreamSegmentGate(
    private val scopeKeyProvider: () -> String = { "default" },
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val maxTurnsPerSession: Int = DEFAULT_MAX_TURNS_PER_SESSION,
) {
    companion object {
        const val DEFAULT_MAX_SESSIONS = 50
        const val DEFAULT_MAX_TURNS_PER_SESSION = 20
    }

    private data class TurnProgress(
        val turnId: String,
        val turnIndex: Long,
        var highestSegment: Int = 0,
        var appliedEnd: Int = 0,
        var highestSealedSegment: Int = -1,
        var started: Boolean = false,
        var isQuarantined: Boolean = false,
        var isCompleted: Boolean = false,
    )

    private class SessionState(
        val maxTurns: Int,
    ) {
        var turnCounter: Long = 0L
        var highestObservedTurnIndex: Long = 0L
        val turns = BoundedLruMap<String, TurnProgress>(maxTurns)
    }

    private class BoundedLruMap<K, V>(
        private val maxSize: Int,
    ) : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxSize
    }

    private var currentScopeKey: String = scopeKeyProvider()

    // sessionId -> SessionState
    private val sessions = BoundedLruMap<String, SessionState>(maxSessions)

    @Synchronized
    fun reset() {
        sessions.clear()
        currentScopeKey = scopeKeyProvider()
    }

    @Synchronized
    fun resetSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    /**
     * Marks a turn as terminally completed/retired.
     * Rejects all subsequent starts, deltas, and seals for this turn.
     */
    @Synchronized
    fun markTurnCompleted(
        sessionId: String,
        turnId: String,
    ) {
        sessions[sessionId]?.turns?.get(turnId)?.let {
            it.isCompleted = true
            it.isQuarantined = false
        }
    }

    @Synchronized
    private fun checkScope() {
        val activeKey = scopeKeyProvider()
        if (activeKey != currentScopeKey) {
            sessions.clear()
            currentScopeKey = activeKey
        }
    }

    private fun getOrCreateSession(sessionId: String): SessionState {
        var state = sessions[sessionId]
        if (state == null) {
            state = SessionState(maxTurnsPerSession)
            sessions[sessionId] = state
        }
        return state
    }

    private fun getOrCreateTurn(
        session: SessionState,
        turnId: String,
    ): TurnProgress {
        var turn = session.turns[turnId]
        if (turn == null) {
            val idx = ++session.turnCounter
            turn = TurnProgress(turnId = turnId, turnIndex = idx)
            session.turns[turnId] = turn
        }
        // Advance highest observed turn index monotonically without rewinding on older turns
        if (turn.turnIndex > session.highestObservedTurnIndex) {
            session.highestObservedTurnIndex = turn.turnIndex
        }
        return turn
    }

    /**
     * Evaluates admission of [event]. Returns [GateAdmission.Accept] with the event
     * to apply (possibly trimmed), [GateAdmission.Drop] if already applied, or
     * [GateAdmission.GapDetected] if missing text was skipped.
     */
    @Synchronized
    fun evaluate(event: WsEvent): GateAdmission {
        checkScope()
        return when (event) {
            is WsEvent.MessageStart -> evaluateStart(event)
            is WsEvent.MessageToken -> evaluateDelta(event)
            is WsEvent.MessageInterim -> evaluateSeal(event)
            else -> GateAdmission.Accept(event)
        }
    }

    /** Backward-compatible helper returning WsEvent? (null on Drop or Gap). */
    @Synchronized
    fun admit(event: WsEvent): WsEvent? =
        when (val res = evaluate(event)) {
            is GateAdmission.Accept -> res.event
            is GateAdmission.Drop, is GateAdmission.GapDetected -> null
        }

    private fun evaluateStart(event: WsEvent.MessageStart): GateAdmission {
        val turnId = event.turnId ?: return GateAdmission.Accept(event)
        val sid = event.sessionId.orEmpty()
        val session = getOrCreateSession(sid)
        val existing = session.turns[turnId]

        if (existing != null) {
            // Drop if this turn was already started, completed, or if a newer turn was already observed
            if (existing.started || existing.isCompleted || existing.turnIndex < session.highestObservedTurnIndex) {
                return GateAdmission.Drop
            }
        } else if (session.highestObservedTurnIndex > 0 && session.turns.isNotEmpty()) {
            // A brand new turnId with a higher turnIndex will be created below
        }

        val turn = getOrCreateTurn(session, turnId)
        turn.started = true
        return GateAdmission.Accept(event)
    }

    private fun evaluateDelta(event: WsEvent.MessageToken): GateAdmission {
        val stamp = event.stamp ?: return GateAdmission.Accept(event)
        val offset = stamp.offset ?: return GateAdmission.Accept(event) // offset == null passes through
        val sid = event.sessionId.orEmpty()
        val session = getOrCreateSession(sid)
        val turn = getOrCreateTurn(session, stamp.turnId)
        val end = offset + event.token.length

        // If turn was already completed or quarantined due to a gap, drop further incremental deltas
        if (turn.isCompleted || turn.isQuarantined) {
            return GateAdmission.Drop
        }

        // An empty chunk carries no text to duplicate (e.g. segment boundary marker)
        if (event.token.isEmpty()) return GateAdmission.Accept(event)

        if (stamp.segment < turn.highestSegment) {
            // Already progressed past this segment in this turn
            return GateAdmission.Drop
        }

        if (stamp.segment > turn.highestSegment) {
            // Progressing to a new segment. Segment must start at offset 0.
            if (offset > 0) {
                turn.isQuarantined = true
                return GateAdmission.GapDetected(
                    sessionId = sid,
                    turnId = stamp.turnId,
                    expectedOffset = 0,
                    incomingOffset = offset,
                )
            }
            turn.highestSegment = stamp.segment
            turn.appliedEnd = end
            return GateAdmission.Accept(event)
        }

        // Same segment: check offset progress
        if (end <= turn.appliedEnd) {
            // Completely already applied
            return GateAdmission.Drop
        }

        if (offset > turn.appliedEnd) {
            // Gap detected: delta starts after current appliedEnd. Quarantine turn.
            turn.isQuarantined = true
            return GateAdmission.GapDetected(
                sessionId = sid,
                turnId = stamp.turnId,
                expectedOffset = turn.appliedEnd,
                incomingOffset = offset,
            )
        }

        val overlap = turn.appliedEnd - offset
        turn.appliedEnd = end
        return if (overlap > 0) {
            val tail = event.token.substring(overlap)
            GateAdmission.Accept(event.copy(token = tail))
        } else {
            GateAdmission.Accept(event)
        }
    }

    private fun evaluateSeal(event: WsEvent.MessageInterim): GateAdmission {
        val stamp = event.stamp ?: return GateAdmission.Accept(event)
        val sid = event.sessionId.orEmpty()
        val session = getOrCreateSession(sid)
        val turn = getOrCreateTurn(session, stamp.turnId)

        if (turn.isCompleted || stamp.segment <= turn.highestSealedSegment) {
            // Turn completed or segment already sealed
            return GateAdmission.Drop
        }

        turn.highestSealedSegment = stamp.segment
        if (stamp.segment >= turn.highestSegment) {
            turn.highestSegment = stamp.segment + 1
            turn.appliedEnd = 0
        }
        return GateAdmission.Accept(event)
    }
}
