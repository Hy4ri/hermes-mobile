package com.m57.hermescontrol.data.ws

import com.m57.hermescontrol.data.model.LiveWorkCounts
import com.m57.hermescontrol.data.model.LiveWorkRoster
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `subagent.roster` decoding and the -32601 fallback (#387). */
class SubagentRosterTest {
    private fun source(request: suspend () -> Any?) =
        HermesSessionLiveStatusSource(
            rpcRequest = { null },
            rosterRequest = request,
            eventsProvider = { MutableSharedFlow() },
            connectionStatusProvider = { MutableStateFlow(ConnectionStatus.CONNECTED) },
        )

    @Test
    fun `decode reads counts keyed by stored session id`() {
        val raw =
            Json.parseToJsonElement(
                """{"sessions":[{"session_id":"s1","subagents":2,"background":1},{"session_id":"s2","background":3}]}""",
            )
        assertEquals(
            mapOf("s1" to LiveWorkCounts(2, 1), "s2" to LiveWorkCounts(0, 3)),
            SubagentRosterDecoder.decode(raw),
        )
    }

    @Test
    fun `decode drops rows without an id or without live work`() {
        val raw =
            mapOf(
                "sessions" to
                    listOf(
                        mapOf("session_id" to "", "subagents" to 1),
                        mapOf("subagents" to 1),
                        mapOf("session_id" to "idle", "subagents" to 0, "background" to 0),
                        mapOf("session_id" to "neg", "subagents" to -4),
                        mapOf("session_id" to "ok", "subagents" to 1, "extra" to "x"),
                    ),
            )
        assertEquals(mapOf("ok" to LiveWorkCounts(subagents = 1)), SubagentRosterDecoder.decode(raw))
    }

    @Test
    fun `decode returns empty for no sessions and null for a malformed reply`() {
        assertTrue(SubagentRosterDecoder.decode(mapOf("sessions" to emptyList<Any>()))!!.isEmpty())
        assertNull(SubagentRosterDecoder.decode(null))
        assertNull(SubagentRosterDecoder.decode(mapOf("other" to 1)))
        assertNull(SubagentRosterDecoder.decode(mapOf("sessions" to "nope")))
    }

    @Test
    fun `fetch maps a reply to counts`() =
        runBlocking {
            val roster =
                source {
                    mapOf("sessions" to listOf(mapOf("session_id" to "s1", "subagents" to 1)))
                }.fetchLiveWorkRoster()
            assertEquals(LiveWorkRoster.Counts(mapOf("s1" to LiveWorkCounts(subagents = 1))), roster)
        }

    @Test
    fun `method not found means unsupported, other failures mean unavailable`() =
        runBlocking {
            assertEquals(
                LiveWorkRoster.Unsupported,
                source { throw HermesWsClient.HermesRpcException("unknown method", code = -32601) }
                    .fetchLiveWorkRoster(),
            )
            assertEquals(
                LiveWorkRoster.Unavailable,
                source { throw HermesWsClient.HermesRpcException("boom", code = 5000) }.fetchLiveWorkRoster(),
            )
            assertEquals(LiveWorkRoster.Unavailable, source { throw RuntimeException("timeout") }.fetchLiveWorkRoster())
            assertEquals(LiveWorkRoster.Unavailable, source { mapOf("bad" to 1) }.fetchLiveWorkRoster())
        }

    @Test
    fun `roster is profile scoped and in the typed contract`() {
        assertTrue(WsMethods.SUBAGENT_ROSTER in WsMethods.PROFILE_SCOPED_METHODS)
        assertTrue(GatewayContract.methods.contains(WsMethods.SUBAGENT_ROSTER))
    }
}
