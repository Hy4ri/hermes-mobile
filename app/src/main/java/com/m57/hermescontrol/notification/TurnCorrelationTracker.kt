package com.m57.hermescontrol.notification

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Durable lower bound for one mobile-originated turn: the highest REST
 * transcript row id that existed immediately BEFORE `prompt.submit` left the
 * device.
 *
 * The id is the gateway's AUTOINCREMENT `messages.id`, so every row persisted
 * by the turn itself is strictly greater than [beforeMessageId]. That gives the
 * notification layer a turn-scoped window it can search without text
 * heuristics, row ordering, or cross-machine clocks.
 *
 * Captured before the prompt is submitted, never after: a fast turn can persist
 * its assistant row before a post-submit snapshot request finishes, which would
 * place the reply inside the "before" snapshot (and a lower bound can then never
 * exclude it).
 */
data class TurnBoundary(
    val scopeId: String,
    val sessionId: String,
    val beforeMessageId: Int,
    /** Monotonic arm order — a stale clear must not remove a newer turn's boundary. */
    val generation: Long,
    val armedAt: Long,
) {
    fun matchesKey(
        otherScopeId: String,
        otherSessionId: String,
    ): Boolean = scopeId == otherScopeId && sessionId == otherSessionId
}

/** Persistence seam so the tracker can be unit-tested without an Android context. */
internal interface TurnBoundaryStore {
    fun read(): List<TurnBoundary>

    fun write(boundaries: List<TurnBoundary>)
}

/**
 * SharedPreferences-backed store — a handful of tiny `scope|session -> state`
 * rows, so a process death during a running turn does not lose the boundary.
 */
internal class PrefsTurnBoundaryStore(
    context: Context,
) : TurnBoundaryStore {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): List<TurnBoundary> =
        prefs.all.mapNotNull { (key, value) -> decodeBoundary(key, value as? String) }

    override fun write(boundaries: List<TurnBoundary>) {
        val editor = prefs.edit().clear()
        boundaries.forEach { editor.putString(encodeKey(it.scopeId, it.sessionId), encodeValue(it)) }
        editor.apply()
    }

    internal companion object {
        const val PREFS_NAME = "hermes_turn_boundaries"
        private const val KEY_SEPARATOR = '|'

        fun encodeKey(
            scopeId: String,
            sessionId: String,
        ): String = "$scopeId$KEY_SEPARATOR$sessionId"

        fun encodeValue(boundary: TurnBoundary): String =
            "${boundary.beforeMessageId}:${boundary.generation}:${boundary.armedAt}"

        fun decodeBoundary(
            key: String,
            value: String?,
        ): TurnBoundary? {
            val separator = key.indexOf(KEY_SEPARATOR)
            if (separator <= 0 || value == null) return null
            val parts = value.split(':')
            if (parts.size != 3) return null
            val beforeMessageId = parts[0].toIntOrNull() ?: return null
            val generation = parts[1].toLongOrNull() ?: return null
            val armedAt = parts[2].toLongOrNull() ?: return null
            return TurnBoundary(
                scopeId = key.substring(0, separator),
                sessionId = key.substring(separator + 1),
                beforeMessageId = beforeMessageId,
                generation = generation,
                armedAt = armedAt,
            )
        }
    }
}

/**
 * Turn-boundary registry for reply-notification correlation.
 *
 * A boundary is armed per (profile scope, stored session id) immediately before
 * a mobile-originated prompt is submitted, and consumed when a completion for
 * that session is handled. Sessions that never had a boundary (desktop/TUI
 * turns, another connected client, a failed capture, queued or group-chat
 * turns) simply fail closed: the reply notification is posted without a durable
 * row id and therefore is never auto-dismissed from REST hydration alone.
 *
 * Scope isolation is structural — a boundary is only visible to the exact
 * profile that armed it, so switching profiles makes the old boundary
 * unreachable instead of resolvable against the wrong namespace.
 */
object TurnCorrelationTracker {
    /** Bound the registry: stale turns must not accumulate across app restarts. */
    private const val MAX_ENTRIES = 32

    /** A boundary older than this belongs to a turn nobody is waiting on anymore. */
    private const val MAX_AGE_MS = 6 * 60 * 60 * 1000L

    private val generationCounter = AtomicLong(0L)
    private val lock = Any()

    @Volatile
    private var cache: List<TurnBoundary> = emptyList()

    @Volatile
    private var store: TurnBoundaryStore? = null

    /** Idempotent — called once from [com.m57.hermescontrol.HermesControlApp]. */
    fun attach(context: Context) {
        synchronized(lock) {
            if (store != null) return
            // Never let persistence break app startup: an unusable context
            // degrades to in-memory boundaries.
            val prefsStore = runCatching { PrefsTurnBoundaryStore(context) }.getOrNull() ?: return
            store = prefsStore
            val restored = prune(runCatching { prefsStore.read() }.getOrDefault(emptyList()))
            cache = restored
            reserveGenerationFloor(restored)
        }
    }

    /** Test seam: swap the persistent store (or detach with `null`). */
    @VisibleForTesting
    internal fun attachStore(store: TurnBoundaryStore?) {
        synchronized(lock) {
            this.store = store
            val restored = store?.let { prune(it.read()) } ?: emptyList()
            cache = restored
            reserveGenerationFloor(restored)
        }
    }

    /**
     * Records the pre-submit high-watermark. Returns the armed boundary, or null
     * when the identity is unusable (blank scope/session, no rows and no
     * confirmed read).
     */
    fun armBoundary(
        scopeId: String,
        sessionId: String,
        beforeMessageId: Int,
        now: Long = System.currentTimeMillis(),
    ): TurnBoundary? {
        if (scopeId.isBlank() || sessionId.isBlank() || beforeMessageId < 0) return null
        val boundary =
            TurnBoundary(
                scopeId = scopeId,
                sessionId = sessionId,
                beforeMessageId = beforeMessageId,
                generation = generationCounter.incrementAndGet(),
                armedAt = now,
            )
        synchronized(lock) {
            val next = prune(cache.filterNot { it.matchesKey(scopeId, sessionId) } + boundary, now = now)
            cache = next
            runCatching { store?.write(next) }
        }
        return boundary
    }

    fun boundaryFor(
        scopeId: String,
        sessionId: String,
        now: Long = System.currentTimeMillis(),
    ): TurnBoundary? {
        if (scopeId.isBlank() || sessionId.isBlank()) return null
        return synchronized(lock) {
            cache.firstOrNull { it.matchesKey(scopeId, sessionId) && isFresh(it, now) }
        }
    }

    /**
     * Consumes a boundary. [generation] guards the race where a newer turn armed
     * a replacement while an older completion was still being correlated.
     */
    fun clearBoundary(
        scopeId: String,
        sessionId: String,
        generation: Long? = null,
    ): Boolean {
        synchronized(lock) {
            val existing = cache.firstOrNull { it.matchesKey(scopeId, sessionId) } ?: return false
            if (generation != null && existing.generation != generation) return false
            val next = cache.filterNot { it === existing }
            cache = next
            runCatching { store?.write(next) }
            return true
        }
    }

    private fun prune(
        boundaries: List<TurnBoundary>,
        now: Long = System.currentTimeMillis(),
    ): List<TurnBoundary> =
        boundaries
            .filter { isFresh(it, now) }
            .sortedByDescending { it.armedAt }
            .take(MAX_ENTRIES)

    /**
     * Keeps generations unique across process death.
     *
     * The counter lives in memory only, so after a restart a restored boundary
     * (generation N) and the next armed boundary would both be N — and the
     * stale-clear guard, which exists precisely to stop an old completion's
     * clear from removing a newer turn's boundary, would stop protecting
     * anything. Lifting the counter above the recovered generations keeps the
     * guard meaningful.
     */
    private fun reserveGenerationFloor(restored: List<TurnBoundary>) {
        val maxRestored = restored.maxOfOrNull { it.generation } ?: 0L
        generationCounter.updateAndGet { maxOf(it, maxRestored) }
    }

    private fun isFresh(
        boundary: TurnBoundary,
        now: Long,
    ): Boolean = now - boundary.armedAt <= MAX_AGE_MS

    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(lock) {
            cache = emptyList()
            store = null
            generationCounter.set(0L)
        }
    }
}

/** How long the pre-submit boundary read may take before the turn goes uncorrelatable. */
private const val TURN_BOUNDARY_TIMEOUT_MS = 1_500L

/**
 * Correlation scope for the active connection profile.
 *
 * `AuthManager.activeProfileId` stays null until a server profile is explicitly
 * selected, and the rest of AuthManager treats that as
 * [AuthManager.DEFAULT_PROFILE_ID] (see `currentDataScope()`). Collapsing
 * null/blank here gives this feature ONE scope identity — a raw `.orEmpty()`
 * would reject the default profile, so a normal install would arm no boundary,
 * resolve no row, and silently never auto-dismiss a reply notification.
 *
 * The arming side (prompt submit), the resolution side (completion), the
 * notification extras, and the chat viewport observer must ALL use this, or the
 * two halves disagree on the scope and dismissal stops working.
 */
internal fun correlationScopeId(): String =
    AuthManager.activeProfileId.value?.takeIf { it.isNotBlank() }
        ?: AuthManager.DEFAULT_PROFILE_ID

/** Rows scanned when correlating a completed turn (newest-first page). */
private const val TURN_ROW_SCAN_LIMIT = 20

/**
 * Reads the durable REST transcript high-watermark for [sessionId] and arms a
 * boundary for it. Call immediately BEFORE submitting the prompt.
 *
 * Deliberately does not send a `profile` query param: the boundary ids are later
 * compared against rows hydrated by the chat screen, which fetches this
 * endpoint without a profile. Keeping both reads in the same namespace is what
 * makes the id comparison meaningful.
 *
 * Returns false on any failure (including a gateway that does not confirm
 * `order=latest`) — the caller sends its prompt anyway and the turn simply
 * becomes uncorrelatable.
 */
internal suspend fun captureTurnBoundary(
    scopeId: String,
    sessionId: String,
    timeoutMs: Long = TURN_BOUNDARY_TIMEOUT_MS,
): Boolean {
    if (scopeId.isBlank() || sessionId.isBlank()) return false
    val maxMessageId =
        try {
            withTimeoutOrNull(timeoutMs) { fetchBoundaryTailId(sessionId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false
    return TurnCorrelationTracker.armBoundary(scopeId, sessionId, maxMessageId) != null
}

/**
 * Newest row id of the transcript, or null when the read cannot be trusted.
 *
 * `pagination` is only returned by gateways that honoured `order=latest`; a
 * backend that ignores the param would answer a `limit=1` probe with the OLDEST
 * row, producing a boundary that is too low — a lower bound that is too low is
 * exactly what lets a historical duplicate win. Absent proof, fail closed.
 */
private suspend fun fetchBoundaryTailId(sessionId: String): Int? {
    val result =
        safeApiCall {
            ApiClient.hermesApi.getSessionMessages(
                sessionId = sessionId,
                limit = 1,
                offset = 0,
                includeCompacted = true,
                order = "latest",
            )
        }
    val body = (result as? NetworkResult.Success)?.data ?: return null
    // The whole safety property rests on this single row being the HIGHEST id in
    // the transcript. Require the gateway to confirm it honoured order=latest
    // (same proof the chat hydration uses) — a backend that ignores the param
    // answers with the OLDEST row, and a lower bound that is too low is exactly
    // what lets a historical duplicate win.
    if (body.pagination?.order != "latest") return null
    return body.messages.mapNotNull { it.id }.maxOrNull() ?: 0
}

/** Newest-first REST page used to correlate a completed turn. */
internal suspend fun fetchTurnRows(sessionId: String): List<SessionMessage>? {
    val result =
        safeApiCall {
            ApiClient.hermesApi.getSessionMessages(
                sessionId = sessionId,
                limit = TURN_ROW_SCAN_LIMIT,
                offset = 0,
                includeCompacted = true,
                order = "latest",
            )
        }
    return (result as? NetworkResult.Success)?.data?.messages
}

/**
 * Resolves the exact REST row persisted by the turn that just completed, given
 * the boundary armed before that turn was submitted.
 *
 * The boundary is consumed either way in `finally`: the turn it describes is
 * over, and leaving it armed would let the next completion bind to a row above
 * a stale lower bound.
 */
internal suspend fun correlateCompletedTurnRow(
    scopeId: String,
    sessionId: String,
    completionText: String,
    resolver: TurnRowResolver = defaultTurnRowResolver,
): Int? {
    val boundary = TurnCorrelationTracker.boundaryFor(scopeId, sessionId) ?: return null
    return try {
        resolver.resolve(sessionId, boundary, completionText)
    } finally {
        TurnCorrelationTracker.clearBoundary(scopeId, sessionId, boundary.generation)
    }
}

/** Production resolver — reads the newest REST page for the session. */
internal val defaultTurnRowResolver =
    TurnRowResolver(fetchLatestRows = { sessionId -> fetchTurnRows(sessionId) })
