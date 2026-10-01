package com.m57.hermescontrol.data.ws

import androidx.annotation.VisibleForTesting
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceParams
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks session event sequence watermarks and orchestrates event replay on reconnect.
 *
 * Extracted from [HermesWsClient] (issue #1016, issue #1376).
 */
internal class ReplayTracker(
    private val scope: CoroutineScope,
    private val fetchEventsSince: suspend (SessionEventsSinceParams) -> SessionEventsSinceResult,
    private val emitEvent: (WsEvent) -> Unit,
    private val resolveStoredSessionId: (String?) -> String? = { it },
    private val onReplayFailure: (String, Exception) -> Unit = { _, _ -> },
) {
    private val lastSeenSeq = ConcurrentHashMap<String, Int>()

    @Volatile
    private var replayEpoch: String? = null

    @Volatile
    private var replayInFlight: Boolean = false

    private val replayHold = ConcurrentHashMap<String, MutableList<Pair<Int?, WsEvent>>>()

    @Volatile
    private var replayJob: Job? = null

    /**
     * Synchronously arms the hold buffers for all known sessions and launches replay in [scope].
     * Arming synchronously ensures racing live frames are held instead of advancing watermarks.
     */
    fun triggerReplay() {
        if (lastSeenSeq.isEmpty() || replayInFlight) return
        replayInFlight = true
        val sessionsToReplay = lastSeenSeq.keys().toList()
        for (sid in sessionsToReplay) {
            replayHold[sid] = Collections.synchronizedList(mutableListOf())
        }
        replayJob?.cancel()
        replayJob =
            scope.launch {
                fetchReplay(sessionsToReplay)
            }
    }

    /**
     * Updates replay epoch from gateway.ready.
     * When [epoch] is non-empty and changes from a previous non-null value, resets sequence watermarks.
     */
    fun updateEpoch(epoch: String?) {
        if (!epoch.isNullOrEmpty()) {
            if (replayEpoch != null && replayEpoch != epoch) {
                lastSeenSeq.clear()
            }
            replayEpoch = epoch
        }
    }

    /**
     * Checks if an incoming event is a duplicate against the current watermark for [sessionId].
     * Returns true if [seq] <= watermark.
     */
    fun isDuplicate(
        sessionId: String,
        seq: Int,
    ): Boolean {
        val prev = lastSeenSeq[sessionId] ?: 0
        return seq <= prev
    }

    /**
     * Processes an incoming live event with sequence tracking.
     * Evaluates [eventFactory] only after checking for duplicates.
     * If duplicate, drops it without calling [eventFactory].
     * If replay is in flight for [sessionId], buffers it in replayHold.
     * Otherwise advances watermark and emits the event.
     */
    fun acceptLiveEvent(
        sessionId: String,
        seq: Int,
        eventFactory: () -> WsEvent,
    ) {
        if (isDuplicate(sessionId, seq)) {
            return
        }
        val finalEvent = decorateEvent(eventFactory())
        if (replayInFlight && replayHold.containsKey(sessionId)) {
            replayHold[sessionId]?.add(seq to finalEvent)
            return
        }
        lastSeenSeq[sessionId] = seq
        emitEvent(finalEvent)
    }

    /**
     * Processes an incoming live event with sequence tracking.
     * If duplicate, drops it.
     * If replay is in flight for [sessionId], buffers it in replayHold.
     * Otherwise advances watermark and emits the event.
     */
    fun acceptLiveEvent(
        sessionId: String,
        seq: Int,
        event: WsEvent,
    ) {
        acceptLiveEvent(sessionId, seq) { event }
    }

    private fun decorateEvent(event: WsEvent): WsEvent =
        if (event is WsEvent.MessageComplete) {
            event.copy(
                storedSessionId = resolveStoredSessionId(event.sessionId),
            )
        } else {
            event
        }

    private suspend fun fetchReplay(sessionsToReplay: List<String> = lastSeenSeq.keys().toList()) {
        try {
            for (sid in sessionsToReplay) {
                val lastSeen = lastSeenSeq[sid] ?: continue
                try {
                    val res =
                        fetchEventsSince(
                            SessionEventsSinceParams(sessionId = sid, lastSeen = lastSeen),
                        )

                    val epoch = res.epoch
                    val epochChanged =
                        !epoch.isNullOrEmpty() && replayEpoch != null && replayEpoch != epoch
                    val truncated = res.truncated == true
                    val latestSeq = res.latestSeq

                    if (epochChanged) replayEpoch = epoch

                    if (epochChanged || truncated) {
                        // Ring buffer (512 events) could not cover the gap or epoch changed (gateway restarted).
                        // Partial replay would silently hole the transcript — fast-forward watermark
                        // and request full transcript resync.
                        if (epochChanged) lastSeenSeq.clear()
                        if (latestSeq != null && !epochChanged) lastSeenSeq[sid] = latestSeq
                        emitEvent(WsEvent.TranscriptResyncRequired(sid))
                        continue
                    }
                    val eventsList = res.events
                    if (eventsList != null) {
                        for (element in eventsList) {
                            @Suppress("UNCHECKED_CAST")
                            val eventMap = (element as? JsonObject)?.toAny() as? Map<String, Any?> ?: continue
                            val eventSeq = (eventMap["seq"] as? Number)?.toInt()
                            val eventSid = (eventMap["session_id"] as? String) ?: sid
                            if (eventSeq != null && eventSid.isNotEmpty()) {
                                val prev = lastSeenSeq[eventSid] ?: 0
                                if (eventSeq <= prev) continue
                                lastSeenSeq[eventSid] = eventSeq
                            }
                            val parsedEvent = EventParser.parseParams(eventMap)
                            val finalEvent = decorateEvent(parsedEvent)
                            emitEvent(finalEvent)
                        }
                    }
                } catch (e: Exception) {
                    onReplayFailure(sid, e)
                    emitEvent(WsEvent.TranscriptResyncRequired(sid))
                }
            }
        } finally {
            withContext(NonCancellable) {
                for (sid in sessionsToReplay) {
                    val held = replayHold.remove(sid)
                    if (held != null) {
                        synchronized(held) {
                            for ((heldSeq, heldEvent) in held) {
                                if (heldSeq != null) {
                                    val prev = lastSeenSeq[sid] ?: 0
                                    if (heldSeq <= prev) continue
                                    lastSeenSeq[sid] = heldSeq
                                }
                                emitEvent(heldEvent)
                            }
                        }
                    }
                }
                replayInFlight = false
            }
        }
    }

    /**
     * Complete teardown matching disconnect (issue #1016).
     * Cancels any active replay job and clears all holds, watermarks, and epoch.
     */
    fun clear() {
        lastSeenSeq.clear()
        replayHold.clear()
        replayEpoch = null
        replayJob?.cancel()
        replayJob = null
        replayInFlight = false
    }

    @VisibleForTesting
    internal fun getSeqWatermarks(): Map<String, Int> = HashMap(lastSeenSeq)

    @VisibleForTesting
    internal fun setSeqWatermark(
        sessionId: String,
        seq: Int,
    ) {
        lastSeenSeq[sessionId] = seq
    }

    @VisibleForTesting
    internal fun clearSeqWatermarks() {
        lastSeenSeq.clear()
        replayHold.clear()
        replayEpoch = null
        replayInFlight = false
    }

    @VisibleForTesting
    internal fun isReplayInFlight(): Boolean = replayInFlight

    @VisibleForTesting
    internal fun setReplayEpochForTest(epoch: String?) {
        replayEpoch = epoch
    }

    @VisibleForTesting
    internal suspend fun fetchReplayForTest() {
        fetchReplay()
    }
}
