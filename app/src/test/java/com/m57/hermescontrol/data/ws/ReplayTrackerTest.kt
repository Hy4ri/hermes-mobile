package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceParams
import com.m57.hermescontrol.data.ws.contract.SessionEventsSinceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayTrackerTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun jsonElement(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `initial state has empty watermarks and no replay in flight`() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val tracker =
            ReplayTracker(
                scope = testScope,
                fetchEventsSince = { SessionEventsSinceResult() },
                emitEvent = {},
            )

        assertTrue(tracker.getSeqWatermarks().isEmpty())
        assertFalse(tracker.isReplayInFlight())
    }

    @Test
    fun `watermarks can be set read and cleared without cancelling jobs`() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val tracker =
            ReplayTracker(
                scope = testScope,
                fetchEventsSince = { SessionEventsSinceResult() },
                emitEvent = {},
            )

        tracker.setSeqWatermark("s1", 10)
        tracker.setSeqWatermark("s2", 25)

        assertEquals(mapOf("s1" to 10, "s2" to 25), tracker.getSeqWatermarks())

        tracker.clearSeqWatermarks()

        assertTrue(tracker.getSeqWatermarks().isEmpty())
        assertFalse(tracker.isReplayInFlight())
    }

    @Test
    fun `triggerReplay is no-op when watermarks are empty`() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        var fetchCalled = false
        val tracker =
            ReplayTracker(
                scope = testScope,
                fetchEventsSince = {
                    fetchCalled = true
                    SessionEventsSinceResult()
                },
                emitEvent = {},
            )

        tracker.triggerReplay()

        assertFalse(tracker.isReplayInFlight())
        testScope.advanceUntilIdle()
        assertFalse(fetchCalled)
    }

    @Test
    fun `triggerReplay arms hold synchronously and sets replay in flight before coroutine runs`() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val deferred = CompletableDeferred<SessionEventsSinceResult>()
        val emitted = mutableListOf<WsEvent>()

        val tracker =
            ReplayTracker(
                scope = testScope,
                fetchEventsSince = { deferred.await() },
                emitEvent = { emitted.add(it) },
            )

        tracker.setSeqWatermark("s1", 5)

        // Arms hold synchronously
        tracker.triggerReplay()

        assertTrue(tracker.isReplayInFlight())

        // Calling triggerReplay again while in flight is ignored
        tracker.triggerReplay()
        assertTrue(tracker.isReplayInFlight())

        // A live event arriving now for s1 must be parked into hold without advancing watermark
        val liveMsg = WsEvent.MessageToken(token = "live", sessionId = "s1")
        tracker.acceptLiveEvent("s1", 8, liveMsg)

        // Watermark has NOT advanced to 8 because replay is in flight and event is held
        assertEquals(5, tracker.getSeqWatermarks()["s1"])
        assertTrue(emitted.isEmpty())

        // Complete replay
        deferred.complete(SessionEventsSinceResult())
        testScope.advanceUntilIdle()

        // Replay completed, held event released and emitted, watermark advanced
        assertFalse(tracker.isReplayInFlight())
        assertEquals(listOf(liveMsg), emitted)
        assertEquals(8, tracker.getSeqWatermarks()["s1"])
    }

    @Test
    fun `isDuplicate drops equal or older seq and accepts newer seq`() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val tracker =
            ReplayTracker(
                scope = testScope,
                fetchEventsSince = { SessionEventsSinceResult() },
                emitEvent = {},
            )

        tracker.setSeqWatermark("s1", 10)

        // Same seq: duplicate
        assertTrue(tracker.isDuplicate("s1", 10))
        // Older seq: duplicate
        assertTrue(tracker.isDuplicate("s1", 5))
        // Newer seq: not duplicate
        assertFalse(tracker.isDuplicate("s1", 11))
        // Unknown session defaults to seq 0
        assertFalse(tracker.isDuplicate("s2", 1))
        assertTrue(tracker.isDuplicate("s2", 0))
    }

    @Test
    fun `acceptLiveEvent without in-flight replay emits event and advances watermark`() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val emitted = mutableListOf<WsEvent>()
        val tracker =
            ReplayTracker(
                scope = testScope,
                fetchEventsSince = { SessionEventsSinceResult() },
                emitEvent = { emitted.add(it) },
            )

        tracker.setSeqWatermark("s1", 10)
        val event = WsEvent.MessageToken(token = "tok", sessionId = "s1")

        // Duplicate seq: dropped
        tracker.acceptLiveEvent("s1", 10, event)
        assertTrue(emitted.isEmpty())
        assertEquals(10, tracker.getSeqWatermarks()["s1"])

        // Newer seq: emitted and updates watermark
        tracker.acceptLiveEvent("s1", 12, event)
        assertEquals(listOf(event), emitted)
        assertEquals(12, tracker.getSeqWatermarks()["s1"])
    }

    @Test
    fun `replay events are emitted in provided order and advance watermarks`() =
        runTest {
            val emitted = mutableListOf<WsEvent>()
            val event1 =
                jsonElement(
                    """{"type":"message.token","session_id":"s1","seq":6,"payload":{"text":"a"}}""",
                )
            val event2 =
                jsonElement(
                    """{"type":"message.token","session_id":"s1","seq":7,"payload":{"text":"b"}}""",
                )

            val tracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = {
                        SessionEventsSinceResult(
                            events = listOf(event1, event2),
                        )
                    },
                    emitEvent = { emitted.add(it) },
                )

            tracker.setSeqWatermark("s1", 5)
            tracker.fetchReplayForTest()

            assertEquals(2, emitted.size)
            assertEquals("a", (emitted[0] as WsEvent.MessageToken).token)
            assertEquals("b", (emitted[1] as WsEvent.MessageToken).token)
            assertEquals(7, tracker.getSeqWatermarks()["s1"])
            assertFalse(tracker.isReplayInFlight())
        }

    @Test
    fun `replay events with seq at or below watermark are dropped`() =
        runTest {
            val emitted = mutableListOf<WsEvent>()
            val oldEvent =
                jsonElement(
                    """{"type":"message.token","session_id":"s1","seq":5,"payload":{"text":"old"}}""",
                )
            val newEvent =
                jsonElement(
                    """{"type":"message.token","session_id":"s1","seq":6,"payload":{"text":"new"}}""",
                )

            val tracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = {
                        SessionEventsSinceResult(
                            events = listOf(oldEvent, newEvent),
                        )
                    },
                    emitEvent = { emitted.add(it) },
                )

            tracker.setSeqWatermark("s1", 5)
            tracker.fetchReplayForTest()

            assertEquals(1, emitted.size)
            assertEquals("new", (emitted[0] as WsEvent.MessageToken).token)
            assertEquals(6, tracker.getSeqWatermarks()["s1"])
        }

    @Test
    fun `held live duplicates covered by replay are dropped and fresh held follow`() =
        runTest {
            val deferred = CompletableDeferred<SessionEventsSinceResult>()
            val emitted = mutableListOf<WsEvent>()

            val tracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = { deferred.await() },
                    emitEvent = { emitted.add(it) },
                )

            tracker.setSeqWatermark("s1", 5)
            tracker.triggerReplay()

            // Live event 6 arrives while replay in flight
            val live6 = WsEvent.MessageToken(token = "live6", sessionId = "s1")
            val live7 = WsEvent.MessageToken(token = "live7", sessionId = "s1")
            tracker.acceptLiveEvent("s1", 6, live6)
            tracker.acceptLiveEvent("s1", 7, live7)

            // Replay also returns event 6
            val replay6 =
                jsonElement(
                    """{"type":"message.token","session_id":"s1","seq":6,"payload":{"text":"replay6"}}""",
                )
            deferred.complete(
                SessionEventsSinceResult(
                    events = listOf(replay6),
                ),
            )

            testScheduler.advanceUntilIdle()

            // Replay 6 is emitted first, advancing watermark to 6.
            // Held live 6 (seq 6 <= 6) is dropped as duplicate.
            // Held live 7 (seq 7 > 6) is emitted and advances watermark to 7.
            assertEquals(2, emitted.size)
            assertEquals("replay6", (emitted[0] as WsEvent.MessageToken).token)
            assertEquals("live7", (emitted[1] as WsEvent.MessageToken).token)
            assertEquals(7, tracker.getSeqWatermarks()["s1"])
            assertFalse(tracker.isReplayInFlight())
        }

    @Test
    fun `epoch change clears all watermarks updates epoch and requests transcript resync`() =
        runTest {
            val emitted = mutableListOf<WsEvent>()

            val tracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = {
                        SessionEventsSinceResult(
                            epoch = "epoch-2",
                            latestSeq = 50,
                        )
                    },
                    emitEvent = { emitted.add(it) },
                )

            tracker.setReplayEpochForTest("epoch-1")
            tracker.setSeqWatermark("s1", 10)
            tracker.setSeqWatermark("s2", 20)

            tracker.fetchReplayForTest()

            // Watermarks wiped
            assertTrue(tracker.getSeqWatermarks().isEmpty())
            assertEquals(1, emitted.size)
            assertEquals(WsEvent.TranscriptResyncRequired("s1"), emitted[0])
        }

    @Test
    fun `empty or null epoch in result does not trigger epoch change`() =
        runTest {
            val emitted = mutableListOf<WsEvent>()

            val tracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = {
                        SessionEventsSinceResult(
                            epoch = "",
                            events = emptyList(),
                        )
                    },
                    emitEvent = { emitted.add(it) },
                )

            tracker.setReplayEpochForTest("epoch-1")
            tracker.setSeqWatermark("s1", 10)

            tracker.fetchReplayForTest()

            assertEquals(10, tracker.getSeqWatermarks()["s1"])
            assertTrue(emitted.isEmpty())
        }

    @Test
    fun `truncated resync fast-forwards watermark to latestSeq when epoch is same`() =
        runTest {
            val emitted = mutableListOf<WsEvent>()

            val tracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = {
                        SessionEventsSinceResult(
                            truncated = true,
                            latestSeq = 100,
                            epoch = "epoch-1",
                        )
                    },
                    emitEvent = { emitted.add(it) },
                )

            tracker.setReplayEpochForTest("epoch-1")
            tracker.setSeqWatermark("s1", 10)

            tracker.fetchReplayForTest()

            // Fast-forwards to latestSeq and emits TranscriptResyncRequired
            assertEquals(100, tracker.getSeqWatermarks()["s1"])
            assertEquals(listOf(WsEvent.TranscriptResyncRequired("s1")), emitted)
        }

    @Test
    fun `replay failure triggers resync invokes onReplayFailure and releases held events`() =
        runTest {
            val deferred = CompletableDeferred<SessionEventsSinceResult>()
            val emitted = mutableListOf<WsEvent>()
            val failedSessions = mutableListOf<Pair<String, Exception>>()

            val tracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = {
                        deferred.await()
                    },
                    emitEvent = { emitted.add(it) },
                    onReplayFailure = { sid, ex -> failedSessions.add(sid to ex) },
                )

            tracker.setSeqWatermark("s1", 10)
            tracker.triggerReplay()

            // Park a live event while waiting
            val liveEvent = WsEvent.MessageToken(token = "held", sessionId = "s1")
            tracker.acceptLiveEvent("s1", 15, liveEvent)

            // Fail replay
            val testException = IllegalStateException("network error")
            deferred.completeExceptionally(testException)

            testScheduler.advanceUntilIdle()

            // Failure emitted TranscriptResyncRequired, reported to callback, and released held events
            assertEquals(1, failedSessions.size)
            assertEquals("s1", failedSessions[0].first)
            assertTrue(failedSessions[0].second is IllegalStateException)
            assertEquals("network error", failedSessions[0].second.message)

            assertEquals(2, emitted.size)
            assertEquals(WsEvent.TranscriptResyncRequired("s1"), emitted[0])
            assertEquals(liveEvent, emitted[1])
            assertEquals(15, tracker.getSeqWatermarks()["s1"])
            assertFalse(tracker.isReplayInFlight())
        }

    @Test
    fun `MessageComplete event uses resolveStoredSessionId injection`() =
        runTest {
            val emitted = mutableListOf<WsEvent>()
            val msgCompleteJson =
                jsonElement(
                    """{"type":"message.complete","session_id":"wire-sid","payload":{"text":"done"}}""",
                )

            val tracker =
                ReplayTracker(
                    scope = this,
                    fetchEventsSince = {
                        SessionEventsSinceResult(
                            events = listOf(msgCompleteJson),
                        )
                    },
                    emitEvent = { emitted.add(it) },
                    resolveStoredSessionId = { wireSid -> "resolved-$wireSid" },
                )

            tracker.setSeqWatermark("wire-sid", 5)
            tracker.fetchReplayForTest()

            assertEquals(1, emitted.size)
            val complete = emitted[0] as WsEvent.MessageComplete
            assertEquals("done", complete.text)
            assertEquals("wire-sid", complete.sessionId)
            assertEquals("resolved-wire-sid", complete.storedSessionId)
        }

    @Test
    fun `updateEpoch clears watermarks when epoch changes`() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val tracker =
            ReplayTracker(
                scope = testScope,
                fetchEventsSince = { SessionEventsSinceResult() },
                emitEvent = {},
            )

        tracker.setReplayEpochForTest("epoch-1")
        tracker.setSeqWatermark("s1", 10)

        // Same epoch does not clear
        tracker.updateEpoch("epoch-1")
        assertEquals(10, tracker.getSeqWatermarks()["s1"])

        // Empty / null does not clear
        tracker.updateEpoch(null)
        tracker.updateEpoch("")
        assertEquals(10, tracker.getSeqWatermarks()["s1"])

        // Different epoch clears watermarks
        tracker.updateEpoch("epoch-2")
        assertTrue(tracker.getSeqWatermarks().isEmpty())
    }

    @Test
    fun `clear cancels in-flight job and resets all state`() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val deferred = CompletableDeferred<SessionEventsSinceResult>()
        val tracker =
            ReplayTracker(
                scope = testScope,
                fetchEventsSince = { deferred.await() },
                emitEvent = {},
            )

        tracker.setSeqWatermark("s1", 10)
        tracker.triggerReplay()
        assertTrue(tracker.isReplayInFlight())

        tracker.clear()

        assertFalse(tracker.isReplayInFlight())
        assertTrue(tracker.getSeqWatermarks().isEmpty())
    }

    @Test
    fun `acceptLiveEvent with eventFactory drops duplicate seq without invoking factory or emitting`() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val emitted = mutableListOf<WsEvent>()
        var factoryInvoked = false
        val tracker =
            ReplayTracker(
                scope = testScope,
                fetchEventsSince = { SessionEventsSinceResult() },
                emitEvent = { emitted.add(it) },
            )

        tracker.setSeqWatermark("s1", 10)

        // Duplicate seq <= watermark must not invoke factory nor emit
        tracker.acceptLiveEvent(sessionId = "s1", seq = 10) {
            factoryInvoked = true
            WsEvent.MessageToken(token = "duplicate", sessionId = "s1")
        }

        assertFalse(factoryInvoked)
        assertTrue(emitted.isEmpty())
        assertEquals(10, tracker.getSeqWatermarks()["s1"])
    }

    @Test
    fun `acceptLiveEvent with eventFactory invokes factory once and preserves single-check ordering`() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        val emitted = mutableListOf<WsEvent>()
        val invocationCount = AtomicInteger(0)
        val tracker =
            ReplayTracker(
                scope = testScope,
                fetchEventsSince = { SessionEventsSinceResult() },
                emitEvent = { emitted.add(it) },
            )

        tracker.setSeqWatermark("s1", 10)
        val expectedEvent = WsEvent.MessageToken(token = "fresh", sessionId = "s1")

        // Factory intentionally injects an interleaved watermark advance (e.g. simulating race or side effect).
        // Under single-check ordering, the initial pre-parse check (seq 11 > 10) succeeds, calls factory exactly once,
        // and proceeds to emit and advance watermark without silently dropping due to an unexpected second check.
        tracker.acceptLiveEvent(sessionId = "s1", seq = 11) {
            invocationCount.incrementAndGet()
            tracker.setSeqWatermark("s1", 99)
            expectedEvent
        }

        assertEquals(1, invocationCount.get())
        assertEquals(listOf(expectedEvent), emitted)
        assertEquals(11, tracker.getSeqWatermarks()["s1"])
    }
}
