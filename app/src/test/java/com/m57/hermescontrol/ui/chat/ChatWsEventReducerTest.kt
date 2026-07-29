package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import org.junit.Assert.assertEquals
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
}
