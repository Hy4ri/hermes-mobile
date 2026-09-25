package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.BusySendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatQueuePresentationTest {
    private val message = ChatMessage("queued-id", MessageRole.USER, "Later")

    private fun pending(state: PendingSendState) =
        PendingSend(message.id, "scope", "session", message.content, mode = BusySendMode.QUEUE, state = state)

    @Test
    fun queuedAndParkedMessagesRemainInternalUntilDispatch() {
        val messages = listOf(message)
        for (state in listOf(PendingSendState.QUEUED, PendingSendState.PARKED)) {
            val receipts = listOf(pending(state))
            assertTrue(messagesWithoutUnsentQueue(messages, receipts).isEmpty())
            assertEquals(listOf(message), messages)
            assertEquals(state, receipts.single().state)
        }
    }

    @Test
    fun queuedMessageAppearsOnceWhenSendingStarts() {
        val messages = listOf(message)
        assertTrue(messagesWithoutUnsentQueue(messages, listOf(pending(PendingSendState.QUEUED))).isEmpty())
        for (state in listOf(PendingSendState.SENDING, PendingSendState.ACCEPTED)) {
            assertEquals(messages, messagesWithoutUnsentQueue(messages, listOf(pending(state))))
        }
    }

    @Test
    fun ordinarySendAndOtherMessagesAreUnchanged() {
        val other = ChatMessage("other", MessageRole.USER, "Send now")
        val messages = listOf(other, message)
        assertSame(messages, messagesWithoutUnsentQueue(messages, emptyList()))
        assertEquals(listOf(other), messagesWithoutUnsentQueue(messages, listOf(pending(PendingSendState.QUEUED))))
    }

    @Test
    fun canonicalHistoryIsNeverHiddenByStaleQueueReceipt() {
        val messages = listOf(message.copy(restId = "rest-session-12"))
        assertEquals(messages, messagesWithoutUnsentQueue(messages, listOf(pending(PendingSendState.QUEUED))))
    }
}
