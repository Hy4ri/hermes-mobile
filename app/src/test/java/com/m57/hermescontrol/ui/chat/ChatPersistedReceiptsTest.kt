package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1285: persisted-turn receipts are addresses, never negative acknowledgements. */
class ChatPersistedReceiptsTest {
    @Test
    fun parsesCompleteReceiptWithMixedNumberTypes() {
        val turn =
            parsePersistedTurn(
                mapOf(
                    "persisted_turn" to
                        mapOf(
                            "row_ids" to listOf(41, 42L, 43.0),
                            "complete" to true,
                            "user_row_id" to 41,
                            "final_assistant_row_id" to 43L,
                        ),
                ),
            )

        assertEquals(PersistedTurn(true, 41L, 43L), turn)
        assertEquals(
            43L,
            parsePersistedTurn(mapOf("persisted_turn" to mapOf("final_assistant_row_id" to 43.0)))?.finalAssistantRowId,
        )
    }

    @Test
    fun missingIdsStayUnprovenAndMalformedValuesAreIgnored() {
        val turn =
            parsePersistedTurn(
                mapOf(
                    "persisted_turn" to
                        mapOf("complete" to "true", "user_row_id" to true, "final_assistant_row_id" to 1.5),
                ),
            )!!

        assertFalse(turn.complete)
        assertNull(positiveRowId("8"))
        assertNull(positiveRowId(0))
        assertNull(positiveRowId(-1L))
        assertNull(turn.userRowId)
        assertNull(turn.finalAssistantRowId)
        assertNull(parsePersistedTurn(mapOf("text" to "done")))
        assertNull(parsePersistedTurn(null))
    }

    @Test
    fun rowIdMatchesUserEchoEvenWhenServerTextDiffers() {
        val local = ChatMessage(id = "local", role = MessageRole.USER, content = "hi", serverRowId = 42)
        val rest =
            ChatMessage(id = "rest-s-42", role = MessageRole.USER, content = "hi\n\n--- rewritten", serverRowId = 42)

        val merged = mergeTranscriptWithLive(listOf(rest), listOf(local), preserveLiveIds = true)

        assertEquals(listOf("local"), merged.map { it.id })
        assertEquals("rest-s-42", merged.single().restId)
        assertEquals(42L, merged.single().serverRowId)
    }

    @Test
    fun differentRowIdsKeepRepeatedTextAsSeparateTurns() {
        val local = ChatMessage(id = "local", role = MessageRole.USER, content = "again", serverRowId = 43)
        val older = ChatMessage(id = "rest-s-42", role = MessageRole.USER, content = "again", serverRowId = 42)

        val merged = mergeTranscriptWithLive(listOf(older), listOf(local), preserveLiveIds = true)

        assertEquals(setOf("local", "rest-s-42"), merged.map { it.id }.toSet())
    }

    @Test
    fun missingRowIdFallsBackToContentMatching() {
        val local = ChatMessage(id = "local", role = MessageRole.USER, content = "hello")
        val rest = ChatMessage(id = "rest-s-9", role = MessageRole.USER, content = "hello", serverRowId = 9)

        val merged = mergeTranscriptWithLive(listOf(rest), listOf(local), preserveLiveIds = true)

        assertEquals(listOf("local"), merged.map { it.id })
        assertEquals(9L, merged.single().serverRowId)
    }

    @Test
    fun completeReceiptBindsFinalAssistantRow() {
        val result =
            complete(
                "answer",
                mapOf(
                    "row_ids" to listOf(1, 2),
                    "complete" to true,
                    "final_assistant_row_id" to 2,
                ),
            )

        assertEquals(
            2L,
            result.state.messages
                .single()
                .serverRowId,
        )
    }

    @Test
    fun partialReceiptStillBindsProvenFinalRowButKeepsLocalReply() {
        val result =
            complete("answer", mapOf("row_ids" to listOf(2), "complete" to false, "final_assistant_row_id" to 2))

        assertEquals(
            "answer",
            result.state.messages
                .single()
                .content,
        )
        assertEquals(
            2L,
            result.state.messages
                .single()
                .serverRowId,
        )
    }

    @Test
    fun receiptWithoutFinalIdLeavesReplyUnbound() {
        val result = complete("answer", mapOf("row_ids" to listOf(1), "complete" to false, "user_row_id" to 1))

        assertEquals(
            "answer",
            result.state.messages
                .single()
                .content,
        )
        assertNull(
            result.state.messages
                .single()
                .serverRowId,
        )
    }

    @Test
    fun strippedCommentaryPrefixDoesNotClaimFinalRow() {
        val sealed = ChatMessage(id = "sealed", role = MessageRole.ASSISTANT, content = "Looking. ")
        val result =
            ChatWsEventReducer.reduce(
                state = ChatUiState(currentSessionId = "session-1", messages = listOf(sealed)),
                streamingState = StreamingState(sealedOrphanIds = listOf("sealed")),
                event =
                    WsEvent.MessageComplete(
                        "Looking. Done",
                        "session-1",
                        rawPayload =
                            mapOf(
                                "persisted_turn" to
                                    mapOf("row_ids" to listOf(5), "complete" to true, "final_assistant_row_id" to 5),
                            ),
                    ),
                currentSessionId = "session-1",
            )

        val reply = result.state.messages.single { it.id != "sealed" }
        assertEquals("Done", reply.content)
        assertNull(reply.serverRowId)
        assertTrue(result.state.messages.none { it.serverRowId == 5L })
    }

    private fun complete(
        text: String,
        receipt: Map<String, Any?>,
    ) = ChatWsEventReducer.reduce(
        state = ChatUiState(currentSessionId = "session-1"),
        streamingState = StreamingState(),
        event = WsEvent.MessageComplete(text, "session-1", rawPayload = mapOf("persisted_turn" to receipt)),
        currentSessionId = "session-1",
    )
}
