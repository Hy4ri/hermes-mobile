package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRpcRequestTrackerTest {
    private var currentSessionId: String? = "session-a"
    private val tracker = ChatRpcRequestTracker { currentSessionId }

    @Test
    fun trackRequest_andRemoveMethod() {
        tracker.trackRequest("req-1", "session.create")
        assertEquals("session.create", tracker.removeMethod("req-1"))
        assertNull(tracker.removeMethod("req-1"))
    }

    @Test
    fun generationIncrement_detectsStaleGenerations() {
        val gen1 = tracker.nextSessionGeneration()
        assertEquals(1L, gen1)

        tracker.trackSessionRequest(
            id = "req-resume-1",
            method = "session.resume",
            generation = gen1,
            sessionId = "session-a",
        )
        assertFalse(tracker.isStaleSessionRequest("req-resume-1"))

        val gen2 = tracker.nextSessionGeneration()
        assertEquals(2L, gen2)
        assertTrue(tracker.isStaleSessionRequest("req-resume-1"))
    }

    @Test
    fun sessionMismatch_marksRequestStale() {
        val gen = tracker.nextSessionGeneration()
        tracker.trackSessionRequest(
            id = "req-2",
            method = "session.resume",
            generation = gen,
            sessionId = "session-a",
        )
        assertFalse(tracker.isStaleSessionRequest("req-2"))

        currentSessionId = "session-b"
        assertTrue(tracker.isStaleSessionRequest("req-2"))
    }

    @Test
    fun resumeSequence_marksSupersededResumeStale() {
        val gen = tracker.nextSessionGeneration()
        val seq1 = tracker.nextResumeSequence()
        tracker.trackSessionRequest(
            id = "req-resume-seq1",
            method = "session.resume",
            generation = gen,
            resumeSequence = seq1,
            sessionId = "session-a",
        )
        assertFalse(tracker.isStaleSessionRequest("req-resume-seq1"))

        val seq2 = tracker.nextResumeSequence()
        assertEquals(2L, seq2)
        assertTrue(tracker.isStaleSessionRequest("req-resume-seq1"))
    }

    @Test
    fun hydrationSequence_guardsActiveHydration() {
        val gen = tracker.nextSessionGeneration()
        val hyd1 = tracker.nextHydrationSequence()
        assertTrue(tracker.isCurrentHydration("session-a", gen, hyd1))

        val hyd2 = tracker.nextHydrationSequence()
        assertFalse(tracker.isCurrentHydration("session-a", gen, hyd1))
        assertTrue(tracker.isCurrentHydration("session-a", gen, hyd2))
    }

    @Test
    fun forgetRequest_removesBothTrackingEntries() {
        tracker.trackSessionRequest(
            id = "req-forget",
            method = "session.resume",
            generation = tracker.sessionGeneration,
            sessionId = "session-a",
        )
        tracker.forgetRequest("req-forget")
        assertNull(tracker.removeMethod("req-forget"))
        assertNull(tracker.removeSessionRequest("req-forget"))
    }
}
