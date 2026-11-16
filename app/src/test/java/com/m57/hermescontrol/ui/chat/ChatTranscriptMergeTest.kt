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

    private val delivered = local(restored = false).copy(serverRowId = 150L, restId = "rest-s-150")

    @Test
    fun deliveredRowFoldsWhenNewestPageProvesItsIdWasReissued() {
        val page = listOf(before.copy(serverRowId = 100L, timestamp = sent - 600_000L), rest(200), after)
        val superseded = reissuedRowIds(page, listOf(delivered), pageIsNewest = true)
        assertEquals(setOf(150L), superseded)
        val merged = mergeTranscriptWithLive(page, listOf(delivered), supersededRowIds = superseded)
        assertEquals(listOf("before", "做 1", "after"), merged.map { it.content })
        assertEquals(200L, merged[1].serverRowId)
    }

    @Test
    fun deliveredFirstSendFoldsWhenCompletePageStartsAtItsTimestamp() {
        // Brand-new session: the page's oldest row IS the rewritten first send, so no row is 2 min newer.
        val page = listOf(rest(200), after)
        assertEquals(emptySet<Long>(), reissuedRowIds(page, listOf(delivered), pageIsNewest = true))
        val superseded = reissuedRowIds(page, listOf(delivered), pageIsNewest = true, pageIsComplete = true)
        assertEquals(setOf(150L), superseded)
        val merged = mergeTranscriptWithLive(page, listOf(delivered), supersededRowIds = superseded)
        assertEquals(listOf("做 1", "after"), merged.map { it.content })
    }

    @Test
    fun completePageDoesNotSupersedeRowsItStillContainsOrAnOlderPage() {
        val page = listOf(rest(150), after)
        assertEquals(emptySet<Long>(), reissuedRowIds(page, listOf(delivered), true, pageIsComplete = true))
        assertEquals(emptySet<Long>(), reissuedRowIds(listOf(rest(200)), listOf(delivered), false, true))
    }

    @Test
    fun deliveredRowStaysWithoutReissueProof() {
        val page = listOf(before.copy(serverRowId = 100L, timestamp = sent - 600_000L), rest(200), after)
        assertEquals(emptySet<Long>(), reissuedRowIds(page, listOf(delivered), pageIsNewest = false))
        assertEquals(4, mergeTranscriptWithLive(page, listOf(delivered)).size)
    }

    @Test
    fun rowStillOnThePageOrOlderThanItIsNotSuperseded() {
        val page = listOf(rest(150, timestamp = sent - 600_000L), rest(200))
        assertEquals(emptySet<Long>(), reissuedRowIds(page, listOf(delivered), pageIsNewest = true))
        val older = delivered.copy(timestamp = sent - 900_000L, serverRowId = 90L)
        assertEquals(emptySet<Long>(), reissuedRowIds(page, listOf(older), pageIsNewest = true))
    }

    @Test
    fun supersededRowWithRepeatedRestCopiesStaysSeparate() {
        val page = listOf(before.copy(serverRowId = 100L, timestamp = sent - 600_000L), rest(200), rest(250), after)
        val merged = mergeTranscriptWithLive(page, listOf(delivered), supersededRowIds = setOf(150L))
        assertEquals(5, merged.size)
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

    private fun row(
        id: String,
        role: MessageRole,
        content: String,
        serverRow: Long,
        reasoning: String = "",
        toolCallId: String = "",
        completionId: String? = null,
    ) = ChatMessage(
        id = id,
        role = role,
        content = content,
        timestamp = sent + serverRow % 10 * 1_000L,
        serverRowId = serverRow,
        restId = "rest-s-$serverRow".takeUnless { id.startsWith("rest-") },
        reasoningText = reasoning,
        toolCallId = toolCallId,
        completionId = completionId,
    )

    /** One tool turn under row ids [first]..[first]+3: prompt, reasoning-only tool call, result, reply. */
    private fun toolTurn(
        first: Long,
        live: Boolean,
    ): List<ChatMessage> {
        fun id(row: Long) = if (live) "live-$row" else "rest-s-$row"
        return listOf(
            row(id(first), MessageRole.USER, "Run ls", first),
            row(id(first + 1), MessageRole.ASSISTANT, "", first + 1, reasoning = "R1 plan"),
            row(id(first + 2), MessageRole.TOOL, "a.txt", first + 2, toolCallId = "call_1"),
            row(id(first + 3), MessageRole.ASSISTANT, "One file.", first + 3, completionId = "c1".takeIf { live }),
        )
    }

    private fun shape(messages: List<ChatMessage>) = messages.map { "${it.role}:${it.content}|${it.reasoningText}" }

    @Test
    fun reissuedAssistantAndToolRowsFoldIntoTheirCopies() {
        // #1831: compaction re-issued the whole turn 10..13 -> 20..23, not only the prompt.
        val shown = toolTurn(10, live = true)
        val page = toolTurn(20, live = false)
        val superseded = reissuedRowIds(page, shown, pageIsNewest = true, pageIsComplete = true)
        assertEquals(setOf(10L, 11L, 12L, 13L), superseded)
        val merged = mergeTranscriptWithLive(page, shown, preserveLiveIds = true, supersededRowIds = superseded)
        assertEquals(shape(page), shape(merged))
        assertEquals(listOf(20L, 21L, 22L, 23L), merged.map { it.serverRowId })
    }

    @Test
    fun cachedRestTurnFoldsIntoReissuedCopies() {
        // Reopened from the Room cache: every known row carries a REST id of its own.
        val shown = toolTurn(10, live = false)
        val page = toolTurn(20, live = false)
        val superseded = reissuedRowIds(page, shown, pageIsNewest = true, pageIsComplete = true)
        val merged = mergeTranscriptWithLive(page, shown, preserveLiveIds = true, supersededRowIds = superseded)
        assertEquals(shape(page), shape(merged))
        assertEquals(listOf(20L, 21L, 22L, 23L), merged.map { it.serverRowId })
    }

    @Test
    fun repeatedReissuedRepliesPairInTranscriptOrder() {
        fun turn(
            first: Long,
            prefix: String,
        ) = listOf(
            row("$prefix-$first", MessageRole.USER, "a", first),
            row("$prefix-${first + 1}", MessageRole.ASSISTANT, "OK", first + 1),
            row("$prefix-${first + 2}", MessageRole.USER, "b", first + 2),
            row("$prefix-${first + 3}", MessageRole.ASSISTANT, "OK", first + 3),
        )
        val shown = turn(10, "live")
        val page = turn(20, "rest-s").map { it.copy(id = "rest-s-${it.serverRowId}") }
        val superseded = reissuedRowIds(page, shown, pageIsNewest = true, pageIsComplete = true)
        val merged = mergeTranscriptWithLive(page, shown, preserveLiveIds = true, supersededRowIds = superseded)
        assertEquals(listOf("a", "OK", "b", "OK"), merged.map { it.content })
        assertEquals(listOf("live-11", "live-13"), merged.filter { it.role == MessageRole.ASSISTANT }.map { it.id })
        assertEquals(listOf(21L, 23L), merged.filter { it.role == MessageRole.ASSISTANT }.map { it.serverRowId })
    }

    @Test
    fun assistantRowsStaySeparateWithoutReissueProof() {
        val shown = toolTurn(10, live = true)
        val page = toolTurn(20, live = false)
        assertEquals(emptySet<Long>(), reissuedRowIds(page, shown, pageIsNewest = false))
        assertEquals(8, mergeTranscriptWithLive(page, shown, preserveLiveIds = true).size)
    }

    @Test
    fun staleTurnDropsWhenItsReissuedCopyIsAlreadyShown() {
        // #1831: a sync stored the re-issued copy next to the original, so reopen showed the turn twice.
        val reissued = toolTurn(20, live = false)
        val shown = toolTurn(10, live = true) + reissued
        val superseded = reissuedRowIds(reissued, shown, pageIsNewest = true, pageIsComplete = true)
        val merged = mergeTranscriptWithLive(reissued, shown, preserveLiveIds = true, supersededRowIds = superseded)
        assertEquals(shape(reissued), shape(merged))
        assertEquals(listOf(20L, 21L, 22L, 23L), merged.map { it.serverRowId })
    }

    @Test
    fun repeatedTurnKeepsTheRowsThePageStillHolds() {
        // The same prompt and reply sent twice: neither id was re-issued, so nothing is dropped.
        val first = toolTurn(10, live = false)
        val second = toolTurn(20, live = false)
        val page = first + second
        val superseded = reissuedRowIds(page, page, pageIsNewest = true, pageIsComplete = true)
        assertEquals(emptySet<Long>(), superseded)
        assertEquals(8, mergeTranscriptWithLive(page, page, preserveLiveIds = true, supersededRowIds = superseded).size)
    }

    @Test
    fun cachedGenerationsWithoutServerRowIdsCollapseToThePageCopy() {
        // #1831 on device: Room drops serverRowId, and two compactions left 10..13 and 20..23 next to 30..33.
        fun cached(first: Long) = toolTurn(first, live = false).map { it.copy(serverRowId = null) }
        val page = toolTurn(30, live = false)
        val shown = cached(10) + cached(20) + cached(30)
        val superseded = reissuedRowIds(page, shown, pageIsNewest = true, pageIsComplete = true)
        assertEquals((10L..13L).toSet() + (20L..23L).toSet(), superseded)
        val merged = mergeTranscriptWithLive(page, shown, preserveLiveIds = true, supersededRowIds = superseded)
        assertEquals(shape(page), shape(merged))
        assertEquals(page.map { it.id }, merged.map { it.id })
    }

    @Test
    fun localRowAfterADroppedStaleRowKeepsItsPlace() {
        // A local notice shown after the stale reply must stay after the reply's surviving copy.
        val stale = toolTurn(10, live = false).map { it.copy(serverRowId = null) }
        val command =
            ChatMessage(
                id = "local-notice",
                role = MessageRole.SYSTEM,
                content = "Compressed",
                timestamp = sent + 9_000L,
            )
        val page = toolTurn(20, live = false)
        val shown = stale + command + page
        val superseded = reissuedRowIds(page, shown, pageIsNewest = true, pageIsComplete = true)
        val merged = mergeTranscriptWithLive(page, shown, preserveLiveIds = true, supersededRowIds = superseded)
        assertEquals(listOf("Run ls", "", "a.txt", "One file.", "Compressed"), merged.map { it.content })
    }
}
