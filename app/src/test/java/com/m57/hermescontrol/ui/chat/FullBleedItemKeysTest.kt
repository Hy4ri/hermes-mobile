package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.fullBleedItemKeys
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.messageIdToLazyIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class FullBleedItemKeysTest {
    @Test
    fun keysIncludeReasoningToolsAndSystemButSkipEmptyProse() {
        val turns =
            groupIntoTurns(
                listOf(
                    ChatMessage("u", MessageRole.USER, "question"),
                    ChatMessage("empty", MessageRole.ASSISTANT, ""),
                    ChatMessage("t", MessageRole.TOOL, "output"),
                    ChatMessage("a", MessageRole.ASSISTANT, "answer", reasoningText = "thinking"),
                    ChatMessage("s", MessageRole.SYSTEM, "event"),
                ),
            )
        assertEquals(listOf("user-u", "reasoning-a", "tool-t", "prose-a", "sys-s"), fullBleedItemKeys(turns))
        assertEquals(mapOf("u" to 0, "a" to 3), messageIdToLazyIndex(turns))
        assertEquals(mapOf("u" to 2, "a" to 5), messageIdToLazyIndex(turns, leadingItems = 2))
    }

    @Test
    fun toolAnchorSurvivesPrependThatExtendsItsAgentTurn() {
        val tool = ChatMessage("live-tool", MessageRole.TOOL, "output")
        val older =
            (0..149).map {
                ChatMessage("old-$it", MessageRole.ASSISTANT, "answer", reasoningText = "thinking")
            }
        assertEquals(listOf("tool-live-tool"), fullBleedItemKeys(groupIntoTurns(listOf(tool))))
        val keys = fullBleedItemKeys(groupIntoTurns(older + tool))
        assertEquals("reasoning-old-0", keys.first())
        assertEquals(151, keys.indexOf("tool-live-tool"))
    }
}
