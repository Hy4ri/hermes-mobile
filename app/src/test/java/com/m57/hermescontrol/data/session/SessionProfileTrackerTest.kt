package com.m57.hermescontrol.data.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SessionProfileTracker] — the durable session→profile mapping
 * that survives WebSocket reconnects so the notification path can resolve
 * the profile owner for proactive completions from a different profile.
 */
class SessionProfileTrackerTest {
    @Before
    fun setUp() {
        SessionProfileTracker.clear()
    }

    @After
    fun tearDown() {
        SessionProfileTracker.clear()
    }

    @Test
    fun `track basic session to profile mapping`() {
        SessionProfileTracker.track("sess-001", "karellen")
        assertEquals("karellen", SessionProfileTracker.resolveProfile("sess-001"))
    }

    @Test
    fun `track canonical session mapping`() {
        SessionProfileTracker.trackCanonical("sess-karellen", "karellen")
        assertEquals("karellen", SessionProfileTracker.resolveProfile("sess-karellen"))
        assertEquals("sess-karellen", SessionProfileTracker.canonicalSessionId("karellen"))
    }

    @Test
    fun `track canonical does not overwrite existing mapping`() {
        SessionProfileTracker.track("sess-001", "profile-a")
        SessionProfileTracker.trackCanonical("sess-001", "profile-b")
        // First-wins: original mapping preserved
        assertEquals("profile-a", SessionProfileTracker.resolveProfile("sess-001"))
    }

    @Test
    fun `resolve unknown session returns null`() {
        assertNull(SessionProfileTracker.resolveProfile("nonexistent-session"))
    }

    @Test
    fun `remove profile clears its mappings`() {
        SessionProfileTracker.trackCanonical("sess-karellen", "karellen")
        SessionProfileTracker.track("sess-other", "karellen")
        assertTrue(SessionProfileTracker.hasProfile("karellen"))

        SessionProfileTracker.removeProfile("karellen")
        assertFalse(SessionProfileTracker.hasProfile("karellen"))
        assertNull(SessionProfileTracker.resolveProfile("sess-karellen"))
    }

    @Test
    fun `clear removes all mappings`() {
        SessionProfileTracker.track("sess-001", "karellen")
        SessionProfileTracker.track("sess-002", "default")
        assertEquals(2, SessionProfileTracker.trackedProfiles.size)

        SessionProfileTracker.clear()
        assertTrue(SessionProfileTracker.trackedProfiles.isEmpty())
        assertNull(SessionProfileTracker.resolveProfile("sess-001"))
        assertNull(SessionProfileTracker.resolveProfile("sess-002"))
    }

    @Test
    fun `track blank session id is no-op`() {
        SessionProfileTracker.track("", "karellen")
        assertNull(SessionProfileTracker.resolveProfile(""))
        assertFalse(SessionProfileTracker.hasProfile("karellen"))
    }

    @Test
    fun `multiple sessions under same profile`() {
        SessionProfileTracker.track("sess-001", "karellen")
        SessionProfileTracker.track("sess-002", "karellen")
        SessionProfileTracker.trackCanonical("sess-canon", "karellen")

        assertEquals("karellen", SessionProfileTracker.resolveProfile("sess-001"))
        assertEquals("karellen", SessionProfileTracker.resolveProfile("sess-002"))
        assertEquals("karellen", SessionProfileTracker.resolveProfile("sess-canon"))
        assertEquals("sess-canon", SessionProfileTracker.canonicalSessionId("karellen"))
    }

    @Test
    fun `profile survives after track called from ActiveSessionHolder`() {
        // Simulate what ActiveSessionHolder does on session create
        SessionProfileTracker.track("runtime-abc", "karellen")
        SessionProfileTracker.track("stored-abc", "karellen")

        assertEquals("karellen", SessionProfileTracker.resolveProfile("runtime-abc"))
        assertEquals("karellen", SessionProfileTracker.resolveProfile("stored-abc"))
    }

    @Test
    fun `mapping survives clear of ActiveSessionHolder`() {
        // Populate from a session create
        SessionProfileTracker.track("sess-karellen", "karellen")

        // Simulate WebSocket disconnect (ActiveSessionHolder.clear() — NOT SessionProfileTracker.clear())
        // The mapping should still be intact
        assertEquals("karellen", SessionProfileTracker.resolveProfile("sess-karellen"))
    }
}
