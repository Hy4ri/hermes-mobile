package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.LiveSessionSnapshot
import com.m57.hermescontrol.data.model.SessionLiveStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLiveStatusSourceTest {
    @Test
    fun `decodeSnapshot decodes working and waiting statuses by stored id`() {
        val payload =
            mapOf(
                "sessions" to
                    listOf(
                        mapOf(
                            "id" to "runtime-1",
                            "session_key" to "stored-1",
                            "status" to "working",
                        ),
                        mapOf(
                            "id" to "runtime-2",
                            "session_key" to "stored-2",
                            "status" to "waiting",
                        ),
                    ),
            )

        val snapshot = SessionLiveStatusDecoder.decodeSnapshot(payload)

        assertNotNull(snapshot)
        assertEquals(SessionLiveStatus.WORKING, snapshot!!.statusByStoredId["stored-1"])
        assertEquals(SessionLiveStatus.WAITING, snapshot.statusByStoredId["stored-2"])
        assertEquals("stored-1", snapshot.storedIdByRuntimeId["runtime-1"])
        assertEquals("stored-2", snapshot.storedIdByRuntimeId["runtime-2"])
    }

    @Test
    fun `decodeSnapshot ignores idle and starting for status but keeps runtime mapping`() {
        val payload =
            mapOf(
                "sessions" to
                    listOf(
                        mapOf(
                            "id" to "runtime-idle",
                            "session_key" to "stored-idle",
                            "status" to "idle",
                        ),
                        mapOf(
                            "id" to "runtime-start",
                            "session_key" to "stored-start",
                            "status" to "starting",
                        ),
                    ),
            )

        val snapshot = SessionLiveStatusDecoder.decodeSnapshot(payload)

        assertNotNull(snapshot)
        assertTrue(snapshot!!.statusByStoredId.isEmpty())
        assertEquals("stored-idle", snapshot.storedIdByRuntimeId["runtime-idle"])
        assertEquals("stored-start", snapshot.storedIdByRuntimeId["runtime-start"])
    }

    @Test
    fun `decodeSnapshot skips items with blank id or session_key`() {
        val payload =
            mapOf(
                "sessions" to
                    listOf(
                        mapOf(
                            "id" to "",
                            "session_key" to "stored-1",
                            "status" to "working",
                        ),
                        mapOf(
                            "id" to "runtime-2",
                            "session_key" to "   ",
                            "status" to "working",
                        ),
                        mapOf(
                            "id" to "runtime-3",
                            "session_key" to "stored-3",
                            "status" to "working",
                        ),
                    ),
            )

        val snapshot = SessionLiveStatusDecoder.decodeSnapshot(payload)

        assertNotNull(snapshot)
        assertEquals(1, snapshot!!.statusByStoredId.size)
        assertEquals(SessionLiveStatus.WORKING, snapshot.statusByStoredId["stored-3"])
        assertEquals(1, snapshot.storedIdByRuntimeId.size)
        assertEquals("stored-3", snapshot.storedIdByRuntimeId["runtime-3"])
    }

    @Test
    fun `decodeSnapshot returns authoritative empty snapshot on empty sessions list`() {
        val payload = mapOf("sessions" to emptyList<Map<String, Any>>())

        val snapshot = SessionLiveStatusDecoder.decodeSnapshot(payload)

        assertNotNull(snapshot)
        assertTrue(snapshot!!.statusByStoredId.isEmpty())
        assertTrue(snapshot.storedIdByRuntimeId.isEmpty())
    }

    @Test
    fun `decodeSnapshot returns null for malformed or missing sessions field`() {
        assertNull(SessionLiveStatusDecoder.decodeSnapshot(null))
        assertNull(SessionLiveStatusDecoder.decodeSnapshot(mapOf("other" to "data")))
        assertNull(SessionLiveStatusDecoder.decodeSnapshot("invalid-json-string"))
    }

    @Test
    fun `HermesSessionLiveStatusSource fetches and decodes snapshot via RPC`() =
        runBlocking {
            var capturedMethod: String? = null
            var capturedParams: Map<String, Any>? = null

            val source =
                HermesSessionLiveStatusSource(
                    rpcRequest = { method, params ->
                        capturedMethod = method
                        capturedParams = params
                        mapOf(
                            "sessions" to
                                listOf(
                                    mapOf(
                                        "id" to "rt-1",
                                        "session_key" to "st-1",
                                        "status" to "working",
                                    ),
                                ),
                        )
                    },
                    eventsProvider = { MutableSharedFlow() },
                    connectionStatusProvider = { MutableStateFlow(ConnectionStatus.CONNECTED) },
                )

            val snapshot = source.fetchActiveSessionsSnapshot()

            assertEquals(WsMethods.SESSION_ACTIVE_LIST, capturedMethod)
            assertTrue(capturedParams!!.isEmpty())
            assertNotNull(snapshot)
            assertEquals(SessionLiveStatus.WORKING, snapshot!!.statusByStoredId["st-1"])
        }

    @Test
    fun `HermesSessionLiveStatusSource returns null when RPC throws`() =
        runBlocking {
            val source =
                HermesSessionLiveStatusSource(
                    rpcRequest = { _, _ -> throw RuntimeException("RPC error") },
                    eventsProvider = { MutableSharedFlow() },
                    connectionStatusProvider = { MutableStateFlow(ConnectionStatus.CONNECTED) },
                )

            val snapshot = source.fetchActiveSessionsSnapshot()
            assertNull(snapshot)
        }
}
