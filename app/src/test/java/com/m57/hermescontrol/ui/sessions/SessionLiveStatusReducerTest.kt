package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.LiveSessionSnapshot
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.data.ws.WsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLiveStatusReducerTest {
    @Test
    fun `snapshot replacement seeds WORKING and WAITING by stored ID`() {
        val snapshot =
            LiveSessionSnapshot(
                statusByStoredId =
                    mapOf(
                        "stored-1" to SessionLiveStatus.WORKING,
                        "stored-2" to SessionLiveStatus.WAITING,
                    ),
                storedIdByRuntimeId =
                    mapOf(
                        "rt-1" to "stored-1",
                        "rt-2" to "stored-2",
                    ),
            )

        val state = SessionLiveStatusReducer.applySnapshot(SessionLiveTrackingState(), snapshot)

        assertEquals(SessionLiveStatus.WORKING, state.liveStatuses["stored-1"])
        assertEquals(SessionLiveStatus.WAITING, state.liveStatuses["stored-2"])
        assertEquals("stored-1", state.storedIdByRuntimeId["rt-1"])
        assertEquals("stored-2", state.storedIdByRuntimeId["rt-2"])
    }

    @Test
    fun `later successful empty snapshot removes previously live rows`() {
        val initial =
            SessionLiveTrackingState(
                liveStatuses = mapOf("stored-1" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            )

        val state =
            SessionLiveStatusReducer.applySnapshot(
                initial,
                LiveSessionSnapshot(emptyMap(), emptyMap()),
            )

        assertTrue(state.liveStatuses.isEmpty())
        assertTrue(state.storedIdByRuntimeId.isEmpty())
    }

    @Test
    fun `runtime ID reuse moves activity from old stored ID to new stored ID`() {
        val initial =
            SessionLiveTrackingState(
                liveStatuses = mapOf("stored-old" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-old"),
            )

        val event =
            WsEvent.SessionInfo(
                data =
                    mapOf(
                        "session_id" to "rt-1",
                        "stored_session_id" to "stored-new",
                        "running" to true,
                    ),
            )

        val state = SessionLiveStatusReducer.applyWsEvent(initial, event)

        assertNull(state.liveStatuses["stored-old"])
        assertEquals(SessionLiveStatus.WORKING, state.liveStatuses["stored-new"])
        assertEquals("stored-new", state.storedIdByRuntimeId["rt-1"])
    }

    @Test
    fun `session info with stored_session_id and running=true marks WORKING`() {
        val initial = SessionLiveTrackingState()

        val event =
            WsEvent.SessionInfo(
                data =
                    mapOf(
                        "session_id" to "rt-1",
                        "stored_session_id" to "stored-1",
                        "running" to true,
                    ),
            )

        val state = SessionLiveStatusReducer.applyWsEvent(initial, event)

        assertEquals(SessionLiveStatus.WORKING, state.liveStatuses["stored-1"])
        assertEquals("stored-1", state.storedIdByRuntimeId["rt-1"])
    }

    @Test
    fun `session info with running=false clears that stored row`() {
        val initial =
            SessionLiveTrackingState(
                liveStatuses = mapOf("stored-1" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            )

        val event =
            WsEvent.SessionInfo(
                data =
                    mapOf(
                        "session_id" to "rt-1",
                        "stored_session_id" to "stored-1",
                        "running" to false,
                    ),
            )

        val state = SessionLiveStatusReducer.applyWsEvent(initial, event)

        assertNull(state.liveStatuses["stored-1"])
        assertEquals("stored-1", state.storedIdByRuntimeId["rt-1"])
    }

    @Test
    fun `message start marks WORKING only when runtime-to-stored identity is known`() {
        val initial =
            SessionLiveTrackingState(
                liveStatuses = emptyMap(),
                storedIdByRuntimeId = mapOf("rt-known" to "stored-known"),
            )

        // Unknown runtime id -> no mutation
        val unknownEvent = WsEvent.MessageStart(sessionId = "rt-unknown")
        val state1 = SessionLiveStatusReducer.applyWsEvent(initial, unknownEvent)
        assertTrue(state1.liveStatuses.isEmpty())

        // Known runtime id -> marks WORKING
        val knownEvent = WsEvent.MessageStart(sessionId = "rt-known")
        val state2 = SessionLiveStatusReducer.applyWsEvent(state1, knownEvent)
        assertEquals(SessionLiveStatus.WORKING, state2.liveStatuses["stored-known"])
    }

    @Test
    fun `message complete and message done clear only matching runtime`() {
        val initial =
            SessionLiveTrackingState(
                liveStatuses =
                    mapOf(
                        "stored-1" to SessionLiveStatus.WORKING,
                        "stored-2" to SessionLiveStatus.WORKING,
                    ),
                storedIdByRuntimeId =
                    mapOf(
                        "rt-1" to "stored-1",
                        "rt-2" to "stored-2",
                    ),
            )

        val completeEvent = WsEvent.MessageComplete(text = "done", sessionId = "rt-1")
        val state1 = SessionLiveStatusReducer.applyWsEvent(initial, completeEvent)

        assertNull(state1.liveStatuses["stored-1"])
        assertEquals(SessionLiveStatus.WORKING, state1.liveStatuses["stored-2"])

        val doneEvent = WsEvent.MessageDone(sessionId = "rt-2")
        val state2 = SessionLiveStatusReducer.applyWsEvent(state1, doneEvent)

        assertNull(state2.liveStatuses["stored-2"])
        assertTrue(state2.liveStatuses.isEmpty())
    }

    @Test
    fun `approval request and clarify request mark WAITING for known runtime`() {
        val initial =
            SessionLiveTrackingState(
                liveStatuses = mapOf("stored-1" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            )

        val approvalEvent =
            WsEvent.ApprovalRequest(
                command = "rm -rf",
                description = "delete",
                patternKeys = null,
                sessionId = "rt-1",
                requestId = "req-1",
                choices = listOf("allow", "deny"),
                allowPermanent = false,
                smartDenied = false,
            )

        val state = SessionLiveStatusReducer.applyWsEvent(initial, approvalEvent)
        assertEquals(SessionLiveStatus.WAITING, state.liveStatuses["stored-1"])

        val clarifyEvent =
            WsEvent.ClarifyRequest(
                text = "which option?",
                options = listOf("a", "b"),
                sessionId = "rt-1",
            )

        val state2 = SessionLiveStatusReducer.applyWsEvent(initial, clarifyEvent)
        assertEquals(SessionLiveStatus.WAITING, state2.liveStatuses["stored-1"])
    }

    @Test
    fun `unknown or missing IDs never mutate another session`() {
        val initial =
            SessionLiveTrackingState(
                liveStatuses = mapOf("stored-1" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            )

        val state1 = SessionLiveStatusReducer.applyWsEvent(initial, WsEvent.MessageStart(sessionId = null))
        assertEquals(initial, state1)

        val state2 = SessionLiveStatusReducer.applyWsEvent(initial, WsEvent.MessageDone(sessionId = "other"))
        assertEquals(initial, state2)

        val state3 =
            SessionLiveStatusReducer.applyWsEvent(
                initial,
                WsEvent.SessionInfo(data = null),
            )
        assertEquals(initial, state3)
    }

    @Test
    fun `clear resets state to empty`() {
        val initial =
            SessionLiveTrackingState(
                liveStatuses = mapOf("stored-1" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            )

        val cleared = SessionLiveStatusReducer.clear()
        assertTrue(cleared.liveStatuses.isEmpty())
        assertTrue(cleared.storedIdByRuntimeId.isEmpty())
    }
}
