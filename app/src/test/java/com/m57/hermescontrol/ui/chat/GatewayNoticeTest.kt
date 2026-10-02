package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.local.toEntity
import com.m57.hermescontrol.data.local.toUiModel
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1433: gateway notices are per-session transcript rows that keep their position. */
class GatewayNoticeTest {
    private val compactedText = "✓ Context compaction complete — continuing turn..."

    private fun status(
        kind: String,
        text: String?,
        sessionId: String? = "s1",
    ) = WsEvent.StatusUpdate(
        status = null,
        data = mapOf("kind" to kind, "text" to text),
        sessionId = sessionId,
    )

    private fun reduce(
        state: ChatUiState,
        event: WsEvent,
        streaming: StreamingState = StreamingState(),
    ) = ChatWsEventReducer.reduce(state, streaming, event, currentSessionId = "s1")

    @Test
    fun sentinelMatchesEveryGatewayInterruptShape() {
        listOf(
            "[the reply degenerated into a repetition loop and was interrupted]",
            "Operation interrupted.",
            "Operation interrupted: waiting for the provider to recover (cycle 1/3).",
            "Operation interrupted: retrying API call after error (retry 2/5).",
            "Operation interrupted: handling API error (RateLimitError: slow down).",
            "Operation interrupted: retrying empty response from model (retry 1/2).",
            "Operation interrupted during retry (timeout, attempt 1/3).",
            "  Operation interrupted.\n",
        ).forEach { assertTrue(it, GatewayNotice.isInterruptSentinel(it)) }
        listOf(
            "Operation interrupted",
            "Operation interrupted. Here is what I found so far",
            "The operation was interrupted.",
            "Operation interrupted: first line.\nsecond line.",
        ).forEach { assertFalse(it, GatewayNotice.isInterruptSentinel(it)) }
    }

    @Test
    fun compactedNoticeBecomesPersistedSystemRowAndClearsTailState() {
        val user = ChatMessage(id = "u", role = MessageRole.USER, content = "go")
        val state =
            ChatUiState(
                currentSessionId = "s1",
                messages = listOf(user),
                isCompressing = true,
                compressionStatus = "🗜️ Compacting context",
            )

        val result = reduce(state, status("compacted", compactedText))

        assertFalse(result.state.isCompressing)
        assertNull(result.state.compressionStatus)
        val notice = result.state.messages.last()
        assertEquals(MessageRole.SYSTEM, notice.role)
        assertEquals(compactedText, notice.content)
        assertTrue(result.effects.any { it is ReducerEffect.PersistMessage && it.message.id == notice.id })
    }

    @Test
    fun replayedNoticeInSameTurnDoesNotStack() {
        val state =
            ChatUiState(
                currentSessionId = "s1",
                messages = listOf(ChatMessage(role = MessageRole.USER, content = "go")),
            )
        val once = reduce(state, status("compacted", compactedText)).state
        val twice = reduce(once, status("compacted", compactedText)).state
        assertEquals(1, twice.messages.count { it.role == MessageRole.SYSTEM })

        val nextTurn = twice.copy(messages = twice.messages + ChatMessage(role = MessageRole.USER, content = "again"))
        val again = reduce(nextTurn, status("compacted", compactedText)).state
        assertEquals(2, again.messages.count { it.role == MessageRole.SYSTEM })
    }

    @Test
    fun otherSessionsStatusUpdatesAreIgnored() {
        val state = ChatUiState(currentSessionId = "s1")
        listOf("compacting", "compacted", "goal", "warn").forEach { kind ->
            val result = reduce(state, status(kind, "text for $kind", sessionId = "other"))
            assertEquals(kind, state, result.state)
        }
    }

    @Test
    fun durableKindsAppendAndTransientKindsDoNot() {
        val state = ChatUiState(currentSessionId = "s1")
        listOf("goal", "loop", "warn", "fallback").forEach { kind ->
            assertEquals(kind, 1, reduce(state, status(kind, "✓ $kind")).state.messages.size)
        }
        listOf("status", "lifecycle", "process", "heartbeat", "ready").forEach { kind ->
            assertTrue(kind, reduce(state, status(kind, "x")).state.messages.isEmpty())
        }
    }

    @Test
    fun repetitionLoopCompletionDropsLoopedPartialAndAppendsNotice() {
        val sealed = ChatMessage(id = "looped", role = MessageRole.ASSISTANT, content = "again again again")
        val state = ChatUiState(currentSessionId = "s1", messages = listOf(sealed), isAgentTyping = true)
        val event =
            WsEvent.MessageComplete(
                text = GatewayNotice.REPETITION_LOOP_INTERRUPTED,
                sessionId = "s1",
                rawPayload = mapOf("status" to "interrupted"),
            )

        val result = reduce(state, event, StreamingState(interruptedMessage = sealed))

        assertEquals(listOf(MessageRole.SYSTEM), result.state.messages.map { it.role })
        assertEquals(
            GatewayNotice.REPETITION_LOOP_INTERRUPTED,
            result.state.messages
                .single()
                .content,
        )
        assertFalse(result.state.isAgentTyping)
        assertTrue(result.effects.contains(ReducerEffect.DeleteLocalMessage("looped")))
    }

    @Test
    fun operationInterruptedCompletionKeepsRealPartial() {
        val stream = ChatMessage(id = "p", role = MessageRole.ASSISTANT, content = "half an answer", isStreaming = true)
        val state = ChatUiState(currentSessionId = "s1")
        val event = WsEvent.MessageComplete(text = "Operation interrupted.", sessionId = "s1")

        val result = reduce(state, event, StreamingState(streamingMessage = stream))

        assertEquals(
            listOf("p", null),
            result.state.messages.map {
                if (it.role ==
                    MessageRole.SYSTEM
                ) {
                    null
                } else {
                    it.id
                }
            },
        )
        assertFalse(
            result.state.messages
                .first()
                .isStreaming,
        )
        assertEquals(
            "Operation interrupted.",
            result.state.messages
                .last()
                .content,
        )
    }

    @Test
    fun persistedSentinelRowMapsToSystemNoticeAndMergesWithLiveCopy() {
        val rows =
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("go")),
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive("Operation interrupted.")),
                SessionMessage(id = 3, role = "user", content = JsonPrimitive("next")),
                SessionMessage(id = 4, role = "assistant", content = JsonPrimitive("done")),
            )
        val page = mapServerMessages("s", rows, 0, true, emptyList())
        assertEquals(MessageRole.SYSTEM, page[1].role)

        // Live view after the interrupted turn: the notice sits between the two turns.
        val live =
            listOf(
                ChatMessage(id = "u1", role = MessageRole.USER, content = "go"),
                ChatMessage(id = "n", role = MessageRole.SYSTEM, content = "Operation interrupted."),
                ChatMessage(id = "u2", role = MessageRole.USER, content = "next"),
                ChatMessage(id = "a2", role = MessageRole.ASSISTANT, content = "done"),
            )
        val merged = mergeTranscriptWithLive(page, live, preserveLiveIds = true)
        assertEquals(
            listOf("go", "Operation interrupted.", "next", "done"),
            merged.map { it.content },
        )
    }

    @Test
    fun liveOnlyNoticeStaysInPlaceAcrossLaterTurnsAndRefresh() {
        val page =
            mapServerMessages(
                "s",
                listOf(
                    SessionMessage(id = 1, role = "user", content = JsonPrimitive("first")),
                    SessionMessage(id = 2, role = "assistant", content = JsonPrimitive("one")),
                    SessionMessage(id = 3, role = "user", content = JsonPrimitive("second")),
                    SessionMessage(id = 4, role = "assistant", content = JsonPrimitive("two")),
                ),
                0,
                true,
                emptyList(),
            )
        val live =
            listOf(
                ChatMessage(id = "u1", role = MessageRole.USER, content = "first"),
                ChatMessage(id = "a1", role = MessageRole.ASSISTANT, content = "one"),
                ChatMessage(id = "c", role = MessageRole.SYSTEM, content = compactedText),
                ChatMessage(id = "u2", role = MessageRole.USER, content = "second"),
                ChatMessage(id = "a2", role = MessageRole.ASSISTANT, content = "two"),
            )
        val once = mergeTranscriptWithLive(page, live, preserveLiveIds = true)
        val twice = mergeTranscriptWithLive(page, once, preserveLiveIds = true)
        val expected = listOf("first", "one", compactedText, "second", "two")
        assertEquals(expected, once.map { it.content })
        assertEquals(expected, twice.map { it.content })
    }

    @Test
    fun roomRoundTripNormalizesLegacyAssistantSentinel() {
        val legacy = ChatMessage(id = "x", role = MessageRole.ASSISTANT, content = "Operation interrupted.")
        val restored = legacy.toEntity("s").toUiModel()
        assertEquals(MessageRole.SYSTEM, restored.role)
        val prose = ChatMessage(id = "y", role = MessageRole.ASSISTANT, content = "fine")
        assertEquals(MessageRole.ASSISTANT, prose.toEntity("s").toUiModel().role)
    }
}
