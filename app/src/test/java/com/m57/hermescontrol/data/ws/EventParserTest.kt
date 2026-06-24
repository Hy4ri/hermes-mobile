package com.m57.hermescontrol.data.ws

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EventParserTest {
    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun testParseRpcResult_returnsRpcResultEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = "123",
                result = mapOf("status" to "success"),
                error = null,
                method = null,
                params = null,
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.RpcResult)
        val rpcResult = event as WsEvent.RpcResult
        assertEquals("123", rpcResult.id)
        assertEquals(mapOf("status" to "success"), rpcResult.result)
    }

    @Test
    fun testParseRpcError_returnsRpcErrorEvent() {
        val error = JsonRpcError(code = -32600, message = "Invalid Request")
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = "456",
                result = null,
                error = error,
                method = null,
                params = null,
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.RpcError)
        val rpcError = event as WsEvent.RpcError
        assertEquals("456", rpcError.id)
        assertEquals(-32600, rpcError.error.code)
        assertEquals("Invalid Request", rpcError.error.message)
    }

    @Test
    fun testParseGatewayReady_returnsGatewayReadyEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "gateway.ready",
                        "payload" to mapOf("session_id" to "session-1"),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.GatewayReady)
        val gatewayReady = event as WsEvent.GatewayReady
        assertEquals(mapOf("session_id" to "session-1"), gatewayReady.data)
    }

    @Test
    fun testParseMessageToken_returnsMessageTokenEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "message.token",
                        "payload" to mapOf("text" to "hello", "session_id" to "session-1"),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.MessageToken)
        val tokenEvent = event as WsEvent.MessageToken
        assertEquals("hello", tokenEvent.token)
        assertEquals("session-1", tokenEvent.sessionId)
    }

    @Test
    fun testParseMessageToken_withSessionIdInParams_returnsMessageTokenEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "message.token",
                        "session_id" to "session-params-1",
                        "payload" to mapOf("text" to "hello"),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.MessageToken)
        val tokenEvent = event as WsEvent.MessageToken
        assertEquals("hello", tokenEvent.token)
        assertEquals("session-params-1", tokenEvent.sessionId)
    }

    @Test
    fun testParseThinkingDelta_returnsThinkingDeltaEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "thinking.delta",
                        "payload" to mapOf("text" to "thinking token", "session_id" to "session-2"),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ThinkingDelta)
        val deltaEvent = event as WsEvent.ThinkingDelta
        assertEquals("thinking token", deltaEvent.token)
        assertEquals("session-2", deltaEvent.sessionId)
    }

    @Test
    fun testParseClarifyRequest_returnsClarifyRequestEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "clarify.request",
                        "payload" to
                            mapOf(
                                "text" to "Select option?",
                                "options" to listOf("Yes", "No"),
                            ),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ClarifyRequest)
        val clarifyEvent = event as WsEvent.ClarifyRequest
        assertEquals("Select option?", clarifyEvent.text)
        assertEquals(listOf("Yes", "No"), clarifyEvent.options)
    }

    @Test
    fun testParseClarifyRequest_withQuestionFields_parsesSuccessfully() {
        val response =
            WsResponse(
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "clarify.request",
                        "payload" to
                            mapOf(
                                "question" to "Which environment?",
                                "choices" to listOf("staging", "production"),
                            ),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ClarifyRequest)
        val clarifyEvent = event as WsEvent.ClarifyRequest
        assertEquals("Which environment?", clarifyEvent.text)
        assertEquals(listOf("staging", "production"), clarifyEvent.options)
    }

    // ── TEST-07: Untested subtypes ─────────────────────────────────────

    @Test
    fun testParseSessionInfo_returnsSessionInfoEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = mapOf("type" to "session.info", "payload" to mapOf("session_id" to "sess-1")),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.SessionInfo)
        assertEquals(mapOf("session_id" to "sess-1"), (event as WsEvent.SessionInfo).data)
    }

    @Test
    fun testParseMessageStart_returnsMessageStartEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "message.start",
                        "session_id" to "sess-1",
                        "payload" to mapOf<String, String>(),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.MessageStart)
        assertEquals("sess-1", (event as WsEvent.MessageStart).sessionId)
    }

    @Test
    fun testParseMessageDelta_returnsMessageTokenEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = mapOf("type" to "message.delta", "payload" to mapOf("text" to "delta-token")),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.MessageToken)
        val tokenEvent = event as WsEvent.MessageToken
        assertEquals("delta-token", tokenEvent.token)
    }

    @Test
    fun testParseMessageComplete_returnsMessageCompleteEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = mapOf("type" to "message.complete", "payload" to mapOf("text" to "full text")),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.MessageComplete)
        assertEquals("full text", (event as WsEvent.MessageComplete).text)
    }

    @Test
    fun testParseMessageDone_returnsMessageDoneEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = mapOf("type" to "message.done", "payload" to mapOf("session_id" to "sess-1")),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.MessageDone)
    }

    @Test
    fun testParseToolStart_returnsToolStartEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "tool.start",
                        "payload" to mapOf("name" to "web_search", "query" to "hello"),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ToolStart)
        val toolStart = event as WsEvent.ToolStart
        assertEquals("web_search", toolStart.name)
        assertEquals("hello", toolStart.data?.get("query"))
    }

    @Test
    fun testParseToolComplete_returnsToolCompleteEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "tool.complete",
                        "payload" to mapOf("name" to "file_read", "status" to "ok"),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ToolComplete)
        val toolComplete = event as WsEvent.ToolComplete
        assertEquals("file_read", toolComplete.name)
        assertEquals("ok", toolComplete.data?.get("status"))
    }

    @Test
    fun testParseStatusUpdate_returnsStatusUpdateEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = mapOf("type" to "status.update", "payload" to mapOf("status" to "processing")),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.StatusUpdate)
        assertEquals("processing", (event as WsEvent.StatusUpdate).status)
    }

    @Test
    fun testParseSessionUpdated_returnsSessionUpdatedEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = mapOf("type" to "session.updated", "payload" to mapOf("session_id" to "sess-1")),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.SessionUpdated)
        assertEquals(mapOf("session_id" to "sess-1"), (event as WsEvent.SessionUpdated).data)
    }

    @Test
    fun testParseUnknownType_returnsUnknownEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = mapOf("type" to "bogus.event"),
            )
        val event = EventParser.parse(response, """{"raw": true}""")
        assertTrue(event is WsEvent.Unknown)
    }

    @Test
    fun testParseNullParams_returnsUnknownEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = null,
            )
        val event = EventParser.parse(response, """{"no":"params"}""")
        assertTrue(event is WsEvent.Unknown)
    }

    @Test
    fun testParseNullTypeInParams_returnsUnknownEvent() {
        val response =
            JsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = mapOf("type" to null, "payload" to mapOf<String, String>()),
            )
        val event = EventParser.parse(response, """{"null":"type"}""")
        assertTrue(event is WsEvent.Unknown)
    }
}
