package com.m57.hermescontrol.data.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionOperationEventParserTest {
    @Test
    fun connectionRequest_usesEnvelopeSessionAndPayloadSequence() {
        val event =
            EventParser.parseParams(
                mapOf(
                    "type" to "connection.request",
                    "session_id" to "runtime-session",
                    "seq" to 999,
                    "payload" to operationPayload(seq = 4L),
                ),
            )

        assertTrue(event is WsEvent.ConnectionRequest)
        val snapshot = (event as WsEvent.ConnectionRequest).snapshot
        assertEquals("runtime-session", snapshot.sessionId)
        assertEquals(4L, snapshot.seq)
    }

    @Test
    fun connectionUpdate_parsesFullSettledSnapshot() {
        val event =
            EventParser.parseParams(
                mapOf(
                    "type" to "connection.update",
                    "session_id" to "runtime-session",
                    "payload" to
                        operationPayload(seq = 5L) +
                        mapOf(
                            "settled" to true,
                            "settled_by" to "all_resolved",
                            "future_field" to "ignored",
                        ),
                ),
            )

        assertTrue(event is WsEvent.ConnectionUpdate)
        val snapshot = (event as WsEvent.ConnectionUpdate).snapshot
        assertTrue(snapshot.settled)
        assertEquals("all_resolved", snapshot.settledBy)
    }

    @Test
    fun malformedOperation_degradesWithoutRetainingRawFrame() {
        val event =
            EventParser.parseParams(
                params =
                    mapOf(
                        "type" to "connection.request",
                        "session_id" to "runtime-session",
                        "payload" to operationPayload(seq = 1L) - "op_id",
                    ),
                rawJson = "issue1218-secret-canary",
            )

        assertTrue(event is WsEvent.Unknown)
        assertEquals("", (event as WsEvent.Unknown).raw)
    }

    @Test
    fun connectionUpdate_bindsToSessionOwner() {
        val event =
            EventParser.parseParams(
                mapOf(
                    "type" to "connection.update",
                    "payload" to
                        operationPayload(seq = 2L) +
                        mapOf("owner" to mapOf("type" to "session", "session_id" to "owner-session")),
                ),
            )

        assertTrue(event is WsEvent.ConnectionUpdate)
        assertEquals("owner-session", (event as WsEvent.ConnectionUpdate).snapshot.sessionId)
    }

    @Test
    fun connectionUpdate_accountOwnerNeverBindsToSession() {
        val event =
            EventParser.parseParams(
                mapOf(
                    "type" to "connection.update",
                    "session_id" to "",
                    "payload" to
                        operationPayload(seq = 2L) +
                        mapOf("owner" to mapOf("type" to "account"), "session_id" to "stale-session"),
                ),
            )

        assertTrue(event is WsEvent.ConnectionUpdate)
        assertNull((event as WsEvent.ConnectionUpdate).snapshot.sessionId)
    }

    private fun operationPayload(seq: Long): Map<String, Any?> =
        mapOf(
            "op_id" to "op-1",
            "seq" to seq,
            "deadline_at" to 2_000_000_000.0,
            "timeout_seconds" to 120.0,
            "tool_call_id" to "tool-1",
            "targets" to
                listOf(
                    mapOf(
                        "name" to "github",
                        "kind" to "connector",
                        "action" to "authorize",
                        "state" to "pending",
                    ),
                ),
        )
}
