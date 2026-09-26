package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Issue #1284: history rows carry display_commentary / display_reasoning projections. */
class ChatHistoryCommentaryTest {
    private fun row(
        content: String,
        reasoning: String? = null,
        commentary: List<String>? = null,
        displayReasoning: String? = null,
        id: Int = 7,
    ) = SessionMessage(
        id = id,
        role = "assistant",
        content = JsonPrimitive(content),
        reasoning = reasoning?.let { JsonPrimitive(it) },
        display_commentary = commentary?.let { list -> JsonArray(list.map { JsonPrimitive(it) }) },
        display_reasoning = displayReasoning?.let { JsonPrimitive(it) as JsonElement },
    )

    private fun map(vararg rows: SessionMessage) = mapServerMessages("s", rows.toList(), 0, true, emptyList())

    private fun countOf(
        haystack: String,
        needle: String,
    ) = haystack.windowed(needle.length).count { it == needle }

    @Test
    fun commentaryRendersOnceBeforeReplyAndLeavesReasoning() {
        val commentary = "I will inspect the files."
        val message =
            map(
                row(
                    content = "Done: two files changed.",
                    reasoning = "Private plan.\n\n$commentary",
                    commentary = listOf(commentary),
                    displayReasoning = "Private plan.",
                ),
            ).single()

        assertEquals("$commentary\n\nDone: two files changed.", message.content)
        assertEquals("Private plan.", message.reasoningText)
        assertEquals(1, countOf(message.content + message.reasoningText, commentary))
    }

    @Test
    fun emptyDisplayReasoningWinsOverRawReasoning() {
        val commentary = "Checking."
        val message =
            map(row(content = "", reasoning = commentary, commentary = listOf(commentary), displayReasoning = ""))
                .single()

        assertEquals(commentary, message.content)
        assertEquals("", message.reasoningText)
    }

    @Test
    fun commentaryEqualToReplyIsNotDuplicated() {
        val text = "Looking at it now."
        val message = map(row(content = text, commentary = listOf(text), displayReasoning = "")).single()

        assertEquals(text, message.content)
    }

    @Test
    fun disabledCommentaryKeepsItOutOfTranscriptAndReasoning() {
        // Backend policy off: empty list, display_reasoning still strips the raw copy.
        val message =
            map(
                row(
                    content = "Answer",
                    reasoning = "Plan.\nSecret interim",
                    commentary = emptyList(),
                    displayReasoning = "Plan.",
                ),
            ).single()

        assertEquals("Answer", message.content)
        assertEquals("Plan.", message.reasoningText)
        assertFalse(message.content.contains("Secret interim"))
    }

    @Test
    fun olderGatewayFallsBackToRawReasoning() {
        val message = map(row(content = "Answer", reasoning = "Raw trace")).single()

        assertEquals("Answer", message.content)
        assertEquals("Raw trace", message.reasoningText)
    }

    @Test
    fun liveSealedCommentaryBubbleIsCoveredByRestRowNotDuplicated() {
        val commentary = "I will inspect the files."
        val liveOrphan = ChatMessage(id = "live-orphan", role = MessageRole.ASSISTANT, content = commentary)
        val rest =
            map(row(content = "", reasoning = commentary, commentary = listOf(commentary), displayReasoning = ""))

        val merged = mergeTranscriptWithLive(rest, listOf(liveOrphan), preserveLiveIds = true)

        assertEquals(1, merged.count { it.content.contains(commentary) })
        assertEquals("", merged.single().reasoningText)
    }

    @Test
    fun multipleCommentaryItemsKeepOrderAndSkipBlanks() {
        val message =
            map(row(content = "Final", commentary = listOf("First.", "  ", "Second."), displayReasoning = "")).single()

        assertEquals("First.\n\nSecond.\n\nFinal", message.content)
    }
}
