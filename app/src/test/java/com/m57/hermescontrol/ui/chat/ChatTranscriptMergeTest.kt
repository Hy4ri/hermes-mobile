package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** #1491: compaction re-issues gateway row ids, so a locally sent row must fold into its REST copy. */
class ChatTranscriptMergeTest {
    private val sent = 1_000_000_000L

    private fun local(
        restId: String? = null,
        restored: Boolean = true,
    ) = ChatMessage(
        id = "6f0c2c1e-0000-4000-8000-000000000001",
        role = MessageRole.USER,
        content = "做 1",
        timestamp = sent,
        restId = restId,
        messageProvenance = MessageProvenance.LOCAL_PENDING,
        isRestoredUnconfirmed = restored,
    )

    private fun rest(
        row: Long,
        timestamp: Long = sent + 3_000L,
    ) = ChatMessage(
        id = "rest-s-$row",
        role = MessageRole.USER,
        content = "做 1",
        timestamp = timestamp,
        serverRowId = row,
    )

    private val before = ChatMessage(id = "rest-s-100", role = MessageRole.ASSISTANT, content = "before")
    private val after = ChatMessage(id = "rest-s-300", role = MessageRole.ASSISTANT, content = "after")

    @Test
    fun receiptlessRestoredRowFoldsIntoRewrittenRestCopy() {
        val local = local()
        val merged =
            mergeTranscriptWithLive(
                listOf(before, rest(200), after),
                listOf(local),
                contentMatchExcludedIds = setOf(local.id),
            )
        assertEquals(listOf("before", "做 1", "after"), merged.map { it.content })
        assertEquals("rest-s-200", merged[1].canonicalRestId)
    }

    @Test
    fun staleRestIdRowFoldsIntoRewrittenRestCopy() {
        val merged = mergeTranscriptWithLive(listOf(before, rest(200), after), listOf(local(restId = "rest-s-150")))
        assertEquals(listOf("before", "做 1", "after"), merged.map { it.content })
        assertEquals("rest-s-200", merged[1].canonicalRestId)
    }

    @Test
    fun repeatedIdenticalRestPromptsDoNotClaimTheLocalRow() {
        val local = local()
        val merged =
            mergeTranscriptWithLive(
                listOf(before, rest(200), rest(250), after),
                listOf(local),
                contentMatchExcludedIds = setOf(local.id),
            )
        assertEquals(5, merged.size)
    }

    @Test
    fun sameTextOutsideTimeWindowStaysSeparate() {
        val local = local()
        val merged =
            mergeTranscriptWithLive(
                listOf(before, rest(200, timestamp = sent + 3_600_000L), after),
                listOf(local),
                contentMatchExcludedIds = setOf(local.id),
            )
        assertEquals(4, merged.size)
    }
}
