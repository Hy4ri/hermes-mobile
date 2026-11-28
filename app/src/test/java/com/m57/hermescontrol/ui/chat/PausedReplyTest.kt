package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PausedReplyTest {
    @Test
    fun pausedCompleteDrawsContinuingNoticeWithoutPersisting() {
        val event =
            WsEvent.MessageComplete(
                text = "Operation interrupted.",
                sessionId = "rt",
                paused = true,
            )
        val result = ChatWsEventReducer.reduce(ChatUiState(currentSessionId = "s1"), StreamingState(), event, "rt")
        assertEquals(
            GatewayNotice.CONTINUING,
            result.state.messages
                .last()
                .content,
        )
        assertTrue(result.effects.none { it is ReducerEffect.PersistMessage })
    }

    @Test
    fun unpausedInterruptKeepsStoppedNotice() {
        val event = WsEvent.MessageComplete(text = "Operation interrupted.", sessionId = "rt")
        val result = ChatWsEventReducer.reduce(ChatUiState(currentSessionId = "s1"), StreamingState(), event, "rt")
        assertEquals(
            "Operation interrupted.",
            result.state.messages
                .last()
                .content,
        )
    }

    private fun notice() = ChatMessage(role = MessageRole.SYSTEM, content = "Operation interrupted.")

    private fun hidden() = ChatMessage(role = MessageRole.USER, content = "continue", displayKind = "hidden")

    @Test
    fun storedStopNoticeDropsWhenHiddenPromptAndOutputFollow() {
        val rows = mutableListOf(notice(), hidden(), ChatMessage(role = MessageRole.ASSISTANT, content = "done"))
        rows.dropContinuedStopNotices()
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), rows.map { it.role })
    }

    @Test
    fun storedStopNoticeStaysWhenUserWritesNext() {
        val rows =
            mutableListOf(
                notice(),
                ChatMessage(role = MessageRole.USER, content = "never mind"),
                ChatMessage(role = MessageRole.ASSISTANT, content = "ok"),
            )
        rows.dropContinuedStopNotices()
        assertTrue(rows.first().content == "Operation interrupted.")
        assertFalse(rows.size < 3)
    }
}
