package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.session.ActiveSessionHolder
import com.m57.hermescontrol.data.session.SessionProfileTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Tests that a proactive [WsEvent.MessageComplete] from Karellen's session
 * resolves correctly (with profile name) even while another profile is active.
 *
 * This validates the notification routing path: when Karellen completes a
 * message while the user is on a different profile (e.g. "default"), the
 * MessageComplete event must carry profileName so the notification tap can
 * switch profiles before navigating to the session.
 */
class KarellenProactiveNotificationRoutingTest {
    @Before
    fun setUp() {
        SessionProfileTracker.clear()
    }

    @After
    fun tearDown() {
        SessionProfileTracker.clear()
    }

    @Test
    fun `MessageComplete event carries profileName when SessionProfileTracker has mapping`() {
        // Given: Karellen's canonical session is tracked
        SessionProfileTracker.trackCanonical("resolved-canon-karellen", "karellen")

        // When: A MessageComplete event is created for Karellen's session
        val event =
            WsEvent.MessageComplete(
                text = "Task completed successfully. All reports ready.",
                sessionId = "resolved-canon-karellen",
                completionId = "completion-001",
            )

        // Then: SessionProfileTracker should resolve the profile
        val resolvedProfile = SessionProfileTracker.resolveProfile("resolved-canon-karellen")
        assertEquals("karellen", resolvedProfile)

        // The event itself doesn't yet have profileName - HermesWsClient adds it via copy()
        val enrichedEvent =
            event.copy(
                profileName = resolvedProfile,
                storedSessionId = ActiveSessionHolder.resolveStoredSessionId(event.sessionId),
            )

        assertNotNull(enrichedEvent.profileName)
        assertEquals("karellen", enrichedEvent.profileName)
        assertEquals("resolved-canon-karellen", enrichedEvent.sessionId)
        assertEquals("Task completed successfully. All reports ready.", enrichedEvent.text)
    }

    @Test
    fun `session from another profile is not the active session`() {
        // Given: The default profile's session is active, Karellen's session is tracked
        SessionProfileTracker.track("sess-default-active", "default")
        SessionProfileTracker.trackCanonical("sess-karellen-completed", "karellen")

        // When: ActiveSessionHolder shows the default profile's session
        ActiveSessionHolder.set("sess-default-active", "stored-default", profileName = "default")

        // Then: Karellen's session resolves to a different profile than the active one
        val karellenProfile = SessionProfileTracker.resolveProfile("sess-karellen-completed")
        assertEquals("karellen", karellenProfile)

        val activeProfile = ActiveSessionHolder.activeProfile
        assertEquals("default", activeProfile)

        // The profiles differ — notification path must switch profiles
        assertNotNull(karellenProfile)
        assertNotNull(activeProfile)
        assertEquals("default", activeProfile)
        assertEquals("karellen", karellenProfile)
    }

    @Test
    fun `Karellen MessageComplete with storedSessionId and profileName resolves correctly`() {
        // Given: SessionProfileTracker knows Karellen's session
        SessionProfileTracker.track("sess-karellen-001", "karellen")

        // When: A MessageComplete arrives (simulating HermesWsClient enrichment)
        val rawEvent =
            WsEvent.MessageComplete(
                text = "Here are the findings from the latest scan",
                sessionId = "sess-karellen-001",
                completionId = "completion-002",
            )

        // Simulate the enrichment that HermesWsClient does at all 3 copy() sites
        val enrichedEvent =
            rawEvent.copy(
                storedSessionId = ActiveSessionHolder.resolveStoredSessionId(rawEvent.sessionId),
                profileName = SessionProfileTracker.resolveProfile(rawEvent.sessionId),
            )

        // Then: profileName is resolved
        assertEquals("karellen", enrichedEvent.profileName)
        assertEquals("sess-karellen-001", enrichedEvent.storedSessionId)
        assertEquals("Here are the findings from the latest scan", enrichedEvent.text)
    }

    @Test
    fun `unknown session returns null profileName`() {
        // Given: No session mapping registered
        // When: Enriching an event for an unknown session
        val event =
            WsEvent.MessageComplete(
                text = "Some completion text",
                sessionId = "unknown-session-id",
                completionId = "completion-003",
            )

        val profileName = SessionProfileTracker.resolveProfile("unknown-session-id")

        // Then: No profile is resolved
        assertNull(profileName)
    }

    @Test
    fun `multiple consecutive completions from Karellen all carry correct profile`() {
        // Given: Karellen session mapped
        SessionProfileTracker.track("sess-karellen-002", "karellen")
        SessionProfileTracker.track("sess-karellen-003", "karellen")

        // When: Multiple completions come in
        val completions =
            listOf(
                WsEvent.MessageComplete(text = "First completion", sessionId = "sess-karellen-002"),
                WsEvent.MessageComplete(text = "Second completion", sessionId = "sess-karellen-003"),
            )

        // Then: All resolve correctly
        for (event in completions) {
            val profile = SessionProfileTracker.resolveProfile(event.sessionId!!)
            assertEquals("karellen", profile)
        }
    }
}
