package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.BusySendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: a canonical cache row must not strand a receipt with a proven server row ID. */
class PendingReceiptRestIdentityTest {
    private fun pending(
        id: String = "local-submit",
        rowId: Long? = 77L,
        state: PendingSendState = PendingSendState.UNKNOWN,
    ) = PendingSend(
        id = id,
        scope = "scope",
        sessionId = "session",
        text = "come procede",
        mode = BusySendMode.QUEUE,
        state = state,
        userRowId = rowId,
    )

    private fun canonicalRow(rowId: Long = 77L) =
        ChatMessage(
            id = "rest-session-77",
            role = MessageRole.USER,
            content = "come procede",
            serverRowId = rowId,
        )

    @Test
    fun canonicalCacheRowDoesNotStrandReceiptWithExactServerIdentity() {
        val canonical = canonicalRow()
        val receiptCandidate =
            ChatMessage(
                id = "local-submit",
                role = MessageRole.USER,
                content = "come procede",
                serverRowId = 77L,
            )
        // Mirrors mergeHistoryPage: canonical cache row exists before REST hydration;
        // the missing optimistic bubble is offered as a receipt-only candidate.
        val merged =
            mergeTranscriptWithLive(
                listOf(canonical),
                listOf(canonical, receiptCandidate),
                chronological = true,
                preserveLiveIds = true,
            ).filterNot { it.id == receiptCandidate.id && it.canonicalRestId == null }
        val confirmed = pendingSendIdsConfirmedByDurableAliases(merged, listOf(pending()))

        assertEquals(setOf("local-submit"), confirmed)
    }

    @Test
    fun canonicalRowDoesNotConfirmUnprovenSameTextReceipt() {
        assertTrue(
            pendingSendIdsConfirmedByDurableAliases(
                listOf(canonicalRow()),
                listOf(pending(rowId = null)),
            ).isEmpty(),
        )
    }

    @Test
    fun canonicalRowDoesNotConfirmDifferentServerIdentity() {
        assertTrue(
            pendingSendIdsConfirmedByDurableAliases(
                listOf(canonicalRow()),
                listOf(pending(rowId = 78L)),
            ).isEmpty(),
        )
    }

    @Test
    fun exactServerIdentityDoesNotDispatchQueuedOrParkedReceipts() {
        val receipts =
            listOf(
                pending(id = "queued", state = PendingSendState.QUEUED),
                pending(id = "parked", state = PendingSendState.PARKED),
            )
        assertTrue(pendingSendIdsConfirmedByDurableAliases(listOf(canonicalRow()), receipts).isEmpty())
    }

    @Test
    fun survivingAliasCannotOverrideKnownReceiptRowIdentity() {
        val alias = canonicalRow().copy(id = "local-submit", restId = "rest-session-77")
        assertTrue(pendingSendIdsConfirmedByDurableAliases(listOf(alias), listOf(pending(rowId = 78L))).isEmpty())
    }

    @Test
    fun survivingContentAliasCannotConfirmIdlessUnknownReceipt() {
        val optimistic = ChatMessage("local-submit", MessageRole.USER, "come procede")
        val merged = mergeTranscriptWithLive(listOf(canonicalRow()), listOf(optimistic), preserveLiveIds = true)
        assertTrue(pendingSendIdsConfirmedByDurableAliases(merged, listOf(pending(rowId = null))).isEmpty())
    }

    @Test
    fun restoredIdlessAcceptedReceiptCannotClaimSameTextRestRow() {
        val restored =
            pending(rowId = null, state = PendingSendState.ACCEPTED)
                .copy(requiresExactReconciliation = true)
        val sameTextAlias = canonicalRow().copy(id = restored.id, restId = "rest-session-77")

        assertTrue(pendingSendIdsConfirmedByDurableAliases(listOf(sameTextAlias), listOf(restored)).isEmpty())
    }

    @Test
    fun visibleExactOnlyBubbleCannotClaimRepeatedRestText() {
        val receipt =
            pending(rowId = null, state = PendingSendState.ACCEPTED)
                .copy(requiresExactReconciliation = true)
        val visible = ChatMessage(receipt.id, MessageRole.USER, receipt.text)
        val merged =
            mergeTranscriptWithLive(
                listOf(canonicalRow()),
                listOf(visible),
                preserveLiveIds = true,
                contentMatchExcludedIds = setOf(receipt.id),
            )
        assertEquals(2, merged.size)
        assertEquals(null, merged.single { it.id == visible.id }.canonicalRestId)
        assertTrue(merged.any { it.id == canonicalRow().id })
    }

    @Test
    fun visibleUnknownBubbleWithExactRowCanOnlyMatchThatRow() {
        val visible = ChatMessage("local-submit", MessageRole.USER, "come procede", serverRowId = 78L)
        val merged =
            mergeTranscriptWithLive(
                listOf(canonicalRow(77L), canonicalRow(78L).copy(id = "rest-session-78")),
                listOf(visible),
                preserveLiveIds = true,
                contentMatchExcludedIds = setOf(visible.id),
            )
        assertEquals(78L, merged.single { it.id == visible.id }.serverRowId)
        assertEquals("rest-session-78", merged.single { it.id == visible.id }.canonicalRestId)
        assertTrue(merged.any { it.id == canonicalRow().id })
    }

    @Test
    fun knownReceiptIsConfirmedByExactRowEvenWhenTextChanges() {
        val alias = canonicalRow().copy(id = "local-submit", restId = "rest-session-77", content = "wrapped prompt")
        assertEquals(setOf("local-submit"), pendingSendIdsConfirmedByDurableAliases(listOf(alias), listOf(pending())))
    }
}
