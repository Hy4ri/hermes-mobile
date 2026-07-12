package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.model.SessionMessagesResponse
import com.m57.hermescontrol.data.remote.OkHttpProvider
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryMapperTest {
    @Test
    fun `joins tool metadata and keeps reasoning across reload`() {
        val toolCalls =
            JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("call-1"),
                            "function" to
                                JsonObject(
                                    mapOf(
                                        "name" to JsonPrimitive("shell_command"),
                                        "arguments" to JsonPrimitive("{\"command\":\"pwd\"}"),
                                    ),
                                ),
                        ),
                    ),
                ),
            )
        val mapped =
            SessionHistoryMapper.map(
                sessionId = "session-a",
                messages =
                    listOf(
                        SessionMessage(
                            role = "assistant",
                            toolCalls = toolCalls,
                            reasoning = JsonPrimitive("I should inspect it."),
                        ),
                        SessionMessage(
                            id = 42,
                            role = "tool",
                            content = JsonPrimitive("{\"stdout\":\"/workspace\",\"exit_code\":0}"),
                            toolCallId = "call-1",
                            timestamp = JsonPrimitive(1_700_000_000.0),
                        ),
                    ),
            )

        assertEquals(2, mapped.size)
        assertEquals("I should inspect it.", mapped.first().reasoningText)
        assertEquals("shell_command", mapped.last().toolName)
        assertEquals(ToolStatus.COMPLETED, mapped.last().toolStatus)
        assertTrue(mapped.last().content.contains("/workspace"))
        assertFalse(mapped.last().content.contains("\"name\":\"Tool\""))
        assertEquals(1_700_000_000_000L, mapped.last().timestamp)
    }

    @Test
    fun `decodes and flattens structured tool content from real history shape`() {
        val response =
            OkHttpProvider.json.decodeFromString<SessionMessagesResponse>(
                """{"messages":[{"id":78,"role":"tool","tool_name":"view_image","content":""" +
                    """[{"type":"text","text":"Image inspected successfully"}]}]}""",
            )

        val mapped = SessionHistoryMapper.map("session-structured", response.messages)

        assertEquals(1, mapped.size)
        assertEquals("view_image", mapped.single().toolName)
        assertTrue(mapped.single().content.contains("Image inspected successfully"))
    }
}
