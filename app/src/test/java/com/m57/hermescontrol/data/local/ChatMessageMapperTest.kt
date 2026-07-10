package com.m57.hermescontrol.data.local

import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageMapperTest {
    @Test
    fun entityToUiModelCarriesReasoningText() {
        val entity =
            ChatMessageEntity(
                id = "msg-1",
                sessionId = "session-a",
                role = "assistant",
                content = "Answer",
                reasoningText = "Let me think step by step",
                timestamp = 1000L,
            )

        val ui = entity.toUiModel()

        assertEquals("Let me think step by step", ui.reasoningText)
        assertEquals("Answer", ui.content)
    }

    @Test
    fun uiModelToEntityCarriesReasoningText() {
        val ui =
            ChatMessage(
                id = "msg-2",
                role = MessageRole.ASSISTANT,
                content = "Answer",
                reasoningText = "Chain of thought",
                timestamp = 2000L,
            )

        val entity = ui.toEntity("session-b")

        assertEquals("Chain of thought", entity.reasoningText)
        assertEquals("session-b", entity.sessionId)
    }

    @Test
    fun roundTripPreservesReasoningText() {
        val ui =
            ChatMessage(
                id = "msg-3",
                role = MessageRole.ASSISTANT,
                content = "Answer",
                reasoningText = "r",
                timestamp = 3000L,
            )

        val roundTripped = ui.toEntity("s").toUiModel()

        assertEquals("r", roundTripped.reasoningText)
    }
}
