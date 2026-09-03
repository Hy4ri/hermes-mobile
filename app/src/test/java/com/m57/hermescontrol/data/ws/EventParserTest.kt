package com.m57.hermescontrol.data.ws

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@Suppress("FunctionName")
private fun createJsonRpcResponse(
    jsonrpc: String,
    id: String?,
    result: Any? = null,
    error: JsonRpcError? = null,
    method: String? = null,
    params: Any? = null,
): JsonRpcResponse =
    JsonRpcResponse(
        jsonrpc = jsonrpc,
        id = id,
        result = result?.toJsonElement(),
        error = error,
        method = method,
        params = params?.toJsonElement() as? JsonObject,
    )

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
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
    fun testParseChangeEvents_returnsChangeEvent() {
        // Issue #784: gateway broadcasts change events (pet.changed excluded —
        // mobile has no pet feature). All four must map to ChangeEvent with the
        // type preserved and the payload attached.
        val cases =
            mapOf(
                "cron.changed" to mapOf("a" to "1"),
                "sessions.changed" to mapOf("b" to "2"),
                "platforms.changed" to mapOf("c" to "3"),
                "pairing.changed" to mapOf("d" to "4"),
            )
        cases.forEach { (type, payload) ->
            val response =
                createJsonRpcResponse(
                    jsonrpc = "2.0",
                    id = null,
                    result = null,
                    error = null,
                    method = "event",
                    params = mapOf("type" to type, "payload" to payload),
                )
            val event = EventParser.parse(response)
            assertTrue("expected ChangeEvent for $type, got $event", event is WsEvent.ChangeEvent)
            val changeEvent = event as WsEvent.ChangeEvent
            assertEquals(type, changeEvent.type)
            assertEquals(payload, changeEvent.data)
        }
    }

    @Test
    fun testParseMessageToken_returnsMessageTokenEvent() {
        val response =
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
    fun testParseReasoningDelta_returnsReasoningDeltaEvent() {
        val response =
            createJsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "reasoning.delta",
                        "payload" to mapOf("text" to "reasoning token", "session_id" to "session-r1"),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ReasoningDelta)
        val deltaEvent = event as WsEvent.ReasoningDelta
        assertEquals("reasoning token", deltaEvent.token)
        assertEquals("session-r1", deltaEvent.sessionId)
    }

    @Test
    fun testParseReasoningAvailable_returnsReasoningAvailableEvent() {
        val response =
            createJsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "reasoning.available",
                        "payload" to mapOf("session_id" to "session-r1"),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ReasoningAvailable)
        assertEquals("session-r1", (event as WsEvent.ReasoningAvailable).sessionId)
    }

    @Test
    fun testParseClarifyRequest_returnsClarifyRequestEvent() {
        val response =
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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

    @Test
    fun testParseClarifyRequest_withBatchQuestions_parsesSuccessfully() {
        val response =
            createJsonRpcResponse(
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
                                "request_id" to "req-batch-1",
                                "questions" to
                                    listOf(
                                        mapOf(
                                            "qid" to "q0",
                                            "question" to "Which database?",
                                            "choices" to listOf("Postgres", "SQLite"),
                                            "multi_select" to false,
                                        ),
                                    ),
                            ),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ClarifyRequest)
        val clarifyEvent = event as WsEvent.ClarifyRequest
        assertEquals("Which database?", clarifyEvent.text)
        assertEquals(listOf("Postgres", "SQLite"), clarifyEvent.options)
        assertEquals("req-batch-1", clarifyEvent.clarifyId)
        assertEquals("q0", clarifyEvent.questionId)
    }

    @Test
    fun testParseClarifyRequest_withMultiSelectAndMultipleQuestions() {
        val response =
            createJsonRpcResponse(
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
                                "request_id" to "req-batch-2",
                                "questions" to
                                    listOf(
                                        mapOf(
                                            "qid" to "q0",
                                            "question" to "Pick languages:",
                                            "choices" to listOf("Python", "Go", "Rust"),
                                            "multi_select" to true,
                                        ),
                                        mapOf(
                                            "qid" to "q1",
                                            "question" to "Any comments?",
                                            "choices" to emptyList<String>(),
                                            "multi_select" to false,
                                        ),
                                    ),
                            ),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ClarifyRequest)
        val clarifyEvent = event as WsEvent.ClarifyRequest
        assertEquals("req-batch-2", clarifyEvent.clarifyId)
        assertEquals(2, clarifyEvent.questions.size)

        val q0 = clarifyEvent.questions[0]
        assertEquals("q0", q0.qid)
        assertEquals("Pick languages:", q0.question)
        assertEquals(listOf("Python", "Go", "Rust"), q0.choices)
        assertTrue(q0.multiSelect)

        val q1 = clarifyEvent.questions[1]
        assertEquals("q1", q1.qid)
        assertEquals("Any comments?", q1.question)
        assertTrue(q1.choices.isEmpty())
        assertFalse(q1.multiSelect)
    }

    @Test
    fun testParseClarifyExpire_parsesSuccessfully() {
        val response =
            createJsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "clarify.expire",
                        "payload" to mapOf("request_id" to "req-expire-1"),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ClarifyExpire)
        val expireEvent = event as WsEvent.ClarifyExpire
        assertEquals("req-expire-1", expireEvent.clarifyId)
    }

    // ── TEST-07: Untested subtypes ─────────────────────────────────────

    @Test
    fun testParseSessionInfo_returnsSessionInfoEvent() {
        val response =
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
    fun testParseGatewayError_returnsGatewayErrorEvent() {
        val response =
            createJsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = mapOf("type" to "error", "payload" to mapOf("message" to "boom")),
            )
        val event = EventParser.parse(response, "")
        assertTrue(event is WsEvent.GatewayError)
        assertEquals("boom", (event as WsEvent.GatewayError).message)
    }

    @Test
    fun testParseGatewayError_missingMessage_returnsNullMessage() {
        val response =
            createJsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params = mapOf("type" to "error", "payload" to mapOf<String, String>()),
            )
        val event = EventParser.parse(response, "")
        assertTrue(event is WsEvent.GatewayError)
        assertEquals(null, (event as WsEvent.GatewayError).message)
    }

    @Test
    fun testParseBackgroundComplete_returnsBackgroundCompleteEvent() {
        val response =
            createJsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "background.complete",
                        "payload" to mapOf("label" to "nightly backup"),
                    ),
            )
        val event = EventParser.parse(response, "")
        assertTrue(event is WsEvent.BackgroundComplete)
        assertEquals(
            "nightly backup",
            (event as WsEvent.BackgroundComplete).data?.get("label"),
        )
    }

    @Test
    fun testParseUnknownType_returnsUnknownEvent() {
        val response =
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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
            createJsonRpcResponse(
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

    @Test
    fun testParseToolProgress_returnsToolProgressEvent() {
        val response =
            createJsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "tool.progress",
                        "session_id" to "sess-123",
                        "payload" to mapOf("name" to "web_search", "preview" to "downloading content..."),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ToolProgress)
        val toolProgress = event as WsEvent.ToolProgress
        assertEquals("web_search", toolProgress.name)
        assertEquals("downloading content...", toolProgress.preview)
        assertEquals("sess-123", toolProgress.sessionId)
    }

    @Test
    fun testParseToolGenerating_returnsToolGeneratingEvent() {
        val response =
            createJsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "tool.generating",
                        "session_id" to "sess-123",
                        "payload" to mapOf("name" to "code_writer"),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.ToolGenerating)
        val toolGenerating = event as WsEvent.ToolGenerating
        assertEquals("code_writer", toolGenerating.name)
        assertEquals("sess-123", toolGenerating.sessionId)
    }

    @Test
    fun testParseSubagentStart_returnsSubagentEvent() {
        val response =
            createJsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "subagent.start",
                        "session_id" to "sess-123",
                        "payload" to
                            mapOf(
                                "goal" to "research oauth providers",
                                "task_index" to 1,
                                "task_count" to 5,
                                "subagent_id" to "sub-456",
                                "text" to "analyzing docs...",
                            ),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.SubagentEvent)
        val subagentEvent = event as WsEvent.SubagentEvent
        assertEquals("subagent.start", subagentEvent.type)
        assertEquals("sess-123", subagentEvent.sessionId)
        assertEquals("research oauth providers", subagentEvent.payload?.get("goal"))
        assertEquals(1, (subagentEvent.payload?.get("task_index") as? Number)?.toInt())
        assertEquals(5, (subagentEvent.payload?.get("task_count") as? Number)?.toInt())
        assertEquals("sub-456", subagentEvent.payload?.get("subagent_id"))
        assertEquals("analyzing docs...", subagentEvent.payload?.get("text"))
    }

    @Test
    fun testParseSessionUsage_returnsSessionUsageEvent() {
        val response =
            createJsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "session.usage",
                        "session_id" to "sess-123",
                        "payload" to
                            mapOf(
                                "usage" to
                                    mapOf(
                                        "compressions" to 3,
                                        "context_used" to 12500,
                                        "context_max" to 128000,
                                        "total" to 45000,
                                    ),
                            ),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.SessionUsage)
        val sessionUsage = event as WsEvent.SessionUsage
        assertEquals("sess-123", sessionUsage.sessionId)
        val usage = sessionUsage.data?.get("usage") as? Map<*, *>
        assertEquals(3, (usage?.get("compressions") as? Number)?.toInt())
        assertEquals(12500, (usage?.get("context_used") as? Number)?.toInt())
    }

    @Test
    fun testParseParams_parsesBareEventMap() {
        val params =
            mapOf(
                "type" to "message.token",
                "session_id" to "sess-replay-1",
                "seq" to 42,
                "payload" to mapOf("text" to "hello replay"),
            )
        val event = EventParser.parseParams(params)
        assertTrue(event is WsEvent.MessageToken)
        val tokenEvent = event as WsEvent.MessageToken
        assertEquals("hello replay", tokenEvent.token)
        assertEquals("sess-replay-1", tokenEvent.sessionId)
    }

    @Test
    fun testParseParams_parsesToolEvent() {
        val params =
            mapOf(
                "type" to "tool.start",
                "session_id" to "sess-replay-2",
                "seq" to 43,
                "payload" to mapOf("name" to "terminal", "tool_id" to "t1"),
            )
        val event = EventParser.parseParams(params)
        assertTrue(event is WsEvent.ToolStart)
        val toolEvent = event as WsEvent.ToolStart
        assertEquals("terminal", toolEvent.name)
        assertEquals("sess-replay-2", toolEvent.sessionId)
    }

    @Test
    fun testParseTodoUpdated_returnsTodoUpdatedEvent() {
        val response =
            createJsonRpcResponse(
                jsonrpc = "2.0",
                id = null,
                result = null,
                error = null,
                method = "event",
                params =
                    mapOf(
                        "type" to "todo.updated",
                        "session_id" to "sess-abc",
                        "payload" to
                            mapOf(
                                "revision" to 5,
                                "todos" to
                                    listOf(
                                        mapOf("id" to "1", "content" to "Parent task", "status" to "in_progress"),
                                        mapOf(
                                            "id" to "2",
                                            "content" to "Sub task",
                                            "status" to "pending",
                                            "parent" to "1",
                                        ),
                                    ),
                            ),
                    ),
            )
        val event = EventParser.parse(response)
        assertTrue(event is WsEvent.TodoUpdated)
        val todoUpdated = event as WsEvent.TodoUpdated
        assertEquals("sess-abc", todoUpdated.sessionId)
        assertEquals(5, todoUpdated.revision)
        assertEquals(2, todoUpdated.todos.size)
        assertEquals("1", todoUpdated.todos[0].id)
        assertEquals("Parent task", todoUpdated.todos[0].content)
        assertEquals(null, todoUpdated.todos[0].parent)
        assertFalse(todoUpdated.todos[0].isSubtask)
        assertEquals("2", todoUpdated.todos[1].id)
        assertEquals("Sub task", todoUpdated.todos[1].content)
        assertEquals("1", todoUpdated.todos[1].parent)
        assertTrue(todoUpdated.todos[1].isSubtask)
    }

    @Test
    fun testParseSudoRequest_returnsSudoRequest() {
        val params =
            mapOf(
                "type" to "sudo.request",
                "session_id" to "sess-sudo-1",
                "payload" to mapOf("request_id" to "sudo-1"),
            )
        val event = EventParser.parseParams(params)
        assertTrue(event is WsEvent.SudoRequest)
        val sudo = event as WsEvent.SudoRequest
        assertEquals("sudo-1", sudo.requestId)
        assertEquals("sess-sudo-1", sudo.sessionId)
    }

    @Test
    fun testParseSudoExpire_returnsSudoExpire() {
        val params =
            mapOf(
                "type" to "sudo.expire",
                "session_id" to "sess-sudo-1",
                "payload" to mapOf("request_id" to "sudo-1"),
            )
        val event = EventParser.parseParams(params)
        assertTrue(event is WsEvent.SudoExpire)
        val expire = event as WsEvent.SudoExpire
        assertEquals("sudo-1", expire.requestId)
        assertEquals("sess-sudo-1", expire.sessionId)
    }

    @Test
    fun testParseSecretRequest_withEnvVarAndPrompt() {
        val params =
            mapOf(
                "type" to "secret.request",
                "session_id" to "sess-secret-1",
                "payload" to
                    mapOf(
                        "request_id" to "secret-1",
                        "env_var" to "GITHUB_TOKEN",
                        "prompt" to "Enter your GitHub token to continue",
                    ),
            )
        val event = EventParser.parseParams(params)
        assertTrue(event is WsEvent.SecretRequest)
        val secret = event as WsEvent.SecretRequest
        assertEquals("secret-1", secret.requestId)
        assertEquals("sess-secret-1", secret.sessionId)
        assertEquals("GITHUB_TOKEN", secret.envVar)
        assertEquals("Enter your GitHub token to continue", secret.prompt)
    }

    @Test
    fun testParseSecretExpire_returnsSecretExpire() {
        val params =
            mapOf(
                "type" to "secret.expire",
                "session_id" to "sess-secret-1",
                "payload" to mapOf("request_id" to "secret-1"),
            )
        val event = EventParser.parseParams(params)
        assertTrue(event is WsEvent.SecretExpire)
        val expire = event as WsEvent.SecretExpire
        assertEquals("secret-1", expire.requestId)
        assertEquals("sess-secret-1", expire.sessionId)
    }
}
