package com.m57.hermescontrol.data.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventParserTest {
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
}
