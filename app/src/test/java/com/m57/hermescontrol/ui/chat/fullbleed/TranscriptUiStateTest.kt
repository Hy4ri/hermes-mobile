package com.m57.hermescontrol.ui.chat.fullbleed

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.BusySendMode
import com.m57.hermescontrol.ui.chat.ChatMessage
import com.m57.hermescontrol.ui.chat.ChatTimelineState
import com.m57.hermescontrol.ui.chat.ChatUiState
import com.m57.hermescontrol.ui.chat.ClarifyUi
import com.m57.hermescontrol.ui.chat.MessageRole
import com.m57.hermescontrol.ui.chat.PendingSend
import com.m57.hermescontrol.ui.chat.PendingSendState
import com.m57.hermescontrol.ui.chat.StreamingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptUiStateTest {
    private val live = ChatMessage(id = "live", role = MessageRole.USER, content = "hello")
    private val historical = ChatMessage(id = "historical", role = MessageRole.USER, content = "older")
    private val clarify = ClarifyUi(text = "choose")

    @Test
    fun acceptedLivePromptKeepsItsTextAndImageUntilHistoryConfirmsIt() {
        val image = Attachment("file:///private/photo", "photo.png", "image/png", 3)
        val prompt = live.copy(attachments = listOf(image), serverRowId = 10)
        val result =
            TranscriptUiState.resolve(
                chat =
                    ChatUiState(
                        messages = listOf(prompt),
                        isAgentTyping = true,
                        pendingSends =
                            listOf(
                                PendingSend(
                                    prompt.id,
                                    "scope",
                                    "session",
                                    prompt.content,
                                    attachments = listOf(image),
                                    mode = BusySendMode.CORRECT,
                                    state = PendingSendState.ACCEPTED,
                                    userRowId = 10,
                                ),
                            ),
                    ),
                timeline = ChatTimelineState(),
                streaming = StreamingState(),
                savingAttachmentPath = null,
                speakingMessageId = null,
            )

        assertEquals(listOf(prompt), result.messages)
        assertEquals(listOf(image), result.messages.single().attachments)
    }

    @Test
    fun `live state preserves prompts streaming and paging actions`() {
        val stream = StreamingState()
        val result =
            TranscriptUiState.resolve(
                chat =
                    ChatUiState(
                        messages = listOf(live),
                        currentSessionId = "session",
                        isAgentTyping = true,
                        isLoading = true,
                        isLoadingOlder = true,
                        hasOlderMessages = true,
                        clarifyRequest = clarify,
                        isCompressing = true,
                        savingAttachmentPath = "server-path",
                    ),
                timeline = ChatTimelineState(),
                streaming = stream,
                savingAttachmentPath = "local-path",
                speakingMessageId = "live",
            )
        assertEquals(listOf(live), result.messages)
        assertSame(stream, result.streamingState)
        assertEquals("session", result.pagingSessionId)
        assertTrue(result.isAgentTyping)
        assertTrue(result.isLoading)
        assertTrue(result.isLoadingOlder)
        assertTrue(result.hasOlderMessages)
        assertSame(clarify, result.clarifyRequest)
        assertTrue(result.isCompressing)
        assertEquals("local-path", result.savingAttachmentPath)
        assertEquals("live", result.speakingMessageId)
    }

    @Test
    fun `historical state isolates live tail prompts loading and compression`() {
        val result =
            TranscriptUiState.resolve(
                chat =
                    ChatUiState(
                        messages = listOf(live),
                        currentSessionId = "session",
                        isAgentTyping = true,
                        isLoading = true,
                        isLoadingOlder = true,
                        hasOlderMessages = true,
                        clarifyRequest = clarify,
                        isCompressing = true,
                        compressionStatus = "compressing",
                    ),
                timeline = ChatTimelineState(historyMessages = listOf(historical), historyAnchorRowId = 42),
                streaming = StreamingState(streamingMessage = live),
                savingAttachmentPath = null,
                speakingMessageId = null,
            )
        assertEquals(listOf(historical), result.messages)
        assertNull(result.streamingState.streamingMessage)
        assertEquals("session:history:42", result.pagingSessionId)
        assertFalse(result.isAgentTyping)
        assertFalse(result.isLoading)
        assertFalse(result.isLoadingOlder)
        assertFalse(result.hasOlderMessages)
        assertNull(result.clarifyRequest)
        assertFalse(result.isCompressing)
        assertNull(result.compressionStatus)
    }
}
