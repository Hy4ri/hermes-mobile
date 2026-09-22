package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.model.SessionMessage
import com.m57.hermescontrol.ui.chat.fakes.FakeChatMessageDao
import com.m57.hermescontrol.ui.chat.fullbleed.fullBleedItemKeys
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import kotlinx.coroutines.test.runTest
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

    @Test
    fun cacheAndRestMergesKeepNumericOrderDespiteNonMonotonicTimestamps() {
        val rows =
            listOf(9, 10, 100, 200).mapIndexed { index, id ->
                ChatMessage(
                    id = "rest-session-$id",
                    role = MessageRole.ASSISTANT,
                    content = "answer-$id",
                    timestamp = listOf(900L, 100L, 800L, 200L)[index],
                )
            }
        for (cache in listOf(false, true)) {
            for (newestFirst in listOf(false, true)) {
                val first = if (newestFirst) rows.drop(2) else rows.take(2)
                val second = if (newestFirst) rows.take(2) else rows.drop(2)
                val merged =
                    if (cache) {
                        mergeCachedTranscriptPage(second, mergeCachedTranscriptPage(first, emptyList()))
                    } else {
                        mergeTranscriptWithLive(
                            second,
                            mergeTranscriptWithLive(first, emptyList(), preserveLiveIds = true),
                            chronological = !newestFirst,
                            preserveLiveIds = true,
                        )
                    }
                assertEquals("cache=$cache newestFirst=$newestFirst", rows.map { it.id }, merged.map { it.id })
            }
        }
    }

    @Test
    fun mapperAssignsOneLiveTraceOnlyToNewestEqualContentRestRow() {
        for (completion in listOf(null, "completion-200")) {
            val live =
                ChatMessage(
                    id = "uuid",
                    role = MessageRole.ASSISTANT,
                    content = "Done",
                    reasoningText = "newest trace",
                    completionId = completion,
                )
            val mapped =
                mapServerMessages(
                    "session",
                    listOf(assistantRow(100), assistantRow(200)),
                    0,
                    true,
                    listOf(live),
                )
            assertEquals(listOf("", "newest trace"), mapped.map { it.reasoningText })
            assertEquals(listOf(null, completion), mapped.map { it.completionId })
            val merged = mergeTranscriptWithLive(mapped, listOf(live), preserveLiveIds = true)
            assertEquals(listOf("rest-session-100", "uuid"), merged.map { it.id })
            assertEquals(listOf("", "newest trace"), merged.map { it.reasoningText })
            assertEquals("rest-session-200", merged.last().canonicalRestId)
        }
    }

    @Test
    fun mapperReservesCanonicalAndCompletionReasoningBeforeContentFallback() {
        val first =
            ChatMessage(
                id = "uuid-first",
                role = MessageRole.ASSISTANT,
                content = "Done",
                reasoningText = "first trace",
                completionId = "completion-100",
            )
        val second = first.copy(id = "uuid-second", reasoningText = "second trace", completionId = "completion-200")
        for (canonicalFirst in listOf(false, true)) {
            val live = listOf(if (canonicalFirst) first.copy(restId = "rest-session-100") else first, second)
            val mapped =
                mapServerMessages(
                    "session",
                    listOf(assistantRow(100), assistantRow(200)),
                    0,
                    true,
                    live,
                )
            assertEquals(listOf("first trace", "second trace"), mapped.map { it.reasoningText })
            assertEquals(listOf("completion-100", "completion-200"), mapped.map { it.completionId })
        }
    }

    @Test
    fun mapperUsesNewestReasoningCandidateWhenNoCompletionIdentityExists() {
        val first =
            ChatMessage(id = "uuid-first", role = MessageRole.ASSISTANT, content = "Done", reasoningText = "old")
        val second = first.copy(id = "uuid-second", reasoningText = "new")
        val mapped = mapServerMessages("session", listOf(assistantRow(200)), 0, true, listOf(first, second))
        assertEquals("new", mapped.single().reasoningText)
    }

    @Test
    fun olderPagesCannotStealLiveReasoningInEitherArrivalOrder() {
        val live =
            ChatMessage(
                id = "uuid",
                role = MessageRole.ASSISTANT,
                content = "Done",
                reasoningText = "newest trace",
                completionId = "completion-200",
                timestamp = 200_000L,
            )
        for (newestFirst in listOf(false, true)) {
            val order = if (newestFirst) listOf(200, 100) else listOf(100, 200)
            var current = listOf(live)
            order.forEach { id ->
                current = applyServerPage(current, listOf(assistantRow(id)), older = id == 100)
                assertEquals("A trace may appear only once after page $id", 1, current.count { it.reasoningText != "" })
                if (id ==
                    100
                ) {
                    assertEquals("", current.single { it.canonicalRestId == "rest-session-100" }.reasoningText)
                }
            }
            assertEquals(listOf("rest-session-100", "uuid"), current.map { it.id })
            assertEquals(listOf("", "newest trace"), current.map { it.reasoningText })
            assertEquals(listOf(null, "completion-200"), current.map { it.completionId })
            assertEquals("rest-session-200", current.last().canonicalRestId)
        }
        // Older paging also forbids text-only reasoning fallback without a completion id.
        val older =
            mapServerMessages(
                "session",
                listOf(assistantRow(100)),
                150,
                true,
                listOf(live.copy(completionId = null)),
                isPagingOlder = true,
            )
        assertEquals("", older.single().reasoningText)
    }

    @Test
    fun reasoningOnlyBoundaryRetainsCanonicalRowBeforeAnswerArrives() {
        val previous = assistantRow(90, content = "Previous answer")
        val reasoning = assistantRow(100, content = "", reasoning = "boundary trace")
        val mapped = mapServerMessages("session", listOf(previous, reasoning), 0, true, emptyList())
        assertEquals(listOf("rest-session-90", "rest-session-100"), mapped.map { it.canonicalRestId })
        assertEquals("", mapped.first().reasoningText)
        assertEquals("boundary trace", mapped.last().reasoningText)
        assertEquals("", mapped.last().content)
    }

    @Test
    fun splitReasoningAndAnswerSurviveBothArrivalOrdersRefreshAndPagedCacheReload() =
        runTest {
            val reasoning = assistantRow(100, content = "", reasoning = "boundary trace")
            val answer = assistantRow(200)
            for (answerFirst in listOf(false, true)) {
                val dao = FakeChatMessageDao()
                val repository = ChatPersistenceRepository(dao)
                var current = emptyList<ChatMessage>()
                val pages = if (answerFirst) listOf(answer, reasoning) else listOf(reasoning, answer)
                pages.forEach { row ->
                    val mapped =
                        mapServerMessages(
                            "session",
                            listOf(row),
                            0,
                            true,
                            current,
                            isPagingOlder = answerFirst && row.id == 100,
                        )
                    current =
                        mergeTranscriptWithLive(
                            mapped,
                            current,
                            chronological = !(answerFirst && row.id == 100),
                            preserveLiveIds = true,
                        )
                    // Same input that persistHistoryPage writes, including the boundary page before its neighbor.
                    repository.persistMessages(
                        mapped.map { it.copy(id = requireNotNull(it.canonicalRestId)) },
                        "session",
                    )
                }
                assertBoundaryTranscript(current)
                val refreshed = applyServerPage(current, listOf(reasoning, answer))
                assertEquals(current, refreshed)
                val latest = repository.loadPage("session", null, 1)
                val older = repository.loadPage("session", latest.cursor, 1)
                assertEquals(true, latest.hasOlder)
                assertEquals(false, older.hasOlder)
                for ((first, second) in listOf(latest.messages to older.messages, older.messages to latest.messages)) {
                    assertBoundaryTranscript(
                        mergeCachedTranscriptPage(second, mergeCachedTranscriptPage(first, emptyList())),
                    )
                }
                assertEquals(0, dao.fullSessionReads)
                assertEquals(listOf(2, 2), dao.pageLimits)
            }
        }

    @Test
    fun reasoningDoesNotCrossUserOrToolBoundaryInEitherArrivalOrder() {
        for (role in listOf("user", "tool")) {
            val reasoning = assistantRow(100, content = "", reasoning = "before $role")
            val boundary =
                SessionMessage(
                    id = 150,
                    role = role,
                    content = JsonPrimitive("boundary"),
                    timestamp = JsonPrimitive(150),
                    tool_call_id = if (role == "tool") "call-150" else null,
                )
            val answer = assistantRow(200)
            for (newestFirst in listOf(false, true)) {
                val first = if (newestFirst) listOf(boundary, answer) else listOf(reasoning)
                val second = if (newestFirst) listOf(reasoning) else listOf(boundary, answer)
                val merged = applyServerPage(applyServerPage(emptyList(), first), second, older = newestFirst)
                assertEquals(
                    listOf(100, 150, 200).map { "rest-session-$it" },
                    merged.map { it.canonicalRestId },
                )
                assertEquals(listOf("before $role", "", ""), merged.map { it.reasoningText })
                // Mapping all rows together must have the same ownership as separate pages.
                assertEquals(merged, applyServerPage(merged, listOf(reasoning, boundary, answer)))
            }
        }
    }

    @Test
    fun multipleReasoningOnlyRowsRemainVisibleAcrossToolSteps() {
        val rows =
            listOf(
                assistantRow(100, content = "", reasoning = "first trace"),
                SessionMessage(
                    id = 150,
                    role = "tool",
                    content = JsonPrimitive("result"),
                    timestamp = JsonPrimitive(150),
                ),
                assistantRow(200, content = "", reasoning = "second trace"),
                assistantRow(300),
            )
        val mapped = applyServerPage(emptyList(), rows)
        assertEquals(listOf("first trace", "second trace"), mapped.map { it.reasoningText }.filter { it.isNotBlank() })
        assertEquals(
            listOf("reasoning-rest-session-100", "reasoning-rest-session-200"),
            fullBleedItemKeys(groupIntoTurns(mapped)).filter { it.startsWith("reasoning-") },
        )
        assertEquals(1, fullBleedItemKeys(groupIntoTurns(mapped)).count { it.startsWith("prose-") })
    }

    @Test
    fun canonicalReasoningAndMatchingLiveAnswerRenderTraceOnlyOnceInEitherArrivalOrder() {
        val live =
            ChatMessage(
                id = "uuid-answer",
                role = MessageRole.ASSISTANT,
                content = "Done",
                reasoningText = "boundary trace",
                completionId = "completion-200",
                timestamp = 200_000L,
            )
        val reasoning = assistantRow(100, content = "", reasoning = "boundary trace")
        val answer = assistantRow(200)
        for (answerFirst in listOf(false, true)) {
            val pages = if (answerFirst) listOf(answer, reasoning) else listOf(reasoning, answer)
            var current = listOf(live)
            pages.forEach { row ->
                current = applyServerPage(current, listOf(row), older = row.id == 100)
            }
            assertEquals(listOf("rest-session-100", "uuid-answer"), current.map { it.id })
            assertEquals(listOf("boundary trace", ""), current.map { it.reasoningText })
            assertEquals("completion-200", current.last().completionId)
            assertEquals("rest-session-200", current.last().canonicalRestId)
            assertEquals(
                listOf("reasoning-rest-session-100", "prose-uuid-answer"),
                fullBleedItemKeys(groupIntoTurns(current)),
            )
        }
    }

    @Test
    fun differentReasoningOnlyRowsCannotMatchByEmptyContent() {
        val live =
            ChatMessage(
                id = "uuid-thinking",
                role = MessageRole.ASSISTANT,
                content = "",
                reasoningText = "new trace",
            )
        val canonical = live.copy(id = "rest-session-100", reasoningText = "old trace")
        assertTrue(!sameLogicalMessage(canonical, live))
        assertEquals(2, mergeTranscriptWithLive(listOf(canonical), listOf(live), preserveLiveIds = true).size)
        assertTrue(sameLogicalMessage(canonical.copy(reasoningText = "new trace"), live))
    }

    private fun assertBoundaryTranscript(messages: List<ChatMessage>) {
        assertEquals(listOf("rest-session-100", "rest-session-200"), messages.map { it.canonicalRestId })
        assertEquals(listOf("boundary trace", ""), messages.map { it.reasoningText })
        assertEquals(listOf("", "Done"), messages.map { it.content })
        assertEquals(
            listOf("reasoning-rest-session-100", "prose-rest-session-200"),
            fullBleedItemKeys(groupIntoTurns(messages)),
        )
    }

    private fun assistantRow(
        id: Int,
        content: String = "Done",
        reasoning: String = "",
    ) = SessionMessage(
        id = id,
        role = "assistant",
        content = JsonPrimitive(content),
        reasoning = JsonPrimitive(reasoning),
        timestamp = JsonPrimitive(id),
    )

    private fun applyServerPage(
        current: List<ChatMessage>,
        rows: List<SessionMessage>,
        older: Boolean = false,
    ): List<ChatMessage> =
        mergeTranscriptWithLive(
            mapServerMessages("session", rows, 0, true, current, isPagingOlder = older),
            current,
            chronological = !older,
            preserveLiveIds = true,
        )
}
