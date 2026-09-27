package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1335: prefix dedupe must not merge distinct messages.
 */
class ChatPrefixDedupeTest {
    // ── Contract 1 ────────────────────────────────────────────────────────────
    // Two USER messages, both >=40 chars, one a strict prefix of the other,
    // different ids -> NOT the same logical message (both survive a merge).

    @Test
    fun contract1_userMessages_strictPrefixDistinctIds_notSameLogicalMessage() {
        val prefixText = "Please write a comprehensive test suite for issue thirteen thirty five."
        val fullText = "$prefixText It should cover all edge cases thoroughly."
        val msg1 = ChatMessage(id = "user-msg-1", role = MessageRole.USER, content = prefixText)
        val msg2 = ChatMessage(id = "user-msg-2", role = MessageRole.USER, content = fullText)

        assertFalse(sameLogicalMessage(msg1, msg2))
        assertFalse(sameLogicalMessage(msg2, msg1))

        val merged = mergeTranscriptWithLive(listOf(msg2), listOf(msg1), preserveLiveIds = true)
        assertEquals(2, merged.size)
        assertTrue(merged.any { it.id == "user-msg-1" && it.content == prefixText })
        assertTrue(merged.any { it.id == "user-msg-2" && it.content == fullText })
    }

    // ── Contract 2 ────────────────────────────────────────────────────────────
    // Two ASSISTANT messages that are both canonical REST rows (distinct restIds /
    // canonical ids), one a >=40-char prefix of the other -> NOT same.

    @Test
    fun contract2_assistantCanonicalRestRows_strictPrefix_notSameLogicalMessage() {
        val prefixText = "I have inspected the project repository and verified the build files."
        val fullText = "$prefixText Now proceeding with the implementation of the requested tests."
        val restRow1 =
            ChatMessage(
                id = "rest-s-101",
                role = MessageRole.ASSISTANT,
                content = prefixText,
                restId = "rest-s-101",
            )
        val restRow2 =
            ChatMessage(
                id = "rest-s-102",
                role = MessageRole.ASSISTANT,
                content = fullText,
                restId = "rest-s-102",
            )

        assertFalse(sameLogicalMessage(restRow1, restRow2))
        assertFalse(sameLogicalMessage(restRow2, restRow1))

        val merged = mergeTranscriptWithLive(listOf(restRow2), listOf(restRow1), preserveLiveIds = true)
        assertEquals(2, merged.size)
    }

    // ── Contract 3 ────────────────────────────────────────────────────────────
    // TOOL rows never match by content prefix.

    @Test
    fun contract3_toolRows_neverMatchByContentPrefix() {
        val prefixToolContent = """{"output":"build succeeded without any compiler errors or warnings during check"}"""
        val fullToolContent =
            """{"output":"build succeeded without any compiler errors or warnings during check and all tests pass"}"""
        val tool1 =
            ChatMessage(
                id = "tool-1",
                role = MessageRole.TOOL,
                content = prefixToolContent,
                toolName = "terminal",
                toolCallId = "call-1",
            )
        val tool2 =
            ChatMessage(
                id = "tool-2",
                role = MessageRole.TOOL,
                content = fullToolContent,
                toolName = "terminal",
                toolCallId = "call-2",
            )

        assertFalse(sameLogicalMessage(tool1, tool2))
        assertFalse(sameLogicalMessage(tool2, tool1))
    }

    // ── Contract 4 ────────────────────────────────────────────────────────────
    // Exact-equal trimmed content still matches as before for a live vs REST pair
    // of the same role (sanity).

    @Test
    fun contract4_exactEqualTrimmedContent_liveVsRest_matchesSameLogicalMessage() {
        for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
            val live =
                ChatMessage(
                    id = "live-temp-id",
                    role = role,
                    content = "\n\nHello, this is an important message for testing.\n  ",
                )
            val rest =
                ChatMessage(
                    id = "rest-s-200",
                    role = role,
                    content = "Hello, this is an important message for testing.",
                    restId = "rest-s-200",
                )

            assertTrue("Role $role exact-equal trimmed content must match", sameLogicalMessage(live, rest))
            assertTrue(
                "Role $role exact-equal trimmed content must match symmetrically",
                sameLogicalMessage(rest, live),
            )

            val merged = mergeTranscriptWithLive(listOf(rest), listOf(live), preserveLiveIds = true)
            assertEquals("Exact-equal trimmed pair should deduplicate into single message", 1, merged.size)
        }
    }

    // ── Contract 5 ────────────────────────────────────────────────────────────
    // Issue #842 scenario still dedupes: a live sealed interim assistant narration
    // without completion/REST identity whose text (after trim) is a prefix of /
    // equal-prefix to the persisted REST narration -> treated as same (reuse the
    // shape used in ChatHistoryCommentaryTest if it exists).

    @Test
    fun contract5_issue842_liveSealedInterimAssistantNarration_prefixOfRestNarration_stillDedupes() {
        // Issue #842 / ChatToolDedupeTest pattern:
        // Live sealed interim bubble without REST identity raced flush and ends prematurely.
        // Rest row contains the full/completed narration.
        val liveOrphan =
            ChatMessage(
                id = "live-orphan-sealed",
                role = MessageRole.ASSISTANT,
                content =
                    "\n\ntool's loaded! 🔍 now searchin' for the **best hummus recipe**" +
                        " — gimme dat creamy dreamy chickpea",
            )
        val restRow =
            ChatMessage(
                id = "rest-s-842",
                role = MessageRole.ASSISTANT,
                content =
                    "tool's loaded! 🔍 now searchin' for the **best hummus recipe**" +
                        " — gimme dat creamy dreamy chickpea goodness:",
                restId = "rest-s-842",
            )

        assertTrue(sameLogicalMessage(liveOrphan, restRow))
        assertTrue(sameLogicalMessage(restRow, liveOrphan))

        val merged = mergeTranscriptWithLive(listOf(restRow), listOf(liveOrphan), preserveLiveIds = true)
        assertEquals(
            "Live interim orphan should deduplicate against persisted REST narration",
            1,
            merged.size,
        )
    }
}
