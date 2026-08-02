package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWsEventReducerTest {
    @Test
    fun testToolProgress_updatesProgressPreviewForMatchingRunningTool() {
        val initialMessage =
            ChatMessage(
                role = MessageRole.TOOL,
                content = "{}",
                toolName = "web_search",
                toolStatus = ToolStatus.RUNNING,
            )
        val state =
            ChatUiState(
                messages = listOf(initialMessage),
                currentSessionId = "session-1",
            )
        val event =
            WsEvent.ToolProgress(
                name = "web_search",
                preview = "fetching google...",
                sessionId = "session-1",
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.messages.size)
        val updatedMessage = result.state.messages.first()
        assertEquals(ToolStatus.RUNNING, updatedMessage.toolStatus)
        assertEquals("fetching google...", updatedMessage.progressPreview)
    }

    @Test
    fun testToolGenerating_clearsProgressPreviewForMatchingRunningTool() {
        val initialMessage =
            ChatMessage(
                role = MessageRole.TOOL,
                content = "{}",
                toolName = "code_writer",
                toolStatus = ToolStatus.RUNNING,
                progressPreview = "writing...",
            )
        val state =
            ChatUiState(
                messages = listOf(initialMessage),
                currentSessionId = "session-1",
            )
        val event =
            WsEvent.ToolGenerating(
                name = "code_writer",
                sessionId = "session-1",
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.messages.size)
        val updatedMessage = result.state.messages.first()
        assertEquals(ToolStatus.RUNNING, updatedMessage.toolStatus)
        assertEquals("", updatedMessage.progressPreview)
    }

    @Test
    fun testSubagentEvent_appendsToSubagentIndicators() {
        val state =
            ChatUiState(
                currentSessionId = "session-1",
                subagentIndicators = emptyList(),
            )
        val event =
            WsEvent.SubagentEvent(
                type = "subagent.start",
                sessionId = "session-1",
                payload =
                    mapOf(
                        "goal" to "analyze repository",
                        "task_index" to 2,
                        "task_count" to 4,
                        "subagent_id" to "sub-1",
                        "text" to "analyzing files",
                    ),
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.subagentIndicators.size)
        val indicator = result.state.subagentIndicators.first()
        assertEquals("subagent.start", indicator.type)
        assertEquals("analyze repository", indicator.goal)
        assertEquals(2, indicator.taskIndex)
        assertEquals(4, indicator.taskCount)
        assertEquals("sub-1", indicator.subagentId)
        assertEquals("analyzing files", indicator.text)
    }

    @Test
    fun testSubagentEvent_updatesExistingIndicatorBySubagentId() {
        val initialIndicator =
            SubagentIndicator(
                type = "subagent.start",
                goal = "analyze repository",
                taskIndex = 1,
                taskCount = 4,
                subagentId = "sub-1",
                text = "starting",
            )
        val state =
            ChatUiState(
                currentSessionId = "session-1",
                subagentIndicators = listOf(initialIndicator),
            )
        val event =
            WsEvent.SubagentEvent(
                type = "subagent.progress",
                sessionId = "session-1",
                payload =
                    mapOf(
                        "task_index" to 2,
                        "subagent_id" to "sub-1",
                        "text" to "in progress",
                    ),
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.subagentIndicators.size)
        val indicator = result.state.subagentIndicators.first()
        assertEquals("subagent.progress", indicator.type)
        assertEquals("analyze repository", indicator.goal)
        assertEquals(2, indicator.taskIndex)
        assertEquals(4, indicator.taskCount)
        assertEquals("sub-1", indicator.subagentId)
        assertEquals("in progress", indicator.text)
    }

    @Test
    fun testSubagentComplete_updatesIndicatorToCompleted() {
        val initialIndicator =
            SubagentIndicator(
                type = "subagent.start",
                goal = "analyze repository",
                taskIndex = 1,
                taskCount = 4,
                subagentId = "sub-1",
                text = "starting",
            )
        val state =
            ChatUiState(
                currentSessionId = "session-1",
                subagentIndicators = listOf(initialIndicator),
            )
        val event =
            WsEvent.SubagentEvent(
                type = "subagent.complete",
                sessionId = "session-1",
                payload =
                    mapOf(
                        "subagent_id" to "sub-1",
                        "status" to "completed",
                        "summary" to "done",
                    ),
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.subagentIndicators.size)
        val indicator = result.state.subagentIndicators.first()
        assertEquals("subagent.complete", indicator.type)
        assertEquals("completed", indicator.status)
        assertEquals("done", indicator.summary)
        assertTrue(indicator.isComplete)
    }

    @Test
    fun testSessionMismatch_isIgnored() {
        val initialMessage =
            ChatMessage(
                role = MessageRole.TOOL,
                content = "{}",
                toolName = "web_search",
                toolStatus = ToolStatus.RUNNING,
            )
        val state =
            ChatUiState(
                messages = listOf(initialMessage),
                currentSessionId = "session-1",
            )
        val event =
            WsEvent.ToolProgress(
                name = "web_search",
                preview = "fetching google...",
                sessionId = "session-different",
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.messages.size)
        val updatedMessage = result.state.messages.first()
        assertEquals(ToolStatus.RUNNING, updatedMessage.toolStatus)
        assertEquals(null, updatedMessage.progressPreview)
    }

    @Test
    fun testSubagentEvent_accumulatesLiveTranscriptLogs() {
        val state =
            ChatUiState(
                currentSessionId = "session-1",
                subagentIndicators = emptyList(),
            )
        val startEvent =
            WsEvent.SubagentEvent(
                type = "subagent.start",
                sessionId = "session-1",
                payload =
                    mapOf(
                        "subagent_id" to "sub-1",
                        "goal" to "research api",
                        "text" to "Initializing subagent",
                    ),
            )

        val res1 =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = startEvent,
                currentSessionId = "session-1",
            )

        val progressEvent =
            WsEvent.SubagentEvent(
                type = "subagent.progress",
                sessionId = "session-1",
                payload =
                    mapOf(
                        "subagent_id" to "sub-1",
                        "text" to "Fetching documentation",
                    ),
            )

        val res2 =
            ChatWsEventReducer.reduce(
                state = res1.state,
                streamingState = StreamingState(),
                event = progressEvent,
                currentSessionId = "session-1",
            )

        val indicator = res2.state.subagentIndicators.first()
        assertEquals(2, indicator.logs.size)
        assertEquals("Initializing subagent", indicator.logs[0].text)
        assertEquals("Fetching documentation", indicator.logs[1].text)
        assertTrue(indicator.isRunning)
    }

    @Test
    fun testMessageStart_doesNotSeedReasoningFromPreviousMessage() {
        val previousReasoning = "old reasoning trace"
        val state =
            ChatUiState(
                currentSessionId = "session-1",
            )
        val staleStreaming =
            StreamingState(
                streamingMessage =
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "prev",
                        reasoningText = previousReasoning,
                        isStreaming = true,
                    ),
                isReasoning = true,
                reasoningText = previousReasoning,
            )
        val startEvent = WsEvent.MessageStart(sessionId = "session-1")

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = staleStreaming,
                event = startEvent,
                currentSessionId = "session-1",
            )

        // Issue #755: the new message must NOT inherit the previous message's
        // reasoning — the bubble starts blank until real reasoning deltas arrive.
        assertEquals("", result.streamingState.streamingMessage?.reasoningText)
        assertFalse(result.streamingState.isReasoning)
        assertEquals("", result.streamingState.reasoningText)
    }

    @Test
    fun testMessageComplete_withoutNewReasoning_persistsEmptyReasoning() {
        // Simulates: message A reasoned, then a fast reply B with no reasoning.
        val previousReasoning = "old reasoning trace"
        val state =
            ChatUiState(
                currentSessionId = "session-1",
            )
        val staleStreaming =
            StreamingState(
                streamingMessage =
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "prev",
                        reasoningText = previousReasoning,
                        isStreaming = true,
                    ),
                isReasoning = true,
                reasoningText = previousReasoning,
            )
        val startEvent = WsEvent.MessageStart(sessionId = "session-1")

        val startResult =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = staleStreaming,
                event = startEvent,
                currentSessionId = "session-1",
            )
        val completeResult =
            ChatWsEventReducer.reduce(
                state = startResult.state,
                streamingState = startResult.streamingState,
                event = WsEvent.MessageComplete(text = "fast reply", sessionId = "session-1"),
                currentSessionId = "session-1",
            )

        // Issue #755: no reasoning tokens arrived for message B, so the
        // finalized message must carry EMPTY reasoning — never the stale trace.
        val persisted = completeResult.state.messages.last()
        assertEquals("fast reply", persisted.content)
        assertEquals("", persisted.reasoningText)
        val persistEffect = completeResult.effects.filterIsInstance<ReducerEffect.PersistMessage>().firstOrNull()
        assertTrue(persistEffect != null)
        assertEquals("", persistEffect?.message?.reasoningText)
    }

    @Test
    fun testMessageStart_prunesCompletedSubagents() {
        val completedSubagent =
            SubagentIndicator(
                type = "subagent.complete",
                goal = "finished task",
                subagentId = "sub-1",
                status = "completed",
            )
        val state =
            ChatUiState(
                currentSessionId = "session-1",
                subagentIndicators = listOf(completedSubagent),
            )
        val startEvent = WsEvent.MessageStart(sessionId = "session-1")

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = startEvent,
                currentSessionId = "session-1",
            )

        assertTrue(result.state.subagentIndicators.isEmpty())
    }

    @Test
    fun testToolStart_extractsAgentTodos() {
        val state = ChatUiState(currentSessionId = "session-1")
        val todoEvent =
            WsEvent.ToolStart(
                name = "todo",
                sessionId = "session-1",
                data =
                    mapOf(
                        "todos" to
                            listOf(
                                mapOf("id" to "1", "content" to "Inspect repo", "status" to "completed"),
                                mapOf("id" to "2", "content" to "Implement feature", "status" to "in_progress"),
                            ),
                    ),
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = todoEvent,
                currentSessionId = "session-1",
            )

        assertEquals(2, result.state.todos.size)
        assertEquals("Inspect repo", result.state.todos[0].content)
        assertTrue(result.state.todos[0].isCompleted)
        assertEquals("Implement feature", result.state.todos[1].content)
        assertTrue(result.state.todos[1].isInProgress)
    }

    @Test
    fun testHydrateTodosFromMessages_parsesStoredToolMessage() {
        val todoMessage =
            ChatMessage(
                role = MessageRole.TOOL,
                toolName = "todo",
                content = """{"todos":[{"id":"a","content":"Write tests","status":"completed"}]}""",
            )
        val state = ChatUiState(messages = listOf(todoMessage), currentSessionId = "session-1")
        val event = WsEvent.MessageToken(token = "hello", sessionId = "session-1")

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.todos.size)
        assertEquals("Write tests", result.state.todos[0].content)
        assertTrue(result.state.todos[0].isCompleted)
    }

    @Test
    fun testReviewSummary_addsSystemMessage() {
        val state = ChatUiState(currentSessionId = "session-1")
        val event =
            WsEvent.ReviewSummary(
                text = "💾 Self-improvement review: Skill 'android-ci' patched",
                sessionId = "session-1",
            )

        val result =
            ChatWsEventReducer.reduce(
                state = state,
                streamingState = StreamingState(),
                event = event,
                currentSessionId = "session-1",
            )

        assertEquals(1, result.state.messages.size)
        val msg = result.state.messages.first()
        assertEquals(MessageRole.SYSTEM, msg.role)
        assertEquals("💾 Self-improvement review: Skill 'android-ci' patched", msg.content)
    }

    // ── Issue #771: reasoning survives a tool call (regression) ────────────
    //
    // Real gateway capture for `run echo hi` (reasoning model):
    //   message.start
    //   reasoning.delta × N          ← thinking streams BEFORE the tool
    //   tool.generating / tool.start / tool.complete  (terminal)
    //   message.delta × N            ← final answer streams AFTER the tool
    //   reasoning.available (full text)
    //   message.complete (text + reasoning payload field)
    //
    // Previously the reducer returned a FRESH StreamingState() at tool.start,
    // wiping the streamed reasoning — the reasoning bubble vanished mid-turn
    // and the finalized answer had no reasoning card.

    @Test
    fun testToolCallTurn_reasoningSurvivesToolStart_andFinalizesWithCard() {
        val state = ChatUiState(currentSessionId = "session-1")

        // 1. message.start
        val start = ChatWsEventReducer.reduce(state, StreamingState(), WsEvent.MessageStart("session-1"), "session-1")

        // 2. reasoning.delta — thinking streams before the tool
        val reasoningTokens = listOf("The user just said", " \"run echo hi\".", " Let me run it.")
        var reasoningState = start.streamingState
        for (token in reasoningTokens) {
            reasoningState =
                ChatWsEventReducer.reduce(
                    state,
                    reasoningState,
                    WsEvent.ReasoningDelta(token, "session-1"),
                    "session-1",
                ).streamingState
        }
        assertEquals(true, reasoningState.isReasoning)
        assertEquals("The user just said \"run echo hi\". Let me run it.", reasoningState.reasoningText)
        // Streaming message alive (reasoning copy lands on it via the
        // controller's flush — the reducer owns the shared text).
        assertEquals(true, reasoningState.streamingMessage?.isStreaming)

        // 3. tool.start — must NOT wipe the streaming message or its reasoning
        var result =
            ChatWsEventReducer.reduce(
                state,
                reasoningState,
                WsEvent.ToolStart("terminal", mapOf("args_text" to "echo hi"), "session-1"),
                "session-1",
            )
        assertEquals(1, result.state.messages.size)
        assertEquals(MessageRole.TOOL, result.state.messages.first().role)
        assertEquals("terminal", result.state.messages.first().toolName)
        // Issue #771: streaming message + reasoning survive the tool call
        assertEquals(true, result.streamingState.streamingMessage?.isStreaming)
        assertEquals("The user just said \"run echo hi\". Let me run it.", result.streamingState.reasoningText)

        // 4. tool.complete — tool bubble completes; stream untouched
        result =
            ChatWsEventReducer.reduce(
                result.state,
                result.streamingState,
                WsEvent.ToolComplete("terminal", mapOf("output" to "hi"), "session-1"),
                "session-1",
            )
        assertEquals(ToolStatus.COMPLETED, result.state.messages.first().toolStatus)

        // 5. reasoning.available — authoritative full-trace fill
        val fullReasoning = "The user just said \"run echo hi\". That's a simple terminal command. Let me just run it."
        result =
            ChatWsEventReducer.reduce(
                result.state,
                result.streamingState,
                WsEvent.ReasoningAvailable("session-1", fullReasoning),
                "session-1",
            )
        assertEquals(fullReasoning, result.streamingState.reasoningText)
        assertEquals(fullReasoning, result.streamingState.streamingMessage?.reasoningText)

        // 6. message.complete — finalized bubble keeps the reasoning card
        result =
            ChatWsEventReducer.reduce(
                result.state,
                result.streamingState,
                WsEvent.MessageComplete(
                    text = "done!! `echo hi` ran clean",
                    sessionId = "session-1",
                    reasoning = fullReasoning,
                ),
                "session-1",
            )
        val assistant = result.state.messages.last()
        assertEquals(MessageRole.ASSISTANT, assistant.role)
        assertEquals("done!! `echo hi` ran clean", assistant.content)
        assertEquals(fullReasoning, assistant.reasoningText)
        assertFalse(assistant.isStreaming)
        // Stream tail cleared after finalize — no ghost duplicate bubble
        assertEquals(null, result.streamingState.streamingMessage)
        // Exactly one assistant message — no orphan duplication
        assertEquals(1, result.state.messages.count { it.role == MessageRole.ASSISTANT })
    }

    @Test
    fun testMessageComplete_reasoningPayloadField_isAuthoritativeFallback() {
        // Even if reasoning.delta events were entirely lost (throttled/wipe),
        // the message.complete payload carries the full trace (real gateway
        // capture: payload keys text/usage/status/reasoning).
        val state = ChatUiState(currentSessionId = "session-1")
        val start = ChatWsEventReducer.reduce(state, StreamingState(), WsEvent.MessageStart("session-1"), "session-1")

        val result =
            ChatWsEventReducer.reduce(
                start.state,
                start.streamingState,
                WsEvent.MessageComplete(text = "answer", sessionId = "session-1", reasoning = "payload reasoning"),
                "session-1",
            )

        assertEquals("payload reasoning", result.state.messages.last().reasoningText)
    }

    @Test
    fun testToolStart_withInterimText_sealsOrphan_butClearsStreamTail() {
        // When the agent streams interim text BEFORE calling the tool, the
        // orphan is sealed into messages (desktop parity), and the streaming
        // tail is cleared — but the reasoning text stays in shared state so
        // the post-tool answer can still pick it up.
        val state = ChatUiState(currentSessionId = "session-1")
        val streaming =
            StreamingState(
                streamingMessage =
                    ChatMessage(
                        role = MessageRole.ASSISTANT,
                        content = "Let me run that for you.",
                        reasoningText = "thinking...",
                        isStreaming = true,
                    ),
                isReasoning = true,
                reasoningText = "thinking...",
            )

        val result =
            ChatWsEventReducer.reduce(
                state,
                streaming,
                WsEvent.ToolStart("terminal", mapOf("args_text" to "echo hi"), "session-1"),
                "session-1",
            )

        assertEquals(2, result.state.messages.size) // sealed orphan + tool
        assertEquals("Let me run that for you.", result.state.messages[0].content)
        assertFalse(result.state.messages[0].isStreaming)
        assertEquals("thinking...", result.state.messages[0].reasoningText)
        assertEquals(null, result.streamingState.streamingMessage)
        // Reasoning preserved for the post-tool message
        assertEquals("thinking...", result.streamingState.reasoningText)
    }
}
