package com.m57.hermescontrol.ui.bots.group

import com.m57.hermescontrol.data.ws.WsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostedRoomPollingTest {
    @Test
    fun pushingBackendPollsSlowlyUnlessTheLastReadFailed() {
        assertEquals(HOSTED_BACKSTOP_POLL_MS, hostedPollIntervalMs(backendPushes = true, lastReadFailed = false))
        assertEquals(HOSTED_FAST_POLL_MS, hostedPollIntervalMs(backendPushes = true, lastReadFailed = true))
        assertEquals(HOSTED_FAST_POLL_MS, hostedPollIntervalMs(backendPushes = false, lastReadFailed = false))
        assertEquals(15_000L, HOSTED_BACKSTOP_POLL_MS)
        assertEquals(2_000L, HOSTED_FAST_POLL_MS)
    }

    @Test
    fun readsOnlyForTheOpenRoomOrAProcessWidePush() {
        val gate = GroupsChangedGate()
        assertTrue(gate.shouldRead(WsEvent.GroupsChanged(listOf("a", "open"), "1"), "open"))
        assertFalse(gate.shouldRead(WsEvent.GroupsChanged(listOf("a"), "2"), "open"))
        assertFalse(gate.shouldRead(WsEvent.GroupsChanged(listOf("open"), "3"), null))
        assertTrue(gate.shouldRead(WsEvent.GroupsChanged(null, "4"), "open"))
    }

    @Test
    fun sameBroadcastIdIsReadOnce() {
        val gate = GroupsChangedGate()
        val event = WsEvent.GroupsChanged(null, "b")
        assertTrue(gate.shouldRead(event, "open"))
        assertFalse(gate.shouldRead(event, "open"))
        // No id: nothing to dedupe on.
        assertTrue(gate.shouldRead(WsEvent.GroupsChanged(null, null), "open"))
        assertTrue(gate.shouldRead(WsEvent.GroupsChanged(null, null), "open"))
    }
}
