package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReplyErrorTest {
    @Test
    fun failedCompletionKeepsPartialReplyAndStopsThinking() {
        val partial =
            ChatMessage(id = "partial", role = MessageRole.ASSISTANT, content = "Partial reply", isStreaming = true)
        val state =
            ChatUiState(
                currentSessionId = "runtime",
                isAgentTyping = true,
                isThinking = true,
                streamingMessage = partial,
            )
        val streaming = StreamingState(streamingMessage = partial, isThinking = true)

        val event =
            WsEvent.MessageComplete(
                text = "Partial reply",
                sessionId = "runtime",
                completionId = "completion-failed-1",
                rawPayload = mapOf("status" to "error", "error" to "Provider failed", "partial" to true),
            )
        val result = ChatWsEventReducer.reduce(state, streaming, event, "runtime")

        assertFalse(result.state.isThinking)
        assertEquals(
            "Partial reply",
            result.state.messages
                .single { it.id == "partial" }
                .content,
        )
        assertNotNull(result.state.replyFailure)
        assertFalse(result.state.isAgentTyping)
        assertNull(result.streamingState.streamingMessage)
        assertEquals(
            "completion-failed-1",
            result.state.messages
                .single { it.id == "partial" }
                .completionId,
        )
        assertTrue(result.effects.none { it is ReducerEffect.PersistMessage })
    }

    @Test
    fun terminalErrorIsNeverPersistedAsAssistantProse() {
        val event =
            WsEvent.MessageComplete(
                text = "Provider refused API_KEY=private-value",
                sessionId = "runtime",
                rawPayload = mapOf("status" to "error"),
            )
        val result = ChatWsEventReducer.reduce(ChatUiState(), StreamingState(), event, "runtime")
        assertNotNull(result.state.replyFailure)
        assertFalse(
            result.state.replyFailure!!
                .details
                .contains("private-value"),
        )
        assertTrue(result.state.messages.isEmpty())
        assertTrue(result.effects.none { it is ReducerEffect.PersistMessage })
    }

    @Test
    fun errorWithoutPartialFlagStillKeepsAlreadyStreamedProse() {
        val streaming =
            StreamingState(streamingMessage = ChatMessage(role = MessageRole.ASSISTANT, content = "Visible"))
        val event = WsEvent.MessageComplete("Error!", null, rawPayload = mapOf("status" to "error"))
        val result = ChatWsEventReducer.reduce(ChatUiState(), streaming, event)
        assertEquals(
            "Visible",
            result.state.messages
                .single()
                .content,
        )
        assertNotNull(result.state.replyFailure)
    }

    @Test
    fun otherSessionCannotShowFailureOrChangeStreaming() {
        val state = ChatUiState(isAgentTyping = true)
        val streaming = StreamingState(isThinking = true)
        val event = WsEvent.MessageComplete("Error", "other", rawPayload = mapOf("status" to "error"))
        val result = ChatWsEventReducer.reduce(state, streaming, event, "current")
        assertEquals(state, result.state)
        assertEquals(streaming, result.streamingState)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun missingPartialTextPreservesAlreadyVisibleOutput() {
        for (text in listOf("", "   ")) {
            val streaming =
                StreamingState(
                    streamingMessage =
                        ChatMessage(
                            id = "visible",
                            role = MessageRole.ASSISTANT,
                            content = "Visible output",
                        ),
                )
            val event =
                WsEvent.MessageComplete(
                    text,
                    null,
                    rawPayload = mapOf("status" to "error", "partial" to true),
                )
            val result = ChatWsEventReducer.reduce(ChatUiState(), streaming, event)
            assertEquals(
                "Visible output",
                result.state.messages
                    .single()
                    .content,
            )
        }
    }

    @Test
    fun partialConversationIsNeverUsedAsDiagnosticFallback() {
        val event =
            WsEvent.MessageComplete(
                "Private conversational content",
                null,
                rawPayload = mapOf("status" to "error", "partial" to true),
            )
        val result = ChatWsEventReducer.reduce(ChatUiState(), StreamingState(), event)
        assertEquals(
            "Private conversational content",
            result.state.messages
                .single()
                .content,
        )
        assertFalse(
            result.state.replyFailure!!
                .details
                .contains("Private conversational content"),
        )
        assertTrue(
            result.state.replyFailure!!
                .details
                .isNotBlank(),
        )
    }

    @Test
    fun normalReplyAndGatewayErrorAreNotTerminalReplyFailures() {
        for (event in listOf(WsEvent.MessageComplete("Success", null), WsEvent.GatewayError("Connection failed"))) {
            val result = ChatWsEventReducer.reduce(ChatUiState(), StreamingState(), event)
            assertNull(result.state.replyFailure)
        }
    }

    @Test
    fun nextTurnClearsFailureButDoneDoesNot() {
        val failure = ReplyFailure("Failed")
        val failedPartial =
            ChatMessage(id = "failed-partial", role = MessageRole.ASSISTANT, content = "Partial")
        val state =
            ChatUiState(
                messages = listOf(ChatMessage(id = "normal", role = MessageRole.USER, content = "Keep"), failedPartial),
                replyFailure = failure,
                replyFailureProjection = ReplyFailureProjection("failed-partial", failure.id),
            )
        val done = ChatWsEventReducer.reduce(state, StreamingState(), WsEvent.MessageDone(null))
        assertEquals(failure, done.state.replyFailure)
        val start = ChatWsEventReducer.reduce(state, StreamingState(), WsEvent.MessageStart(null))
        assertNull(start.state.replyFailure)
        assertNull(start.state.replyFailureProjection)
        assertEquals(listOf("normal"), start.state.messages.map { it.id })
    }

    @Test
    fun retainedFailureWithEmptyAssistantNeverInheritsUnrelatedStream() {
        val normal = ChatMessage(id = "normal", role = MessageRole.USER, content = "Keep")
        val stale =
            ChatMessage(
                id = "unrelated-stream",
                role = MessageRole.ASSISTANT,
                content = "Stale text",
                reasoningText = "Stale reasoning",
                isStreaming = true,
            )
        val state =
            ChatUiState(
                messages = listOf(normal),
                isAgentTyping = true,
                isThinking = true,
                thinkingText = "Stale thinking",
                streamingMessage = stale,
            )

        val result =
            ChatWsEventReducer.reduceRetainedReplyFailure(
                state = state,
                inflight = mapOf("assistant" to "", "status" to "error", "error" to "Provider failed"),
                currentSessionId = "runtime",
            )

        assertEquals(listOf(normal), result.state.messages)
        assertFalse(result.state.isAgentTyping)
        assertFalse(result.state.isThinking)
        assertEquals("", result.state.thinkingText)
        assertNull(result.state.streamingMessage)
        assertEquals(StreamingState(), result.streamingState)
        assertNotNull(result.state.replyFailure)
        assertNull(result.state.replyFailureProjection?.messageId)
    }

    @Test
    fun duplicateRetainedFailureKeepsOneStableProjection() {
        val normal = ChatMessage(id = "normal", role = MessageRole.USER, content = "Keep")
        val inflight =
            mapOf("assistant" to "Retained partial", "status" to "error", "error" to "Provider failed")

        val first =
            ChatWsEventReducer.reduceRetainedReplyFailure(
                ChatUiState(messages = listOf(normal)),
                inflight,
                "runtime",
            )
        val second = ChatWsEventReducer.reduceRetainedReplyFailure(first.state, inflight, "runtime")

        assertEquals(2, second.state.messages.size)
        assertEquals(listOf("Keep", "Retained partial"), second.state.messages.map { it.content })
        assertEquals(first.state.replyFailureProjection, second.state.replyFailureProjection)
        assertEquals(first.state.replyFailure?.id, second.state.replyFailure?.id)
    }

    @Test
    fun duplicateLiveFailureKeepsOneStableProjection() {
        val normal = ChatMessage(id = "normal", role = MessageRole.USER, content = "Keep")
        val partial =
            ChatMessage(id = "live-partial", role = MessageRole.ASSISTANT, content = "Partial", isStreaming = true)
        val event =
            WsEvent.MessageComplete(
                text = "Partial",
                sessionId = "runtime",
                completionId = "completion-1",
                rawPayload = mapOf("status" to "error", "error" to "Provider failed", "partial" to true),
            )

        val first =
            ChatWsEventReducer.reduce(
                ChatUiState(messages = listOf(normal), currentSessionId = "runtime"),
                StreamingState(streamingMessage = partial),
                event,
                "runtime",
            )
        val second = ChatWsEventReducer.reduce(first.state, first.streamingState, event, "runtime")

        assertEquals(listOf("normal", "live-partial"), second.state.messages.map { it.id })
        assertEquals(first.state.replyFailureProjection, second.state.replyFailureProjection)
        assertEquals(first.state.replyFailure?.id, second.state.replyFailure?.id)
    }

    @Test
    fun liveFailureThenResumeUpdatesSameProjection() {
        val partial =
            ChatMessage(
                id = "live-partial",
                role = MessageRole.ASSISTANT,
                content = "Live partial",
                isStreaming = true,
            )
        val live =
            ChatWsEventReducer.reduce(
                ChatUiState(currentSessionId = "stored"),
                StreamingState(streamingMessage = partial),
                WsEvent.MessageComplete(
                    text = "Live partial",
                    sessionId = "runtime",
                    completionId = "completion-1",
                    rawPayload = mapOf("status" to "error", "error" to "Provider failed", "partial" to true),
                ),
                "runtime",
            )

        val resumed =
            ChatWsEventReducer.reduceRetainedReplyFailure(
                live.state,
                mapOf("assistant" to "Retained partial", "status" to "error", "error" to "Provider failed"),
                "runtime",
            )

        assertEquals(1, resumed.state.messages.size)
        assertEquals(
            "live-partial",
            resumed.state.messages
                .single()
                .id,
        )
        assertEquals(
            "Retained partial",
            resumed.state.messages
                .single()
                .content,
        )
        assertEquals(
            "completion-1",
            resumed.state.messages
                .single()
                .completionId,
        )
        assertEquals(live.state.replyFailureProjection, resumed.state.replyFailureProjection)
        assertEquals(live.state.replyFailure?.id, resumed.state.replyFailure?.id)
    }
}
