package com.m57.hermescontrol.data.local

import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageMapperTest {
    @Test
    fun entityToUiModel_mapsAllPropertiesCorrectly() {
        val entity =
            ChatMessageEntity(
                id = "msg-1",
                sessionId = "session-a",
                role = "TOOL",
                content = "Tool output",
                reasoningText = "Thinking",
                timestamp = 1000L,
                toolName = "search",
                toolStatus = "RUNNING",
                isStreaming = true,
            )

        val ui = entity.toUiModel()

        assertEquals("msg-1", ui.id)
        assertEquals(MessageRole.TOOL, ui.role)
        assertEquals("Tool output", ui.content)
        assertEquals("Thinking", ui.reasoningText)
        assertEquals(1000L, ui.timestamp)
        assertEquals("search", ui.toolName)
        assertEquals(ToolStatus.RUNNING, ui.toolStatus)
        assertTrue(ui.isStreaming)
    }

    @Test
    fun uiModelToEntity_mapsAllPropertiesCorrectly() {
        val ui =
            ChatMessage(
                id = "msg-2",
                role = MessageRole.SYSTEM,
                content = "System prompt",
                reasoningText = "",
                timestamp = 2000L,
                toolName = null,
                toolStatus = null,
                isStreaming = false,
            )

        val entity = ui.toEntity("session-b")

        assertEquals("msg-2", entity.id)
        assertEquals("session-b", entity.sessionId)
        assertEquals("SYSTEM", entity.role)
        assertEquals("System prompt", entity.content)
        assertEquals("", entity.reasoningText)
        assertEquals(2000L, entity.timestamp)
        assertNull(entity.toolName)
        assertNull(entity.toolStatus)
        assertFalse(entity.isStreaming)
    }

    @Test
    fun entityToUiModel_mapsRolesCorrectly() {
        assertEquals(MessageRole.USER, createEntityWithRole("USER").toUiModel().role)
        assertEquals(MessageRole.ASSISTANT, createEntityWithRole("ASSISTANT").toUiModel().role)
        assertEquals(MessageRole.SYSTEM, createEntityWithRole("SYSTEM").toUiModel().role)
        assertEquals(MessageRole.TOOL, createEntityWithRole("TOOL").toUiModel().role)
    }

    @Test
    fun entityToUiModel_mapsUnknownRoleToAssistant() {
        assertEquals(MessageRole.ASSISTANT, createEntityWithRole("UNKNOWN").toUiModel().role)
        assertEquals(MessageRole.ASSISTANT, createEntityWithRole("").toUiModel().role)
    }

    @Test
    fun entityToUiModel_mapsToolStatusCorrectly() {
        assertEquals(ToolStatus.RUNNING, createEntityWithToolStatus("RUNNING").toUiModel().toolStatus)
        assertEquals(ToolStatus.COMPLETED, createEntityWithToolStatus("COMPLETED").toUiModel().toolStatus)
        assertEquals(ToolStatus.FAILED, createEntityWithToolStatus("FAILED").toUiModel().toolStatus)
    }

    @Test
    fun entityToUiModel_mapsUnknownToolStatusToNull() {
        assertNull(createEntityWithToolStatus("UNKNOWN").toUiModel().toolStatus)
        assertNull(createEntityWithToolStatus("").toUiModel().toolStatus)
        assertNull(createEntityWithToolStatus(null).toUiModel().toolStatus)
    }

    private fun createEntityWithRole(role: String): ChatMessageEntity {
        return ChatMessageEntity(
            id = "id",
            sessionId = "session",
            role = role,
            content = "content",
            timestamp = 0L,
        )
    }

    private fun createEntityWithToolStatus(status: String?): ChatMessageEntity {
        return ChatMessageEntity(
            id = "id",
            sessionId = "session",
            role = "TOOL",
            content = "content",
            timestamp = 0L,
            toolStatus = status,
        )
    }

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
