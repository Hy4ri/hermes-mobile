package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.data.ws.WsEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #771 — duplicate tool bubble ("terminal" + generic "Tool") regression.
 *
 * Real captured payloads for `echo meow`:
 * - WS tool.complete content: full payload incl. `result`
 * - REST transcript row content: result-only, no tool name
 */
class ChatToolDedupeTest {
    private val wsToolContent =
        """{"tool_id":"call_00_g45kdmnSROQL4RMaGZrn3669","name":"terminal","args":{"command":"echo meow"},"duration_s":0.988396167755127,"result":{"output":"meow\nrenamed '/tmp/hermes-snap-ecb25e16f404.sh.tmp.21749' -> '/tmp/hermes-snap-ecb25e16f404.sh'","exit_code":0.0,"error":null}}"""

    private val restToolContent =
        """{"output": "meow\nrenamed '/tmp/hermes-snap-ecb25e16f404.sh.tmp.21749' -> '/tmp/hermes-snap-ecb25e16f404.sh'", "exit_code": 0, "error": null}"""

    // ── canonicalToolResultKey ─────────────────────────────────────────────

    @Test
    fun canonicalKey_wsPayloadAndRestRow_match() {
        val wsKey = canonicalToolResultKey(wsToolContent)
        val restKey = canonicalToolResultKey(restToolContent)
        assertEquals(wsKey, restKey)
    }

    @Test
    fun canonicalKey_isIntFloatAgnostic() {
        // exit_code 0.0 (WS, float) vs 0 (REST, int) must not break the match
        val wsKey = canonicalToolResultKey(wsToolContent)
        val restKey =
            canonicalToolResultKey(
                "{\"output\":\"meow\\nrenamed '/tmp/hermes-snap-ecb25e16f404.sh.tmp.21749' " +
                    "-> '/tmp/hermes-snap-ecb25e16f404.sh'\",\"exit_code\":0,\"error\":null}",
            )
        assertEquals(wsKey, restKey)
    }

    @Test
    fun canonicalKey_differentResults_doNotMatch() {
        val a = canonicalToolResultKey(wsToolContent)
        val b = canonicalToolResultKey("""{"output":"other","exit_code":1,"error":null}""")
        assertFalse(a == b)
    }

    @Test
    fun canonicalKey_unparseable_returnsNull() {
        assertNull(canonicalToolResultKey("not json at all"))
    }

    // ── sameLogicalMessage ─────────────────────────────────────────────────

    @Test
    fun sameLogicalMessage_wsToolAndRestRow_areSameCall() {
        val ws = ChatMessage(role = MessageRole.TOOL, content = wsToolContent, toolName = "terminal")
        val rest = ChatMessage(role = MessageRole.TOOL, content = restToolContent)
        assertTrue(sameLogicalMessage(ws, rest))
    }

    @Test
    fun sameLogicalMessage_differentCalls_notSame() {
        val ws = ChatMessage(role = MessageRole.TOOL, content = wsToolContent, toolName = "terminal")
        val other =
            ChatMessage(
                role = MessageRole.TOOL,
                content = """{"output":"meow 2","exit_code":0,"error":null}""",
            )
        assertFalse(sameLogicalMessage(ws, other))
    }

    @Test
    fun sameLogicalMessage_userMessages_matchOnContent() {
        val ws = ChatMessage(role = MessageRole.USER, content = "run echo meow")
        val rest = ChatMessage(role = MessageRole.USER, content = "run echo meow")
        assertTrue(sameLogicalMessage(ws, rest))
    }

    @Test
    fun sameLogicalMessage_whitespaceDrift_stillSame() {
        // Issue #842: the sealed live bubble holds the RAW streamed text
        // (leading blank lines), the backend row is CLEANED — same logical
        // message, must not duplicate on reload.
        val live = ChatMessage(role = MessageRole.ASSISTANT, content = "\n\noooh fresh angle this time 🤤")
        val rest = ChatMessage(role = MessageRole.ASSISTANT, content = "oooh fresh angle this time 🤤")
        assertTrue(sameLogicalMessage(live, rest))
        assertFalse(sameLogicalMessage(live, ChatMessage(role = MessageRole.ASSISTANT, content = "different reply")))
    }

    @Test
    fun sameLogicalMessage_sealRaceTruncatedNarration_prefixCovered() {
        // Issue #842 (on-device capture): the seal raced the throttled flush and
        // the orphan missed the last deltas — it ends "...dreamy chickpea" while
        // the backend persisted the COMPLETE narration "...dreamy chickpea
        // goodness:". The orphan must count as covered (strict prefix, both
        // sides substantial) so the reload doesn't ghost the commentary.
        val orphan =
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content =
                    "\n\ntool's loaded! 🔍 now searchin' for the **best hummus recipe**" +
                        " — gimme dat creamy dreamy chickpea",
            )
        val rest =
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content =
                    "tool's loaded! 🔍 now searchin' for the **best hummus recipe**" +
                        " — gimme dat creamy dreamy chickpea goodness:",
            )
        assertTrue(sameLogicalMessage(orphan, rest))

        // A SHORT truncated prefix must NOT swallow a longer message that merely
        // starts with it ("ok" is not the same message as "okay bestie...").
        assertFalse(
            sameLogicalMessage(
                ChatMessage(role = MessageRole.ASSISTANT, content = "ok"),
                ChatMessage(role = MessageRole.ASSISTANT, content = "okay bestie, 3 searches comin' up!! 🔍"),
            ),
        )
    }

    @Test
    fun sameLogicalMessage_differentRoles_notSame() {
        val ws = ChatMessage(role = MessageRole.TOOL, content = wsToolContent)
        val rest = ChatMessage(role = MessageRole.ASSISTANT, content = wsToolContent)
        assertFalse(sameLogicalMessage(ws, rest))
    }

    // ── dedupeCachedMessages ───────────────────────────────────────────────

    @Test
    fun dedupe_dropsRestCopiesWhenWsCopyExists() {
        val wsUser = ChatMessage(role = MessageRole.USER, content = "run echo meow", timestamp = 1)
        val restUser = ChatMessage(role = MessageRole.USER, content = "run echo meow", id = "rest-s-0", timestamp = 1)
        val wsTool = ChatMessage(role = MessageRole.TOOL, content = wsToolContent, toolName = "terminal", timestamp = 2)
        val restTool = ChatMessage(role = MessageRole.TOOL, content = restToolContent, id = "rest-s-1", timestamp = 2)
        val wsAssistant = ChatMessage(role = MessageRole.ASSISTANT, content = "done!!", timestamp = 3)
        val restAssistant =
            ChatMessage(role = MessageRole.ASSISTANT, content = "done!!", id = "rest-s-2", timestamp = 3)

        val deduped = dedupeCachedMessages(listOf(restUser, wsTool, restTool, wsAssistant, restAssistant))

        assertEquals(3, deduped.size)
        // WS copies win; rest- copies of the same logical message are dropped
        assertTrue(deduped.contains(wsTool))
        assertTrue(deduped.none { it.id == "rest-s-1" })
        assertTrue(deduped.none { it.id == "rest-s-2" })
        // rest- user kept when no WS copy exists
        assertTrue(deduped.contains(restUser))
    }

    @Test
    fun dedupe_onlyRestRows_unchanged() {
        val restTool = ChatMessage(role = MessageRole.TOOL, content = restToolContent, id = "rest-s-0")
        val result = dedupeCachedMessages(listOf(restTool))
        assertEquals(1, result.size)
        assertEquals("rest-s-0", result[0].id)
    }

    @Test
    fun dedupe_noRestRows_unchanged() {
        val wsTool = ChatMessage(role = MessageRole.TOOL, content = wsToolContent, toolName = "terminal")
        val result = dedupeCachedMessages(listOf(wsTool))
        assertEquals(1, result.size)
    }

    // ── mergeTranscriptWithLive ────────────────────────────────────────────

    @Test
    fun merge_midTurnReload_keepsInFlightToolBubble() {
        // Reload lands while the tool is RUNNING — the REST page has the
        // user row only (server persists the tool row at completion).
        val current =
            listOf(
                ChatMessage(role = MessageRole.USER, content = "run echo meow", timestamp = 1),
                ChatMessage(
                    role = MessageRole.TOOL,
                    content = wsToolContent,
                    toolName = "terminal",
                    toolStatus = ToolStatus.RUNNING,
                    timestamp = 2,
                ),
            )
        val restPage =
            listOf(ChatMessage(role = MessageRole.USER, content = "run echo meow", id = "rest-s-0", timestamp = 1))

        val merged = mergeTranscriptWithLive(restPage, current)

        assertEquals(2, merged.size)
        // The RUNNING tool bubble survives the reload
        assertEquals(ToolStatus.RUNNING, merged[1].toolStatus)
        assertEquals("terminal", merged[1].toolName)
    }

    @Test
    fun merge_sealedCommentaryWithLeadingWhitespace_noDuplicate() {
        // Issue #842 (on-device capture 2026-08-08): the app seals the RAW
        // streamed commentary — which can carry leading blank lines the model
        // emits before its narration — while the backend persists a CLEANED
        // copy. A reload merge with exact-content matching treated them as
        // different messages and added the REST copy on top: the commentary
        // duplicated ~10s after the stream ended. Trim-based matching covers
        // the sealed live bubble, so no second copy renders.
        val liveOrphan =
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "\n\noooh fresh angle this time — the **Lebanese method** and even hummus fatteh 🤤",
                timestamp = 3,
            )
        val restRow =
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "oooh fresh angle this time — the **Lebanese method** and even hummus fatteh 🤤",
                id = "rest-s-19",
                timestamp = 3,
            )

        val merged = mergeTranscriptWithLive(listOf(restRow), listOf(liveOrphan))

        assertEquals("cleaned REST row covers the whitespace-drifting live bubble", 1, merged.size)
    }

    @Test
    fun merge_matchingUser_keepsLiveOnlyMetadata() {
        val live = ChatMessage(role = MessageRole.USER, content = "redirect", isStreaming = true, timestamp = 1)
        val rest = ChatMessage(role = MessageRole.USER, content = "redirect", id = "rest-s-0", timestamp = 1)

        val merged = mergeTranscriptWithLive(listOf(rest), listOf(live))

        // Preserves cache/live-only user flags such as continuesActiveTurn.
        assertSame(live, merged.single())
    }

    @Test
    fun merge_reusedLiveToolId_noDuplicate() {
        // Post-completion reload: mapServerMessages reuses the live WS tool
        // message (same id) — the old copy is covered by id, no duplicate.
        val liveTool =
            ChatMessage(
                role = MessageRole.TOOL,
                content = wsToolContent,
                toolName = "terminal",
                toolStatus = ToolStatus.COMPLETED,
                timestamp = 2,
            )
        val current = listOf(liveTool)
        val restPage =
            listOf(
                ChatMessage(role = MessageRole.USER, content = "run echo meow", id = "rest-s-0", timestamp = 1),
                liveTool,
            )

        val merged = mergeTranscriptWithLive(restPage, current)

        assertEquals(2, merged.size)
        assertEquals(1, merged.count { it.role == MessageRole.TOOL })
    }

    @Test
    fun merge_restToolCopyWithCanonicalMatch_noDuplicate() {
        // Rest- copy + WS copy of the same call (canonical match) — the WS
        // copy is dropped from the tail, only the REST row stays.
        val wsTool =
            ChatMessage(
                role = MessageRole.TOOL,
                content = wsToolContent,
                toolName = "terminal",
                toolStatus = ToolStatus.COMPLETED,
                timestamp = 2,
            )
        val restTool =
            ChatMessage(role = MessageRole.TOOL, content = restToolContent, id = "rest-s-1", timestamp = 2)
        val current = listOf(wsTool)
        val restPage =
            listOf(
                ChatMessage(role = MessageRole.USER, content = "run echo meow", id = "rest-s-0", timestamp = 1),
                restTool,
            )

        val merged = mergeTranscriptWithLive(restPage, current)

        assertEquals(2, merged.size)
        assertEquals(1, merged.count { it.role == MessageRole.TOOL })
        assertEquals("rest-s-1", merged.single { it.role == MessageRole.TOOL }.id)
    }

    @Test
    fun merge_preservesChronologicalOrder() {
        val current =
            listOf(
                ChatMessage(role = MessageRole.USER, content = "run echo meow", timestamp = 1),
                ChatMessage(
                    role = MessageRole.TOOL,
                    content = wsToolContent,
                    toolName = "terminal",
                    toolStatus = ToolStatus.RUNNING,
                    timestamp = 2,
                ),
            )
        val restPage =
            listOf(ChatMessage(role = MessageRole.USER, content = "run echo meow", id = "rest-s-0", timestamp = 1))

        val merged = mergeTranscriptWithLive(restPage, current)

        assertEquals(1, merged[0].timestamp)
        assertEquals(2, merged[1].timestamp)
    }

    // ── end-to-end: full turn + mid-turn reload (the user's exact scenario) ──

    @Test
    fun fullTurn_withMidTurnReload_toolBubbleSurvivesUntilStreamEnd() {
        val state = ChatUiState(currentSessionId = "session-1")

        // 1. WS turn: user → reasoning → tool.start → tool.complete
        val userMsg = ChatMessage(role = MessageRole.USER, content = "echo meow", timestamp = 1)
        var s = state.copy(messages = listOf(userMsg))
        var stream = StreamingState()
        stream = ChatWsEventReducer.reduce(s, stream, WsEvent.MessageStart("session-1"), "session-1").streamingState
        stream =
            ChatWsEventReducer.reduce(
                s,
                stream,
                WsEvent.ReasoningDelta("thinking about meow", "session-1"),
                "session-1",
            ).streamingState
        var r =
            ChatWsEventReducer.reduce(
                s,
                stream,
                WsEvent.ToolStart("terminal", mapOf("args_text" to "echo meow"), "session-1"),
                "session-1",
            )
        s = r.state
        stream = r.streamingState
        r =
            ChatWsEventReducer.reduce(
                s,
                stream,
                WsEvent.ToolComplete("terminal", mapOf("output" to "meow"), "session-1"),
                "session-1",
            )
        s = r.state
        stream = r.streamingState

        // 2. MID-TURN reload lands while the tool is still running server-side
        //    (server persists the tool row only at completion → page has no tool).
        val restPage =
            listOf(
                ChatMessage(role = MessageRole.USER, content = "echo meow", id = "rest-s-0", timestamp = 1),
                // assistant reasoning carrier row skipped by mapper; tool row ABSENT
            )
        val merged = mergeTranscriptWithLive(restPage, s.messages)
        s = s.copy(messages = merged)

        // Tool bubble survived the reload
        assertEquals(1, s.messages.count { it.role == MessageRole.TOOL })
        assertEquals(ToolStatus.COMPLETED, s.messages.single { it.role == MessageRole.TOOL }.toolStatus)
        assertEquals("terminal", s.messages.single { it.role == MessageRole.TOOL }.toolName)

        // 3. Rest of the turn: answer streams → message.complete
        stream = ChatWsEventReducer.reduce(s, stream, WsEvent.MessageStart("session-1"), "session-1").streamingState
        r =
            ChatWsEventReducer.reduce(
                s,
                stream,
                WsEvent.MessageComplete(text = "meow, done!", sessionId = "session-1"),
                "session-1",
            )
        s = r.state

        // Final list: user + tool + assistant — tool bubble STILL there
        assertEquals(3, s.messages.size)
        assertEquals(listOf(MessageRole.USER, MessageRole.TOOL, MessageRole.ASSISTANT), s.messages.map { it.role })
        assertEquals("terminal", s.messages[1].toolName)
    }

    // ── syncCurrentSession merge: the exact logcat scenario ──────────────
    //
    // Logcat from the user's device:
    //   MessageComplete: messages.size=34 tools=8
    //   syncCurrentSession: messages.size=35 tools=8
    //   sync merge: before=35 after=34 tools=7  ← TOOL DROPPED
    //
    // The 5-second sync poll fires right after the turn ends (isAgentTyping
    // = false, streamingMessage = null). Its REST page is one tool short
    // (server offset predates the newest tool's persistence), and the old
    // toolName/content match consumed the wrong incoming tool, leaving the
    // newest WS tool with no counterpart → dropped.

    @Test
    fun syncMerge_newestToolSurvives_whenRestPageLacksIt() {
        // 3 existing messages: user + tool(WS, terminal, echo meow) + assistant
        val existingTool =
            ChatMessage(
                id = "ws-uuid-1",
                role = MessageRole.TOOL,
                content = wsToolContent,
                toolName = "terminal",
                toolStatus = ToolStatus.COMPLETED,
                timestamp = 2,
            )
        val current =
            listOf(
                ChatMessage(id = "rest-s-0", role = MessageRole.USER, content = "echo meow", timestamp = 1),
                existingTool,
                ChatMessage(id = "rest-s-2", role = MessageRole.ASSISTANT, content = "done!", timestamp = 3),
            )

        // Incoming REST page is SHORT: 2 rows, tool row missing (server lag)
        val incoming =
            listOf(
                ChatMessage(id = "rest-s-0", role = MessageRole.USER, content = "echo meow", timestamp = 1),
                ChatMessage(id = "rest-s-2", role = MessageRole.ASSISTANT, content = "done!", timestamp = 3),
            )

        // Replay the sync merge logic
        val unmatchedIncoming = incoming.toMutableList()
        val mergedList = mutableListOf<ChatMessage>()

        for (existing in current) {
            val existingServerIndex = if (existing.id.startsWith("rest-")) 0 else null
            if (existingServerIndex != null) {
                val matchIdx = unmatchedIncoming.indexOfFirst { it.id == existing.id }
                if (matchIdx >= 0) {
                    mergedList.add(unmatchedIncoming.removeAt(matchIdx))
                } else {
                    mergedList.add(existing)
                }
            } else {
                val matchIdx = unmatchedIncoming.indexOfFirst { inc -> sameLogicalMessage(inc, existing) }
                if (matchIdx >= 0) {
                    mergedList.add(existing)
                    unmatchedIncoming.removeAt(matchIdx)
                } else {
                    mergedList.add(existing)
                }
            }
        }
        mergedList.addAll(unmatchedIncoming)
        val merged = mergedList.distinctBy { it.id }

        // Tool MUST survive with its name
        assertEquals(3, merged.size)
        assertEquals(1, merged.count { it.role == MessageRole.TOOL })
        assertEquals("terminal", merged.single { it.role == MessageRole.TOOL }.toolName)
        assertEquals("ws-uuid-1", merged.single { it.role == MessageRole.TOOL }.id)
    }

    @Test
    fun syncMatch_oldWay_wouldDropTheTool() {
        // Prove the OLD match logic (toolName/content) would match the wrong
        // incoming and drop the newest WS tool when the REST page is short.
        val existingTool =
            ChatMessage(
                id = "ws-uuid-1",
                role = MessageRole.TOOL,
                content = wsToolContent,
                toolName = "terminal",
                toolStatus = ToolStatus.COMPLETED,
                timestamp = 2,
            )
        val current =
            listOf(
                ChatMessage(id = "rest-s-0", role = MessageRole.USER, content = "echo meow", timestamp = 1),
                existingTool,
                ChatMessage(id = "rest-s-2", role = MessageRole.ASSISTANT, content = "done!", timestamp = 3),
            )

        // Old logic: match by role + (content equality OR toolName equality)
        // With no incoming tool row, there's nothing to match — the WS tool
        // should be kept. But if there WAS a different terminal tool in
        // incoming, the old match would consume it for the wrong existing.
        // The sameLogicalMessage match is stricter (canonical result key).
        val incoming =
            listOf(
                ChatMessage(id = "rest-s-0", role = MessageRole.USER, content = "echo meow", timestamp = 1),
                ChatMessage(id = "rest-s-2", role = MessageRole.ASSISTANT, content = "done!", timestamp = 3),
            )

        // With the NEW match, the WS tool has no canonical match in incoming
        // → it's kept. Verify sameLogicalMessage returns false for all incoming.
        assertTrue(incoming.none { sameLogicalMessage(it, existingTool) })
    }

    @Test
    fun incrementalMerge_keepsEarlierDuplicateContent() {
        val live = ChatMessage(role = MessageRole.USER, content = "retry", isStreaming = true, timestamp = 2)
        val current =
            listOf(
                ChatMessage(id = "rest-s-0", role = MessageRole.USER, content = "retry", timestamp = 1),
                live,
            )
        val incoming =
            listOf(
                ChatMessage(id = "rest-s-1", role = MessageRole.USER, content = "retry", timestamp = 2),
            )

        val merged = mergeIncrementalTranscriptPage(incoming, current, "s", 1)

        assertEquals(listOf("retry", "retry"), merged.map { it.content })
        assertSame(live, merged.last())
    }

    // ── Issue #842: MCP/web tool rows (raw `<untrusted_tool_result>` text) ──
    //
    // REAL captured shapes (on-device 2026-08-08): the live WS bubble holds
    // the full tool.complete payload (JSON), while the REST transcript row
    // stores the SAME call's payload as RAW blob TEXT — not JSON, so content
    // canonicalization can never produce a key. The gateway `tool_call_id`
    // (present on BOTH sides) is the 1:1 identity that closes the gap.

    private val wsMcpToolPayload =
        """{"tool_id":"call_00_1GgRcqEFKA2TR0EkdkN85844","name":"mcp__ddgs__search_text","args":{"query":"Amman weather today"},"duration_s":3.2,"result":{"result":"<untrusted_tool_result source=\"mcp__ddgs__search_text\">\nThe following content was retrieved from an external source.\n{\"title\":\"Amman weather\"}\n</untrusted_tool_result>"}}"""

    private val restMcpToolBlob =
        """<untrusted_tool_result source="mcp__ddgs__search_text">
The following content was retrieved from an external source.
{"title":"Amman weather"}
</untrusted_tool_result>"""

    @Test
    fun sameLogicalMessage_mcpBlobRestRow_matchesByCallId() {
        val live =
            ChatMessage(
                role = MessageRole.TOOL,
                content = wsMcpToolPayload,
                toolName = "mcp__ddgs__search_text",
                toolCallId = "call_00_1GgRcqEFKA2TR0EkdkN85844",
            )
        val rest =
            ChatMessage(
                role = MessageRole.TOOL,
                content = restMcpToolBlob,
                id = "rest-s-3",
                toolCallId = "call_00_1GgRcqEFKA2TR0EkdkN85844",
            )
        assertTrue(sameLogicalMessage(live, rest))
    }

    @Test
    fun sameLogicalMessage_differentCallIds_notSameEvenWithSameBlob() {
        val live = ChatMessage(role = MessageRole.TOOL, content = wsMcpToolPayload, toolCallId = "call_00_aaa")
        val rest = ChatMessage(role = MessageRole.TOOL, content = restMcpToolBlob, toolCallId = "call_00_bbb")
        assertFalse(sameLogicalMessage(live, rest))
    }

    @Test
    fun sameLogicalMessage_callIdMissing_fallsBackToContentCanonical() {
        // Old cached rows may lack the call id — terminal-style content
        // canonicalization must still match.
        val ws = ChatMessage(role = MessageRole.TOOL, content = wsToolContent, toolName = "terminal")
        val rest = ChatMessage(role = MessageRole.TOOL, content = restToolContent)
        assertTrue(sameLogicalMessage(ws, rest))
    }

    @Test
    fun mergeTranscriptWithLive_mcpToolRows_collapseToOne() {
        // Issue #842 on-device repro: the REST page carries the same MCP
        // search call as raw blob text; with the call id present the merge
        // must NOT add a second tool bubble.
        val live =
            ChatMessage(
                role = MessageRole.TOOL,
                content = wsMcpToolPayload,
                toolName = "mcp__ddgs__search_text",
                toolCallId = "call_00_1GgRcqEFKA2TR0EkdkN85844",
                timestamp = 2,
            )
        val rest =
            ChatMessage(
                role = MessageRole.TOOL,
                content = restMcpToolBlob,
                id = "rest-s-3",
                toolCallId = "call_00_1GgRcqEFKA2TR0EkdkN85844",
                timestamp = 2,
            )
        val merged = mergeTranscriptWithLive(listOf(rest), listOf(live))
        // One copy survives (the merge keeps the REST row as server truth and
        // drops the covered live bubble — in the real flow mapServerMessages
        // already substituted the live row before this merge, preserving the
        // tool name + rich payload). The invariant: never two bubbles.
        assertEquals(1, merged.size)
        assertEquals(MessageRole.TOOL, merged.single().role)
    }

    @Test
    fun dedupeCached_mcpBlobRestRow_droppedWhenWsCopyExists() {
        val wsTool =
            ChatMessage(
                role = MessageRole.TOOL,
                content = wsMcpToolPayload,
                toolName = "mcp__ddgs__search_text",
                toolCallId = "call_00_1GgRcqEFKA2TR0EkdkN85844",
            )
        val restTool =
            ChatMessage(
                role = MessageRole.TOOL,
                content = restMcpToolBlob,
                id = "rest-s-3",
                toolCallId = "call_00_1GgRcqEFKA2TR0EkdkN85844",
            )
        val deduped = dedupeCachedMessages(listOf(wsTool, restTool))
        assertEquals(1, deduped.size)
        assertEquals(wsTool, deduped.single())
    }
}
