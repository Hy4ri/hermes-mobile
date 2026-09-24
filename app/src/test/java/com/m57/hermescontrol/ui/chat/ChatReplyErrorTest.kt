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
        val state = ChatUiState(isAgentTyping = true, isThinking = true, streamingMessage = partial)
        val streaming = StreamingState(streamingMessage = partial, isThinking = true)

        val event =
            WsEvent.MessageComplete(
                text = "Partial reply",
                sessionId = null,
                rawPayload = mapOf("status" to "error", "error" to "Provider failed", "partial" to true),
            )
        val result = ChatWsEventReducer.reduce(state, streaming, event)

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
        val state = ChatUiState(replyFailure = failure)
        val done = ChatWsEventReducer.reduce(state, StreamingState(), WsEvent.MessageDone(null))
        assertEquals(failure, done.state.replyFailure)
        val start = ChatWsEventReducer.reduce(state, StreamingState(), WsEvent.MessageStart(null))
        assertNull(start.state.replyFailure)
    }
}
