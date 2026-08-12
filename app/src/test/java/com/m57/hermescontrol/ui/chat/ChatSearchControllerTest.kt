package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchControllerTest {
    private val controller = ChatSearchController()

    private fun message(
        role: MessageRole,
        content: String,
        reasoningText: String = "",
    ): ChatMessage = ChatMessage(role = role, content = content, reasoningText = reasoningText)

    @Test
    fun `matches user and assistant visible text`() {
        val messages =
            listOf(
                message(MessageRole.USER, "deploy the API"),
                message(MessageRole.ASSISTANT, "Let's deploy it now"),
                message(MessageRole.USER, "unrelated"),
            )

        assertEquals(listOf(0, 1), controller.findMatches(messages, "deploy"))
    }

    @Test
    fun `excludes tool rows even when the payload contains the query`() {
        val messages =
            listOf(
                message(MessageRole.USER, "check the logs"),
                message(MessageRole.TOOL, "{\"result\": \"check the logs: all green\"}"),
                message(MessageRole.ASSISTANT, "all good"),
            )

        assertEquals(listOf(0), controller.findMatches(messages, "check the logs"))
    }

    @Test
    fun `excludes system events`() {
        val messages =
            listOf(
                message(MessageRole.USER, "approve the terminal command"),
                message(MessageRole.SYSTEM, "Approval requested: approve the terminal command"),
            )

        assertEquals(listOf(0), controller.findMatches(messages, "approve"))
    }

    @Test
    fun `excludes reasoning text that never made it to visible content`() {
        val messages =
            listOf(
                message(
                    role = MessageRole.ASSISTANT,
                    content = "Here is the answer",
                    reasoningText = "thinking about the deployment steps",
                ),
            )

        // Only the visible content is searchable — reasoning is invisible.
        assertEquals(emptyList<Int>(), controller.findMatches(messages, "deployment"))
        assertEquals(listOf(0), controller.findMatches(messages, "answer"))
    }

    @Test
    fun `matching is case insensitive`() {
        val messages = listOf(message(MessageRole.USER, "Deploy the API now"))

        assertEquals(listOf(0), controller.findMatches(messages, "dEpLoY"))
    }

    @Test
    fun `blank query returns no matches`() {
        val messages = listOf(message(MessageRole.USER, "deploy"))

        assertEquals(emptyList<Int>(), controller.findMatches(messages, " "))
    }

    @Test
    fun `navigate wraps forward and backward`() {
        assertEquals(1, controller.navigate(currentIndex = 0, matchCount = 3, direction = 1))
        assertEquals(0, controller.navigate(currentIndex = 2, matchCount = 3, direction = 1))
        assertEquals(2, controller.navigate(currentIndex = 0, matchCount = 3, direction = -1))
        assertEquals(1, controller.navigate(currentIndex = 2, matchCount = 3, direction = -1))
        assertEquals(-1, controller.navigate(currentIndex = -1, matchCount = 0, direction = 1))
    }
}
