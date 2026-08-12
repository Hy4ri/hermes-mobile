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
    fun `matches user and assistant visible text with content offsets`() {
        val messages =
            listOf(
                message(MessageRole.USER, "deploy the API"),
                message(MessageRole.ASSISTANT, "Let's deploy it now"),
                message(MessageRole.USER, "unrelated"),
            )

        assertEquals(
            listOf(SearchMatch(0, 0), SearchMatch(1, 6)),
            controller.findMatches(messages, "deploy"),
        )
    }

    @Test
    fun `excludes tool rows even when the payload contains the query`() {
        val messages =
            listOf(
                message(MessageRole.USER, "check the logs"),
                message(MessageRole.TOOL, "{\"result\": \"check the logs: all green\"}"),
                message(MessageRole.ASSISTANT, "all good"),
            )

        assertEquals(listOf(SearchMatch(0, 0)), controller.findMatches(messages, "check the logs"))
    }

    @Test
    fun `excludes system events`() {
        val messages =
            listOf(
                message(MessageRole.USER, "approve the terminal command"),
                message(MessageRole.SYSTEM, "Approval requested: approve the terminal command"),
            )

        assertEquals(listOf(SearchMatch(0, 0)), controller.findMatches(messages, "approve"))
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
        assertEquals(emptyList<SearchMatch>(), controller.findMatches(messages, "deployment"))
        assertEquals(listOf(SearchMatch(0, 12)), controller.findMatches(messages, "answer"))
    }

    @Test
    fun `matching is case insensitive`() {
        val messages = listOf(message(MessageRole.USER, "Deploy the API now"))

        assertEquals(listOf(SearchMatch(0, 0)), controller.findMatches(messages, "dEpLoY"))
    }

    @Test
    fun `repeats the message index once per occurrence with each offset`() {
        val messages = listOf(message(MessageRole.USER, "deploy deploy and deploy"))

        // 3 hits in one message = 3 entries with their own character offsets.
        assertEquals(
            listOf(SearchMatch(0, 0), SearchMatch(0, 7), SearchMatch(0, 18)),
            controller.findMatches(messages, "deploy"),
        )
    }

    @Test
    fun `counts occurrences across multiple messages`() {
        val messages =
            listOf(
                message(MessageRole.USER, "deploy the api"),
                message(MessageRole.ASSISTANT, "deploy deploy"),
            )

        assertEquals(
            listOf(SearchMatch(0, 0), SearchMatch(1, 0), SearchMatch(1, 7)),
            controller.findMatches(messages, "deploy"),
        )
    }

    @Test
    fun `regex special characters in the query are treated literally`() {
        val messages = listOf(message(MessageRole.USER, "a+b c a+b"))

        assertEquals(
            listOf(SearchMatch(0, 0), SearchMatch(0, 6)),
            controller.findMatches(messages, "a+b"),
        )
        assertEquals(emptyList<SearchMatch>(), controller.findMatches(messages, "a."))
    }

    @Test
    fun `blank query returns no matches`() {
        val messages = listOf(message(MessageRole.USER, "deploy"))

        assertEquals(emptyList<SearchMatch>(), controller.findMatches(messages, " "))
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
