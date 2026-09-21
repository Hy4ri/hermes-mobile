package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPagingMergeTest {
    @Test
    fun confirmedLiveOccurrenceCannotConsumeEarlierIdenticalPage() {
        for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL)) {
            val content = if (role == MessageRole.TOOL) "{\"output\":\"ok\"}" else "continue"
            val live = ChatMessage(id = "uuid-new", role = role, content = content, timestamp = 2L)
            val newest = live.copy(id = "rest-s-20")
            val earlier = live.copy(id = "rest-s-10", timestamp = 1L)
            val hydrated = mergeTranscriptWithLive(listOf(newest), listOf(live), preserveLiveIds = true)
            val paged =
                mergeTranscriptWithLive(
                    listOf(earlier),
                    hydrated,
                    chronological = false,
                    preserveLiveIds = true,
                )
            assertEquals("Distinct $role occurrences must survive across pages", 2, paged.size)
            assertEquals(listOf("rest-s-10", "uuid-new"), paged.map { it.id })
            val refreshed = mergeTranscriptWithLive(listOf(newest), paged, preserveLiveIds = true)
            assertEquals(2, refreshed.size)
            assertEquals("uuid-new", refreshed.last().id)
        }
    }

    @Test
    fun mapperAndMergeKeepConfirmedOccurrencesMetadataAndKeysAcrossPages() {
        for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL)) {
            val content = if (role == MessageRole.TOOL) """{"output":"ok"}""" else "continue"
            val live =
                ChatMessage(
                    id = "uuid-new",
                    role = role,
                    content = content,
                    timestamp = 100L,
                    reasoningText = "live reasoning",
                    toolName = "terminal",
                    toolStatus = ToolStatus.COMPLETED,
                    attachments =
                        listOf(
                            Attachment(uri = "content://image", name = "image", mimeType = "image/png", size = 1),
                        ),
                    finishTimestamp = 200L,
                    tps = 5.0,
                )

            fun page(
                id: Int,
                current: List<ChatMessage>,
            ): List<ChatMessage> =
                mapServerMessages(
                    "s",
                    listOf(SessionMessage(id = id, role = role.name.lowercase(), content = JsonPrimitive(content))),
                    0,
                    true,
                    current,
                )

            val hydrated = mergeTranscriptWithLive(page(20, listOf(live)), listOf(live), preserveLiveIds = true)
            val confirmed = hydrated.single()
            assertEquals("rest-s-20", confirmed.restId)
            assertEquals(live.id, confirmed.id)
            assertEquals(live.reasoningText, confirmed.reasoningText)
            assertEquals(live.attachments, confirmed.attachments)
            assertEquals(live.finishTimestamp, confirmed.finishTimestamp)
            assertEquals(live.tps, confirmed.tps)
            assertEquals(live.toolStatus, confirmed.toolStatus)
            val refreshedPage = page(20, hydrated)
            assertEquals(confirmed.timestamp, refreshedPage.single().timestamp)

            // Missing server timestamps must not put this older occurrence after the UUID alias.
            val paged =
                mergeTranscriptWithLive(page(10, hydrated), hydrated, chronological = false, preserveLiveIds = true)
            assertEquals(listOf("rest-s-10", "uuid-new"), paged.map { it.id })
            assertEquals("", paged.first().reasoningText)
            val refreshed =
                mergeTranscriptWithLive(page(10, paged), paged, chronological = false, preserveLiveIds = true)
            assertEquals(paged, refreshed)
            assertEquals("rest-s-20", refreshed.last().restId)
        }
    }

    @Test
    fun cacheAliasesRejectEarlierOccurrencesInEitherPageOrder() {
        for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL)) {
            val content = if (role == MessageRole.TOOL) """{"output":"ok"}""" else "continue"
            val live = ChatMessage(id = "uuid", role = role, content = content, timestamp = 20L)
            val echo = live.copy(id = "rest-s-20")
            val earlier = live.copy(id = "rest-s-10", timestamp = 10L)
            for (firstPage in listOf(listOf(live, echo), listOf(echo, live))) {
                val cached = dedupeCachedMessages(firstPage)
                assertEquals(listOf(live.copy(restId = echo.id)), cached)
                val nextPage = dedupeCachedMessages(listOf(earlier) + cached)
                assertEquals(listOf(earlier, live.copy(restId = echo.id)), nextPage)
                assertEquals(nextPage, dedupeCachedMessages(listOf(echo) + nextPage))
            }
        }
    }

    @Test
    fun cachedPagesPreserveDisplayedKeyAndEnrichAliasInEitherArrivalOrder() {
        val live = tool("uuid", timestamp = 20L).copy(toolName = "terminal")
        val echo = tool("rest-s-20", timestamp = 20L)
        val earlier = tool("rest-s-10", timestamp = 10L)
        for ((first, second) in listOf(live to echo, echo to live)) {
            val merged = mergeCachedTranscriptPage(listOf(second), listOf(first))
            assertEquals(listOf(live.copy(id = first.id, restId = echo.id)), merged)
            val older = mergeCachedTranscriptPage(listOf(earlier), merged)
            assertEquals(listOf(earlier.id, first.id), older.map { it.id })
            assertEquals(older, mergeCachedTranscriptPage(listOf(echo, live), older))
        }
        assertEquals(
            listOf(earlier, live.copy(restId = echo.id)),
            mergeCachedTranscriptPage(listOf(earlier, live), listOf(live.copy(restId = echo.id))),
        )
        // A page-local dedup must also transfer its alias to an already displayed UUID.
        assertEquals(
            listOf(live.copy(restId = echo.id)),
            mergeCachedTranscriptPage(listOf(echo, live), listOf(live)),
        )
    }

    @Test
    fun exactAliasIsReservedBeforeUnconfirmedContentAndSurvivesContentChanges() {
        for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL)) {
            val content = if (role == MessageRole.TOOL) """{"output":"ok"}""" else "continue"
            val live = ChatMessage(id = "uuid", role = role, content = content, restId = "rest-s-20")
            val unconfirmed = live.copy(id = "uuid-next", restId = null)
            val older = live.copy(id = "rest-s-10", restId = null)
            val exact = live.copy(id = "rest-s-20", restId = null, content = "updated by server")
            val matches = matchTranscriptMessages(listOf(older, exact), listOf(live, unconfirmed))
            assertEquals(listOf(unconfirmed, live), matches)
            assertTrue(!sameLogicalMessage(older, live))
        }
    }

    @Test
    fun olderRowsUseAliasBoundariesEvenWhenLocalTimestampsDisagree() {
        val first = tool("uuid-first", timestamp = 100L).copy(restId = "rest-s-4")
        val last = tool("uuid-last", timestamp = 1L).copy(restId = "rest-s-10")
        val page = listOf(1, 5, 7).map { tool("rest-s-$it", timestamp = 50L) }

        val merged = mergeTranscriptWithLive(page, listOf(first, last), chronological = false, preserveLiveIds = true)

        assertEquals(listOf("rest-s-1", "uuid-first", "rest-s-5", "rest-s-7", "uuid-last"), merged.map { it.id })
    }

    private fun tool(
        id: String,
        callId: String = "",
        timestamp: Long = 1L,
    ) = ChatMessage(
        id = id,
        role = MessageRole.TOOL,
        content = """{"output":"ok","exit_code":0}""",
        toolCallId = callId,
        toolStatus = ToolStatus.COMPLETED,
        timestamp = timestamp,
    )

    @Test
    fun merge_oneRestEchoCannotConsumeTwoIdenticalLiveResults() {
        val first = tool("ws-first", timestamp = 1L)
        val second = tool("ws-second", timestamp = 2L)
        val echo = tool("rest-session-1", timestamp = 1L)

        val merged = mergeTranscriptWithLive(listOf(echo), listOf(first, second))

        assertEquals("A result echo may consume at most one live occurrence", 2, merged.size)
        assertTrue(merged.any { it.id == second.id })
    }

    @Test
    fun cache_oneLiveEchoCannotConsumeTwoPersistedResults() {
        val first = tool("rest-session-1", timestamp = 1L)
        val second = tool("rest-session-2", timestamp = 2L)
        val echo = tool("ws-first", timestamp = 1L)

        val cached = dedupeCachedMessages(listOf(first, echo, second))

        assertEquals(2, cached.size)
        assertTrue(cached.any { it.id == second.id })
    }

    @Test
    fun merge_identicalPayloadsWithDifferentCallIdsStayDistinct() {
        val first = tool("rest-session-1", callId = "call-first")
        val second = tool("ws-second", callId = "call-second")

        val merged = mergeTranscriptWithLive(listOf(first), listOf(second))

        assertEquals(setOf(first.id, second.id), merged.map { it.id }.toSet())
    }

    @Test
    fun merge_largeSettledHistoryRetainsEveryStableId() {
        val settled = (1..2_000).map { index -> tool("rest-session-$index", callId = "call-$index") }
        val overlappingPage = settled.takeLast(150)
        val live = tool("ws-new", callId = "call-new", timestamp = 2L)

        val merged = mergeTranscriptWithLive(overlappingPage, settled + live)

        assertEquals(2_001, merged.size)
        assertEquals((settled + live).map { it.id }.toSet(), merged.map { it.id }.toSet())
    }

    @Test
    fun olderOverlapUpdatesInPlaceWithoutMovingExistingRows() {
        val current = (1..10).map { tool("rest-s-$it", callId = "call-$it", timestamp = 1L) }
        val page = current.slice(4..5).map { it.copy(toolName = "terminal") }

        val merged = mergeTranscriptWithLive(page, current, chronological = false)

        assertEquals(current.map { it.id }, merged.map { it.id })
        assertEquals("terminal", merged[4].toolName)
        assertEquals("terminal", merged[5].toolName)
    }

    @Test
    fun olderDisjointAndGapRowsUseServerOrderWhenTimestampsAreEqual() {
        val current = listOf(4, 6, 10).map { tool("rest-s-$it", callId = "call-$it", timestamp = 1L) }
        val page = listOf(1, 3, 5, 6, 7).map { tool("rest-s-$it", callId = "call-$it", timestamp = 1L) }

        val merged = mergeTranscriptWithLive(page, current, chronological = false)

        assertEquals(listOf(1, 3, 4, 5, 6, 7, 10).map { "rest-s-$it" }, merged.map { it.id })
    }

    private fun serverTool(
        id: Int,
        callId: String = "",
    ) = com.m57.hermescontrol.data.model.SessionMessage(
        id = id,
        role = "tool",
        content = kotlinx.serialization.json.JsonPrimitive("""{"output":"ok","exit_code":0}"""),
        tool_call_id = callId,
    )

    @Test
    fun mapper_mixedRoomEchoPrefersRichWsToolOverExactRestId() {
        val live =
            tool("uuid-1", callId = "call-42").copy(
                toolName = "terminal",
                content = """{"name":"terminal","result":{"output":"ok","exit_code":0}}""",
            )
        val echo = tool("rest-s-42", callId = "call-42")

        val mapped = mapServerMessages("s", listOf(serverTool(42, "call-42")), 0, true, listOf(live, echo))

        assertEquals(listOf(live.copy(restId = "rest-s-42")), mapped)
    }

    @Test
    fun mapper_mixedEchoesConsumeIdenticalResultsOnlyOncePerOccurrence() {
        val first = tool("uuid-1").copy(toolName = "terminal")
        val second = tool("uuid-2").copy(toolName = "terminal")
        val cache = listOf(first, second, tool("rest-s-42"), tool("rest-s-43"))

        val mapped = mapServerMessages("s", listOf(serverTool(42), serverTool(43)), 0, true, cache)

        assertEquals(
            listOf(first.copy(restId = "rest-s-42"), second.copy(restId = "rest-s-43")),
            mapped,
        )
        assertEquals(2, mapped.map { it.id }.toSet().size)
    }

    @Test
    fun mapper_differentCallIdsCannotReuseIdenticalLiveResult() {
        val live = tool("uuid-1", callId = "call-41").copy(toolName = "terminal")
        val echo = tool("rest-s-42", callId = "call-42")

        val mapped = mapServerMessages("s", listOf(serverTool(42, "call-42")), 0, true, listOf(live, echo))

        assertEquals("rest-s-42", mapped.single().id)
        assertEquals("call-42", mapped.single().toolCallId)
        assertEquals(null, mapped.single().toolName)
    }
}
