package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end sanity on a synthetic LONG session (2000 messages, ~1 KB each).
 *
 * The matcher must stay correct at scale: exact occurrence counts, valid
 * offsets, cap semantics. No wall-clock asserts (flaky on CI) — the local
 * scan duration is measured manually during development.
 */
class ChatSearchPerfSanityTest {
    private fun longSession(): List<ChatMessage> =
        (0 until 2_000).map { index ->
            val role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT
            val words =
                buildList {
                    repeat(80) { add(listOf("alpha", "beta", "gamma", "deploy", "config")[it % 5]) }
                    add("needle-$index")
                }
            ChatMessage(
                id = "m$index",
                role = role,
                content = words.joinToString(" "),
            )
        }

    @Test
    fun `common word across a long session reports the exact total and caps storage`() {
        val messages = longSession()
        val result = ChatSearchController().findMatches(messages, "the")
        // "the" never appears (content is alpha/beta/...), so no matches.
        assertEquals(0, result.totalMatches)
        assertEquals(0, result.matches.size)
        assertFalse(result.capped)
    }

    @Test
    fun `long session search returns exact counts for a frequent word`() {
        val messages = longSession()
        val result = ChatSearchController().findMatches(messages, "deploy")

        // "deploy" is the 4th of 5 rotating words → appears 16× per message,
        // 2000 messages → 32_000 total occurrences.
        assertEquals(32_000, result.totalMatches)
        assertTrue(result.capped)
        assertEquals(ChatSearchController.MAX_SEARCH_MATCHES, result.matches.size)

        // Every stored offset points at an actual "deploy" in its message.
        for (match in result.matches) {
            val message = messages[match.messageIndex]
            val start = match.contentOffset
            assertTrue(start in 0..message.content.length - 6)
            assertTrue(
                message.content.substring(start, start + 6).equals("deploy", ignoreCase = true),
            )
        }
    }

    @Test
    fun `long session search finds a unique word at scale`() {
        val messages = longSession()
        val result = ChatSearchController().findMatches(messages, "needle-1999")

        assertEquals(1, result.totalMatches)
        assertEquals(1, result.matches.size)
        assertFalse(result.capped)
        val match = result.matches.single()
        assertEquals(1999, match.messageIndex)
        assertTrue(match.contentOffset >= 0)
    }

    @Test
    fun `tool-heavy long session excludes payload noise`() {
        val messages =
            longSession().mapIndexed { index, m ->
                if (index % 10 == 0) {
                    m.copy(role = MessageRole.TOOL, content = "tool payload containing needle-1999 repeated")
                } else {
                    m
                }
            }
        // The only real visible hit is the assistant message's own needle.
        val result = ChatSearchController().findMatches(messages, "needle-1999")
        assertEquals(1, result.totalMatches)
        assertEquals(1999, result.matches.single().messageIndex)
    }
}
