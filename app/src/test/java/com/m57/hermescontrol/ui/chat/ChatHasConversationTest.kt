package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHasConversationTest {
    private val marker = ChatMessage(role = MessageRole.SYSTEM, content = "Session created")

    @Test
    fun emptyChat_hasNoConversation() {
        assertFalse(ChatUiState().hasConversation)
    }

    @Test
    fun sessionStartMarkerAlone_isNotAConversation() {
        assertFalse(ChatUiState(messages = listOf(marker)).hasConversation)
    }

    @Test
    fun userMessage_startsConversation() {
        val user = ChatMessage(role = MessageRole.USER, content = "hi")
        assertTrue(ChatUiState(messages = listOf(marker, user)).hasConversation)
    }
}
