package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.data.ws.WsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCachedHistoryTest {
    private val historicalTool =
        ChatMessage(
            id = "cached-tool",
            role = MessageRole.TOOL,
            content = "{}",
            toolName = "terminal",
            toolCallId = "call-old",
            toolStatus = ToolStatus.RUNNING,
            isHistoricalCache = true,
        )

    @Test
    fun exactToolIdPromotesOnlyItsHistoricalIdentity() {
        val other = historicalTool.copy(id = "other", toolCallId = "call-other")
        val state = ChatUiState(currentSessionId = "s", messages = listOf(historicalTool, other))
        val events =
            listOf(
                WsEvent.ToolProgress(name = "terminal", preview = "working", sessionId = "s", toolId = "call-old"),
                WsEvent.ToolGenerating(name = "terminal", sessionId = "s", toolId = "call-old"),
                WsEvent.ToolComplete(name = "terminal", data = mapOf("tool_id" to "call-old"), sessionId = "s"),
            )
        events.forEach { event ->
            val result = ChatWsEventReducer.reduce(state, StreamingState(), event, "s")
            assertFalse(
                "$event must supersede historical provenance",
                result.state.messages
                    .first()
                    .isHistoricalCache,
            )
            assertTrue(
                result.state.messages
                    .last()
                    .isHistoricalCache,
            )
        }
    }

    @Test
    fun toolProgressWithNonmatchingExplicitIdDoesNotPromoteHistoricalSameNameCall() {
        val state = ChatUiState(currentSessionId = "s", messages = listOf(historicalTool))

        val result =
            ChatWsEventReducer.reduce(
                state,
                StreamingState(),
                WsEvent.ToolProgress(name = "terminal", preview = "new call", sessionId = "s", toolId = "call-new"),
                "s",
            )

        assertEquals(listOf(historicalTool), result.state.messages)
    }

    @Test
    fun toolCompleteWithNonmatchingExplicitIdDoesNotOverwriteHistoricalSameNameCall() {
        val state = ChatUiState(currentSessionId = "s", messages = listOf(historicalTool))

        val result =
            ChatWsEventReducer.reduce(
                state,
                StreamingState(),
                WsEvent.ToolComplete(
                    name = "terminal",
                    data = mapOf("tool_id" to "call-new", "output" to "new call"),
                    sessionId = "s",
                ),
                "s",
            )

        assertEquals(listOf(historicalTool), result.state.messages)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun legacyIdlessToolEventsStillPromoteHistoricalSameNameCall() {
        val state = ChatUiState(currentSessionId = "s", messages = listOf(historicalTool))
        val events =
            listOf(
                WsEvent.ToolProgress(name = "terminal", preview = "legacy", sessionId = "s"),
                WsEvent.ToolComplete(name = "terminal", data = mapOf("output" to "legacy"), sessionId = "s"),
            )

        events.forEach { event ->
            val result = ChatWsEventReducer.reduce(state, StreamingState(), event, "s")
            assertFalse(
                "$event must retain the legacy name fallback",
                result.state.messages
                    .single()
                    .isHistoricalCache,
            )
        }
    }

    @Test
    fun lateCacheCannotDemoteAnObservedLiveIdentity() {
        val live = historicalTool.copy(isHistoricalCache = false, progressPreview = "new progress")
        val merged = mergeCachedTranscriptPage(listOf(historicalTool), listOf(live))
        assertEquals(listOf(live), merged)
    }

    @Test
    fun cachedSessionStartMarkerStaysFirstBeforeHistoricalUuidAndCanonicalRestWindow() {
        val start =
            ChatMessage(
                id = "cached-session-start",
                role = MessageRole.SYSTEM,
                content = "Session created",
                isHistoricalCache = true,
            )
        val historicalUser =
            ChatMessage(
                id = "5df43b6f-98ea-46db-9fc4-d1e4cf11d43d",
                role = MessageRole.USER,
                content = "older cached prompt",
                isHistoricalCache = true,
            )
        val canonical = ChatMessage(id = "rest-s-0", role = MessageRole.ASSISTANT, content = "canonical reply")

        val merged = mergeTranscriptWithLive(listOf(canonical), listOf(start, historicalUser))

        assertEquals(listOf(start.id, historicalUser.id, canonical.id), merged.map { it.id })
    }

    @Test
    fun restoredRepeatedUsersKeepBothOccurrencesUntilEachCanonicalPageConfirmsOne() {
        val first =
            ChatMessage(
                id = "restored-first",
                role = MessageRole.USER,
                content = "continue",
                localOrder = 1,
                isRestoredUnconfirmed = true,
            )
        val second = first.copy(id = "restored-second", localOrder = 2)
        val latest = ChatMessage(id = "rest-s-100", role = MessageRole.ASSISTANT, content = "Latest answer")
        val restored = mergeCachedTranscriptPage(listOf(first, second), listOf(latest))
        assertEquals(listOf(first.id, second.id, latest.id), restored.map { it.id })
        assertTrue(restored.take(2).all { it.canonicalRestId == null })

        val older = ChatMessage(id = "rest-s-20", role = MessageRole.USER, content = "continue")
        val once = mergeTranscriptWithLive(listOf(older), restored, chronological = false)
        assertEquals(2, once.count { it.role == MessageRole.USER })
        assertEquals(1, once.count { it.canonicalRestId == older.id })
        val confirmed = once.single { it.canonicalRestId == older.id }
        assertFalse(confirmed.isRestoredUnconfirmed)
        val remaining = once.single { it.role == MessageRole.USER && it.canonicalRestId == null }
        assertTrue(remaining.isRestoredUnconfirmed)
        assertEquals(MessageProvenance.UNKNOWN, remaining.messageProvenance)

        val twice = mergeTranscriptWithLive(listOf(older.copy(id = "rest-s-21")), once, chronological = false)
        assertEquals(listOf(first.id, second.id, latest.id), twice.map { it.id })
        assertEquals(listOf("rest-s-20", "rest-s-21"), twice.take(2).map { it.canonicalRestId })
        assertTrue(twice.none { it.isRestoredUnconfirmed })
    }

    @Test
    fun historicalRowsPrecedeCanonicalWindowWithoutChangingLiveTailOrAttachments() {
        val attachment = Attachment("content://test/file", "file.txt", "text/plain")
        val old =
            ChatMessage(
                id = "cached-user",
                role = MessageRole.USER,
                content = "old",
                timestamp = Long.MAX_VALUE,
                attachments = listOf(attachment),
                isHistoricalCache = true,
            )
        val pending = old.copy(id = "pending", content = "pending", isHistoricalCache = false)
        val recent = ChatMessage(id = "rest-s-100", role = MessageRole.ASSISTANT, content = "recent", timestamp = 1)
        val live = historicalTool.copy(id = "live-tool", toolCallId = "call-live", isHistoricalCache = false)
        val resume = ChatMessage(id = "resume", role = MessageRole.SYSTEM, content = "Session resumed")
        val merged = mergeTranscriptWithLive(listOf(recent), listOf(old, historicalTool, pending, live, resume))
        assertEquals(listOf(old.id, historicalTool.id, recent.id, pending.id, live.id, resume.id), merged.map { it.id })
        assertEquals(listOf(attachment), merged.first().attachments)
        assertEquals(listOf(attachment), merged.single { it.id == pending.id }.attachments)
        assertEquals(merged, mergeTranscriptWithLive(listOf(recent), merged))
    }
}
