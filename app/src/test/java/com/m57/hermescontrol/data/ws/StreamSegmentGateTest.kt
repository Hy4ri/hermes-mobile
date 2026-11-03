package com.m57.hermescontrol.data.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSegmentGateTest {
    private val gate = StreamSegmentGate()

    private fun delta(
        text: String,
        segment: Int,
        offset: Int?,
        turn: String = "t1",
        sid: String = "s1",
    ) = WsEvent.MessageToken(text, sid, SegmentStamp(turn, segment, offset))

    private fun seal(
        segment: Int,
        turn: String = "t1",
        sid: String = "s1",
    ) = WsEvent.MessageInterim(sid, SegmentStamp(turn, segment))

    /** Feeds [events] and returns the text the consumers would append. */
    private fun applied(
        vararg events: WsEvent,
        customGate: StreamSegmentGate = gate,
    ): String =
        events
            .mapNotNull { customGate.admit(it) }
            .filterIsInstance<WsEvent.MessageToken>()
            .joinToString("") { it.token }

    @Test
    fun `fresh stamped deltas pass unchanged`() {
        val first = delta("Hel", 0, 0)
        assertSame(first, gate.admit(first))
        val second = delta("lo", 0, 3)
        assertSame(second, gate.admit(second))
    }

    @Test
    fun `full repeat of an applied chunk is dropped`() {
        assertEquals(
            "Hello",
            applied(
                WsEvent.MessageStart("s1", "t1"),
                delta("Hel", 0, 0),
                delta("lo", 0, 3),
                // Reconnect replay re-sends both chunks under new seqs.
                delta("Hel", 0, 0),
                delta("lo", 0, 3),
            ),
        )
    }

    @Test
    fun `overlapping chunk keeps only the unseen tail`() {
        gate.admit(delta("Hel", 0, 0))
        val trimmed = gate.admit(delta("Hello world", 0, 0)) as WsEvent.MessageToken
        assertEquals("lo world", trimmed.token)
        assertEquals("s1", trimmed.sessionId)
        assertNull(gate.admit(delta("world", 0, 6)))
    }

    @Test
    fun `offsets count UTF-16 code units`() {
        // The moon emoji is a surrogate pair: two UTF-16 code units, as on the backend.
        assertEquals(
            "🌙 hi!",
            applied(delta("🌙 ", 0, 0), delta("🌙 hi", 0, 0), delta("!", 0, 5)),
        )
    }

    @Test
    fun `gap detection returns GapDetected and does not advance cursor over missing text`() {
        val gate = StreamSegmentGate()
        gate.admit(delta("He", 0, 0))
        val gapAdmission = gate.evaluate(delta("world", 0, 6))
        assertTrue("Must detect gap when offset skips appliedEnd", gapAdmission is GateAdmission.GapDetected)
        val gap = gapAdmission as GateAdmission.GapDetected
        assertEquals(2, gap.expectedOffset)
        assertEquals(6, gap.incomingOffset)

        // Quarantined turn rejects further deltas until settled
        assertNull("Quarantined turn must reject further deltas", gate.admit(delta("llo ", 0, 2)))
    }

    @Test
    fun `completed turn rejects all subsequent starts, deltas, and seals`() {
        val gate = StreamSegmentGate()
        val start = WsEvent.MessageStart("s1", "turn1")
        val d1 = delta("hello", 0, 0, turn = "turn1")
        assertSame(start, gate.admit(start))
        assertSame(d1, gate.admit(d1))

        // Mark turn1 completed
        gate.markTurnCompleted("s1", "turn1")

        // Replay of start, deltas, or seals for turn1 must all be dropped
        assertNull("Completed turn must reject repeated start", gate.admit(start))
        assertNull("Completed turn must reject replayed delta", gate.admit(d1))
        assertNull("Completed turn must reject new delta", gate.admit(delta(" world", 0, 5, turn = "turn1")))
        assertNull("Completed turn must reject seal", gate.admit(seal(0, turn = "turn1")))

        // Brand new turn2 passes normally
        val start2 = WsEvent.MessageStart("s1", "turn2")
        val d2 = delta("turn2 text", 0, 0, turn = "turn2")
        assertSame(start2, gate.admit(start2))
        assertSame(d2, gate.admit(d2))
    }

    @Test
    fun `partial stamp with null offset passes through unchanged`() {
        val partial1 = delta("Hello ", 0, null)
        val partial2 = delta("world", 0, null)
        assertSame(partial1, gate.admit(partial1))
        assertSame(partial2, gate.admit(partial2))
        // Successive chunks are not discarded as offset=0 duplicates
        assertEquals("Hello world", applied(partial1, partial2))
    }

    @Test
    fun `older segment re-sent after a seal is dropped`() {
        assertEquals(
            "Checking.Done",
            applied(
                delta("Checking.", 0, 0),
                seal(0),
                delta("Done", 1, 0),
                // A superseded attempt re-streams segment 0.
                delta("Checking.", 0, 0),
            ),
        )
    }

    @Test
    fun `repeated seal is a no-op and a fresh seal passes`() {
        val first = seal(0)
        assertSame(first, gate.admit(first))
        assertNull(gate.admit(seal(0)))
        val next = seal(1)
        assertSame(next, gate.admit(next))
    }

    @Test
    fun `same text in a later segment still passes`() {
        assertEquals("ok ok", applied(delta("ok ", 0, 0), seal(0), delta("ok", 1, 0)))
    }

    @Test
    fun `same-turn message start is dropped and a new turn passes`() {
        val start = WsEvent.MessageStart("s1", "t1")
        assertSame(start, gate.admit(start))
        assertNull(gate.admit(WsEvent.MessageStart("s1", "t1")))
        val next = WsEvent.MessageStart("s1", "t2")
        assertSame(next, gate.admit(next))
        // The new turn starts its own cursor at segment 0.
        assertEquals("Hi", applied(delta("Hi", 0, 0, turn = "t2")))
    }

    @Test
    fun `stale start of an earlier observed turn is dropped`() {
        // Delta A before start A, then start B + delta B, then late delayed start A
        val deltaA = delta("msgA", 0, 0, turn = "turnA")
        val startB = WsEvent.MessageStart("s1", "turnB")
        val deltaB = delta("msgB", 0, 0, turn = "turnB")
        val lateStartA = WsEvent.MessageStart("s1", "turnA")

        assertSame(deltaA, gate.admit(deltaA))
        assertSame(startB, gate.admit(startB))
        assertSame(deltaB, gate.admit(deltaB))

        // Late start A must be rejected as stale so it cannot rewind or reset the active turn
        assertNull("Late start of earlier turn must be dropped", gate.admit(lateStartA))
    }

    @Test
    fun `duplicate delta of older turn does not rewind highest observed turn pointer`() {
        // Exact repro: delta A -> start B -> duplicate delta A -> delayed first start A
        val deltaA = delta("msgA", 0, 0, turn = "turnA")
        val startB = WsEvent.MessageStart("s1", "turnB")
        val lateStartA = WsEvent.MessageStart("s1", "turnA")

        assertSame(deltaA, gate.admit(deltaA))
        assertSame(startB, gate.admit(startB))

        // Duplicate delta A arrives (e.g. from late socket/replay): must be dropped
        assertNull("Duplicate delta A must be dropped", gate.admit(deltaA))

        // Now delayed first start A arrives: must STILL be dropped because turnA is older than turnB
        assertNull("Delayed start A after duplicate delta A must still be dropped", gate.admit(lateStartA))
    }

    @Test
    fun `cursors are kept per session`() {
        assertEquals("AB", applied(delta("A", 0, 0, sid = "s1"), delta("B", 0, 0, sid = "s2")))
        assertNull(gate.admit(delta("A", 0, 0, sid = "s1")))
    }

    @Test
    fun `interleaved turns do not rewind or re-emit duplicate chunks`() {
        // Review point 1 repro: start A; delta A(0, 'abc'); start B; delta B(0, 'xyz');
        // replay delta A(0, 'abc'); replay delta B(0, 'xyz'); late start A.
        val startA = WsEvent.MessageStart("s1", "turnA")
        val deltaA = delta("abc", 0, 0, turn = "turnA")
        val startB = WsEvent.MessageStart("s1", "turnB")
        val deltaB = delta("xyz", 0, 0, turn = "turnB")

        // First pass
        assertSame(startA, gate.admit(startA))
        assertSame(deltaA, gate.admit(deltaA))
        assertSame(startB, gate.admit(startB))
        assertSame(deltaB, gate.admit(deltaB))

        // Interleaved replays of both turns
        assertNull("Replayed delta A must be dropped", gate.admit(deltaA))
        assertNull("Replayed delta B must be dropped", gate.admit(deltaB))
        assertNull("Late repeated start A must be dropped", gate.admit(startA))
        assertNull("Late repeated start B must be dropped", gate.admit(startB))
    }

    @Test
    fun `seals are tracked per turn during interleaving`() {
        val sealA0 = seal(0, turn = "turnA")
        val sealB0 = seal(0, turn = "turnB")

        assertSame(sealA0, gate.admit(sealA0))
        assertSame(sealB0, gate.admit(sealB0))

        // Repeated seals across both turns
        assertNull("Repeated seal A0 must be dropped", gate.admit(sealA0))
        assertNull("Repeated seal B0 must be dropped", gate.admit(sealB0))

        // Segment 0 delta in either turn after seal must be dropped
        assertNull(gate.admit(delta("A0", 0, 0, turn = "turnA")))
        assertNull(gate.admit(delta("B0", 0, 0, turn = "turnB")))

        // Fresh segment 1 in turnA passes
        val deltaA1 = delta("A1", 1, 0, turn = "turnA")
        assertSame(deltaA1, gate.admit(deltaA1))
    }

    @Test
    fun `scope change clears cache while same scope reconnect preserves dedupe`() {
        var currentScope = "scope1"
        val scopedGate = StreamSegmentGate(scopeKeyProvider = { currentScope })

        val delta1 = delta("hello", 0, 0, turn = "t1", sid = "s1")
        assertSame(delta1, scopedGate.admit(delta1))

        // Reconnect on same scope: duplicate is dropped
        assertNull("Replay on same scope must be deduped", scopedGate.admit(delta1))

        // Profile / server switch changes scope
        currentScope = "scope2"
        // On new scope, frames are not falsely dropped due to collisions
        val deltaNewScope = scopedGate.admit(delta1)
        assertNotNull("Frame on new scope must pass through", deltaNewScope)
        assertSame(delta1, deltaNewScope)
    }

    @Test
    fun `bounded LRU evicts eldest turns and sessions without memory leak`() {
        val tinyGate =
            StreamSegmentGate(
                maxSessions = 2,
                maxTurnsPerSession = 2,
            )

        // Session 1: Turn 1, Turn 2, Turn 3 (evicts Turn 1)
        tinyGate.admit(delta("t1", 0, 0, turn = "t1", sid = "s1"))
        tinyGate.admit(delta("t2", 0, 0, turn = "t2", sid = "s1"))
        tinyGate.admit(delta("t3", 0, 0, turn = "t3", sid = "s1"))

        // Turn 1 was evicted, so a new frame for turn 1 is admitted rather than dropped
        val reAdmittedT1 = tinyGate.admit(delta("t1", 0, 0, turn = "t1", sid = "s1"))
        assertNotNull("Evicted turn state should allow re-admission", reAdmittedT1)

        // Fill sessions: s1, s2, s3 (evicts s2 or eldest)
        tinyGate.admit(delta("s2", 0, 0, turn = "t1", sid = "s2"))
        tinyGate.admit(delta("s3", 0, 0, turn = "t1", sid = "s3"))
        // s1 was accessed before s2, so s1 was eldest when s3 arrived
        val reAdmittedS1 = tinyGate.admit(delta("s1-new", 0, 0, turn = "t9", sid = "s1"))
        assertNotNull(reAdmittedS1)
    }

    @Test
    fun `older backend without stamps keeps the old append path`() {
        val start = WsEvent.MessageStart("s1")
        assertSame(start, gate.admit(start))
        assertSame(start, gate.admit(start))
        val token = WsEvent.MessageToken("Hi", "s1")
        assertSame(token, gate.admit(token))
        assertSame(token, gate.admit(token))
        val interim = WsEvent.MessageInterim("s1", null)
        assertSame(interim, gate.admit(interim))
        assertSame(interim, gate.admit(interim))
    }

    @Test
    fun `empty segment boundary chunk passes through`() {
        gate.admit(delta("Hi", 0, 0))
        val boundary = delta("", 0, 2)
        assertSame(boundary, gate.admit(boundary))
    }

    @Test
    fun `non stream events pass unchanged`() {
        val done = WsEvent.MessageDone("s1")
        assertSame(done, gate.admit(done))
    }
}
