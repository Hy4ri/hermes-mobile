package com.m57.hermescontrol.ui.chat

import android.content.SharedPreferences
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.BusySendMode
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSendStoreTest {
    private fun pending(
        id: String,
        text: String,
        state: PendingSendState = PendingSendState.ACCEPTED,
    ) = PendingSend(id, "scope", "session", text, mode = BusySendMode.QUEUE, state = state)

    private fun mappedHistoryAliases(
        incoming: List<ChatMessage>,
        existing: List<ChatMessage>,
    ): List<ChatMessage> =
        incoming.zip(matchTranscriptMessages(incoming, existing)) { rest, match ->
            match?.copy(restId = rest.canonicalRestId) ?: rest
        }

    @Test
    fun durableAliasConfirmsAttachmentAndSteerNormalizedReceipt() {
        val local = ChatMessage("send-1", MessageRole.USER, "See attachment")
        val rest =
            ChatMessage(
                "rest-session-10",
                MessageRole.USER,
                """
                [OUT-OF-BAND USER MESSAGE — direct steering]
                @file:files/agent-vault/hermes/attachments/note.txt

                See attachment
                [/OUT-OF-BAND USER MESSAGE]
                """.trimIndent(),
            )

        val confirmed =
            pendingSendIdsConfirmedByDurableAliases(
                mappedHistoryAliases(listOf(rest), listOf(local)),
                listOf(pending("send-1", "See attachment")),
            )

        assertEquals(setOf("send-1"), confirmed)
    }

    @Test
    fun durableAliasConsumesOnlyOneDuplicateReceiptOccurrence() {
        val existing =
            listOf(
                ChatMessage("send-1", MessageRole.USER, "repeat"),
                ChatMessage("send-2", MessageRole.USER, "repeat"),
            )
        val aliases =
            mappedHistoryAliases(
                listOf(ChatMessage("rest-session-10", MessageRole.USER, "repeat")),
                existing,
            )

        val confirmed =
            pendingSendIdsConfirmedByDurableAliases(
                aliases,
                listOf(pending("send-1", "repeat"), pending("send-2", "repeat")),
            )

        assertEquals(setOf("send-1"), confirmed)
    }

    @Test
    fun repeatedHydrationDoesNotConfirmAnotherDuplicateReceipt() {
        val aliases =
            mappedHistoryAliases(
                listOf(ChatMessage("rest-session-10", MessageRole.USER, "repeat")),
                listOf(ChatMessage("send-1", MessageRole.USER, "repeat")),
            )
        val first =
            pendingSendIdsConfirmedByDurableAliases(
                aliases,
                listOf(pending("send-1", "repeat"), pending("send-2", "repeat")),
            )
        val remaining = listOf(pending("send-2", "repeat")).filterNot { it.id in first }

        assertTrue(pendingSendIdsConfirmedByDurableAliases(aliases, remaining).isEmpty())
    }

    @Test
    fun durableAliasDoesNotConfirmQueuedOrParkedReceipts() {
        val aliases =
            listOf(
                ChatMessage("queued", MessageRole.USER, "same", restId = "rest-session-10"),
                ChatMessage("parked", MessageRole.USER, "same", restId = "rest-session-11"),
            )

        val confirmed =
            pendingSendIdsConfirmedByDurableAliases(
                aliases,
                listOf(
                    pending("queued", "same", PendingSendState.QUEUED),
                    pending("parked", "same", PendingSendState.PARKED),
                ),
            )

        assertTrue(confirmed.isEmpty())
    }

    @Test
    fun promoteMovesTheChosenMessageAheadOfEarlierQueuedMessages() {
        val store = ChatSendStore()
        store.put(PendingSend("first", "scope", "session", "one", mode = BusySendMode.QUEUE))
        store.put(PendingSend("second", "scope", "session", "two", mode = BusySendMode.QUEUE))

        store.promote("second")

        assertEquals(listOf("second", "first"), store.all().map { it.id })
    }

    @Test
    fun recreationPreservesQueueAndQuarantinesAmbiguousSendsAcrossScopes() {
        var saved: String? = null
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.getString("rows", null) } answers { saved }
        every { prefs.edit() } returns editor
        every { editor.putString("rows", any()) } answers {
            saved = secondArg()
            editor
        }
        every { editor.commit() } returns true

        val first = ChatSendStore(prefs)
        first.put(
            PendingSend(
                "queued",
                "connection-a",
                "session-a",
                "later",
                attachments = listOf(Attachment("file:///private/photo", "photo.png", "image/png", 3)),
                mode = BusySendMode.QUEUE,
            ),
        )
        first.put(
            PendingSend(
                "sending",
                "connection-b",
                "session-b",
                "maybe",
                mode = BusySendMode.CORRECT,
                state = PendingSendState.SENDING,
            ),
        )
        first.park("connection-a", "session-a")

        val restored = ChatSendStore(prefs)
        assertEquals(PendingSendState.PARKED, restored.all().single { it.id == "queued" }.state)
        assertEquals(PendingSendState.UNKNOWN, restored.all().single { it.id == "sending" }.state)
        assertEquals("connection-a", restored.all().single { it.id == "queued" }.scope)
        assertEquals(
            "file:///private/photo",
            restored
                .all()
                .single { it.id == "queued" }
                .attachments
                .single()
                .uri,
        )
        assertEquals("session-b", restored.all().single { it.id == "sending" }.sessionId)

        restored.remove("queued")
        assertTrue(ChatSendStore(prefs).all().none { it.id == "queued" })
        assertEquals(1, ChatSendStore(prefs).all().size)
    }

    @Test
    fun recreationQuarantinesAcceptedReceiptWithoutAutomaticReplay() {
        var saved: String? = null
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { prefs.getString("rows", null) } answers { saved }
        every { prefs.edit() } returns editor
        every { editor.putString("rows", any()) } answers {
            saved = secondArg()
            editor
        }
        every { editor.commit() } returns true

        ChatSendStore(prefs).put(
            PendingSend(
                "accepted",
                "connection-a",
                "session-a",
                "possibly accepted",
                mode = BusySendMode.QUEUE,
                state = PendingSendState.ACCEPTED,
            ),
        )

        val restored = ChatSendStore(prefs).all().single()

        assertEquals(PendingSendState.UNKNOWN, restored.state)
        assertEquals(0, restored.attempts)
    }
}
