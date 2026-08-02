package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
