package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.activeToolMemberIds
import com.m57.hermescontrol.ui.chat.fullbleed.expandedToolGroups
import com.m57.hermescontrol.ui.chat.fullbleed.fullBleedItemKeys
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.messageIdToLazyIndex
import com.m57.hermescontrol.ui.chat.fullbleed.searchMatchToLazyIndex
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
    fun searchTargetsResolveHoistedReasoningAndToolRows() {
        val messages =
            listOf(
                ChatMessage("u", MessageRole.USER, "question"),
                ChatMessage("t", MessageRole.TOOL, "output", toolName = "terminal"),
                ChatMessage("a", MessageRole.ASSISTANT, "answer", reasoningText = "thinking"),
            )
        val turns = groupIntoTurns(messages)

        assertEquals(1, searchMatchToLazyIndex(turns, messages, SearchMatch(2, 0, SearchTarget.REASONING)))
        assertEquals(2, searchMatchToLazyIndex(turns, messages, SearchMatch(1, 0, SearchTarget.TOOL)))
        assertEquals(3, searchMatchToLazyIndex(turns, messages, SearchMatch(2, 0)))
    }

    @Test
    fun secondaryReasoningResolvesToItsProseRow() {
        val messages =
            listOf(
                ChatMessage("a1", MessageRole.ASSISTANT, "one", reasoningText = "first"),
                ChatMessage("a2", MessageRole.ASSISTANT, "two", reasoningText = "second"),
            )
        val turns = groupIntoTurns(messages)
        val keys = fullBleedItemKeys(turns)

        assertEquals(
            keys.indexOf("prose-a2"),
            searchMatchToLazyIndex(turns, messages, SearchMatch(1, 0, SearchTarget.REASONING)),
        )
    }

    @Test
    fun groupedToolsKeepExactSearchAndProseIndicesWhenOpened() {
        val messages =
            listOf(
                ChatMessage("u", MessageRole.USER, "question"),
                ChatMessage("t1", MessageRole.TOOL, "first output"),
                ChatMessage("t2", MessageRole.TOOL, "second output"),
                ChatMessage("a", MessageRole.ASSISTANT, "answer", reasoningText = "thinking"),
            )
        val turns = groupIntoTurns(messages)
        assertEquals(listOf("user-u", "reasoning-a", "toolgroup-t1", "prose-a"), fullBleedItemKeys(turns))
        val expanded = setOf("t1")
        assertEquals(
            listOf("user-u", "reasoning-a", "toolgroup-t1", "tool-t1", "tool-t2", "prose-a"),
            fullBleedItemKeys(turns, expanded),
        )
        assertEquals(4, searchMatchToLazyIndex(turns, messages, SearchMatch(2, 0, SearchTarget.TOOL), expanded))
        assertEquals(5, messageIdToLazyIndex(turns, expandedToolGroupIds = expanded)["a"])
        assertEquals(3, messageIdToLazyIndex(turns)["a"])
    }

    @Test
    fun prependedToolsPreserveGroupExpansionThroughMemberIdentity() {
        val first = ChatMessage("t1", MessageRole.TOOL, "one")
        val second = ChatMessage("t2", MessageRole.TOOL, "two")
        val turns = groupIntoTurns(listOf(ChatMessage("old", MessageRole.TOOL, "old"), first, second))
        assertEquals(setOf("old"), expandedToolGroups(turns, setOf("t1", "t2")))
    }

    @Test
    fun staleExpandedMembersAreIgnoredWhenTranscriptChanges() {
        val current =
            groupIntoTurns(
                listOf(
                    ChatMessage("current-1", MessageRole.TOOL, "one"),
                    ChatMessage("current-2", MessageRole.TOOL, "two"),
                ),
            )
        assertEquals(setOf("current-1"), activeToolMemberIds(current, setOf("old", "current-1")))
        assertEquals(emptySet<String>(), activeToolMemberIds(current, setOf("old")))
    }

    @Test
    fun singletonGroupKeepsTheOriginalToolKey() {
        val tool = ChatMessage("single", MessageRole.TOOL, "output")
        val turns = listOf(ChatTurn.Agent(listOf(AgentEntry.ToolGroup(listOf(AgentEntry.ToolRow(tool))))))
        assertEquals(listOf("tool-single"), fullBleedItemKeys(turns))
    }

    @Test
    fun groupingDoesNotChangeRealCallMilestones() {
        val tools = (1..12).map { ChatMessage("t$it", MessageRole.TOOL, "output") }
        val turns = groupIntoTurns(tools)
        assertEquals(listOf("toolgroup-t1"), fullBleedItemKeys(turns))
        assertEquals(mapOf("t5" to 5, "t10" to 10), toolCallMilestones(tools))
        assertEquals(13, fullBleedItemKeys(turns, setOf("t1")).size)
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
