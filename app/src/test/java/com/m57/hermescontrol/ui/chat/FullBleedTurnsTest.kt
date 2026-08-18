package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.currentMatchMessageId
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurnsWithStreaming
import com.m57.hermescontrol.ui.chat.fullbleed.matchedMessageIds
import com.m57.hermescontrol.ui.chat.fullbleed.messageIdToLazyIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullBleedTurnsTest {
    private fun msg(
        id: String,
        role: MessageRole,
        content: String = "c-$id",
        toolStatus: ToolStatus? = null,
        isStreaming: Boolean = false,
    ) = ChatMessage(id = id, role = role, content = content, toolStatus = toolStatus, isStreaming = isStreaming)

    private fun entries(vararg e: AgentEntry) = ChatTurn.Agent(e.toList())

    @Test
    fun `empty list produces no turns`() {
        assertEquals(emptyList<ChatTurn>(), groupIntoTurns(emptyList()))
    }

    @Test
    fun `single user message is its own turn`() {
        val m = msg("u1", MessageRole.USER)
        assertEquals(listOf<ChatTurn>(ChatTurn.User(m)), groupIntoTurns(listOf(m)))
    }

    @Test
    fun `single assistant message is an agent turn with prose`() {
        val m = msg("a1", MessageRole.ASSISTANT)
        assertEquals(listOf<ChatTurn>(entries(AgentEntry.Prose(m))), groupIntoTurns(listOf(m)))
    }

    @Test
    fun `user then assistant-tool-assistant groups into user turn plus agent turn`() {
        val u = msg("u1", MessageRole.USER)
        val a1 = msg("a1", MessageRole.ASSISTANT)
        val t = msg("t1", MessageRole.TOOL, toolStatus = ToolStatus.COMPLETED)
        val a2 = msg("a2", MessageRole.ASSISTANT)
        val result = groupIntoTurns(listOf(u, a1, t, a2))
        assertEquals(
            listOf(
                ChatTurn.User(u),
                entries(AgentEntry.Prose(a1), AgentEntry.ToolRow(t), AgentEntry.Prose(a2)),
            ),
            result,
        )
    }

    @Test
    fun `two consecutive user messages produce two user turns`() {
        val u1 = msg("u1", MessageRole.USER)
        val u2 = msg("u2", MessageRole.USER)
        assertEquals(listOf(ChatTurn.User(u1), ChatTurn.User(u2)), groupIntoTurns(listOf(u1, u2)))
    }

    @Test
    fun `leading tool and system messages before any assistant become an agent turn`() {
        val t = msg("t0", MessageRole.TOOL, toolStatus = ToolStatus.COMPLETED)
        val s = msg("s0", MessageRole.SYSTEM)
        val a = msg("a0", MessageRole.ASSISTANT)
        val result = groupIntoTurns(listOf(t, s, a))
        assertEquals(
            listOf(entries(AgentEntry.ToolRow(t), AgentEntry.SystemEvent(s), AgentEntry.Prose(a))),
            result,
        )
    }

    @Test
    fun `system event is preserved as a SystemEvent entry not prose`() {
        val s = msg("s1", MessageRole.SYSTEM, content = "Self-improvement review: patched skill")
        val result = groupIntoTurns(listOf(s))
        assertEquals(listOf(entries(AgentEntry.SystemEvent(s))), result)
    }

    @Test
    fun `display_kind marker is a SystemEvent entry not a user turn`() {
        val marker =
            msg("m1", MessageRole.USER, content = "[System: The active model has changed to gpt-5]")
                .copy(displayKind = "model_switch")
        val result = groupIntoTurns(listOf(marker))
        assertEquals(listOf<ChatTurn>(entries(AgentEntry.SystemEvent(marker))), result)
    }

    @Test
    fun `max-iterations nudge is a SystemEvent not a user bubble`() {
        // Backend persists the max-iterations notice as a plain role=user row
        // with NO display_kind (it's stripped on SessionDB persistence); the
        // mobile must still treat it as a system event so it renders with the
        // distinct system design instead of a fake user bubble.
        val content =
            "You've reached the maximum number of tool-calling iterations allowed. " +
                "Please provide a final response."
        val nudge = msg("n1", MessageRole.USER, content = content)
        val result = groupIntoTurns(listOf(nudge))
        assertEquals(
            listOf<ChatTurn>(
                entries(AgentEntry.SystemEvent(nudge.copy(displayKind = "max_iterations_reached"))),
            ),
            result,
        )
    }

    @Test
    fun `marker between assistant and user does not close the agent turn`() {
        val a = msg("a1", MessageRole.ASSISTANT)
        val marker =
            msg("m1", MessageRole.USER, content = "marker")
                .copy(displayKind = "personality_switch")
        val u = msg("u1", MessageRole.USER)
        val result = groupIntoTurns(listOf(a, marker, u))
        assertEquals(
            listOf(
                entries(AgentEntry.Prose(a), AgentEntry.SystemEvent(marker)),
                ChatTurn.User(u),
            ),
            result,
        )
    }

    @Test
    fun `marker only closes nothing and trailing marker lands in agent turn`() {
        val u = msg("u1", MessageRole.USER)
        val marker =
            msg("m1", MessageRole.USER, content = "marker")
                .copy(displayKind = "auto_continue")
        val result = groupIntoTurns(listOf(u, marker))
        assertEquals(
            listOf(ChatTurn.User(u), entries(AgentEntry.SystemEvent(marker))),
            result,
        )
    }

    @Test
    fun `user message closes the current agent turn`() {
        val a1 = msg("a1", MessageRole.ASSISTANT)
        val u = msg("u1", MessageRole.USER)
        val a2 = msg("a2", MessageRole.ASSISTANT)
        val result = groupIntoTurns(listOf(a1, u, a2))
        assertEquals(
            listOf(
                entries(AgentEntry.Prose(a1)),
                ChatTurn.User(u),
                entries(AgentEntry.Prose(a2)),
            ),
            result,
        )
    }

    // ── groupIntoTurnsWithStreaming ────────────────────────────────────────

    @Test
    fun `null streaming message falls back to plain grouping`() {
        val u = msg("u1", MessageRole.USER)
        val a = msg("a1", MessageRole.ASSISTANT)
        assertEquals(
            groupIntoTurns(listOf(u, a)),
            groupIntoTurnsWithStreaming(listOf(u, a), null),
        )
    }

    @Test
    fun `streaming prose appends to the current agent turn`() {
        val u = msg("u1", MessageRole.USER)
        val t = msg("t1", MessageRole.TOOL, toolStatus = ToolStatus.COMPLETED)
        val s = msg("s1", MessageRole.ASSISTANT, content = "partial", isStreaming = true)
        val result = groupIntoTurnsWithStreaming(listOf(u, t), s)
        assertEquals(
            listOf(ChatTurn.User(u), entries(AgentEntry.ToolRow(t), AgentEntry.Prose(s))),
            result,
        )
    }

    @Test
    fun `streaming prose after a user turn opens a new agent turn`() {
        val u = msg("u1", MessageRole.USER)
        val s = msg("s1", MessageRole.ASSISTANT, content = "partial", isStreaming = true)
        val result = groupIntoTurnsWithStreaming(listOf(u), s)
        assertEquals(
            listOf(ChatTurn.User(u), entries(AgentEntry.Prose(s))),
            result,
        )
    }

    @Test
    fun `streaming message already present in messages is not duplicated`() {
        val u = msg("u1", MessageRole.USER)
        val s = msg("s1", MessageRole.ASSISTANT, content = "done", isStreaming = false)
        // Commit race: the same id exists in both messages and streamingMessage.
        val result = groupIntoTurnsWithStreaming(listOf(u, s), s.copy(isStreaming = true))
        assertEquals(
            listOf(ChatTurn.User(u), entries(AgentEntry.Prose(s))),
            result,
        )
    }

    @Test
    fun `empty messages with streaming produce a single agent turn`() {
        val s = msg("s1", MessageRole.ASSISTANT, content = "partial", isStreaming = true)
        assertEquals(listOf(entries(AgentEntry.Prose(s))), groupIntoTurnsWithStreaming(emptyList(), s))
    }

    // ── messageIdToLazyIndex ───────────────────────────────────────────────

    @Test
    fun `lazy index maps simple user turns one to one`() {
        val u1 = msg("u1", MessageRole.USER)
        val u2 = msg("u2", MessageRole.USER)
        val map = messageIdToLazyIndex(groupIntoTurns(listOf(u1, u2)))
        assertEquals(2, map.size)
        assertEquals(0, map["u1"])
        assertEquals(1, map["u2"])
    }

    @Test
    fun `lazy index accounts for the reasoning hoist item`() {
        val a1 = ChatMessage(id = "a1", role = MessageRole.ASSISTANT, content = "prose", reasoningText = "thinking")
        val t1 = msg("t1", MessageRole.TOOL)
        val a2 = msg("a2", MessageRole.ASSISTANT)
        val map = messageIdToLazyIndex(groupIntoTurns(listOf(a1, t1, a2)))
        // reasoning-a1 item at lazy 0, prose a1 at 1, tool t1 at 2, prose a2 at 3
        assertEquals(1, map["a1"])
        assertEquals(3, map["a2"])
        // tool rows are items but never map (not searchable).
        assertNull(map["t1"])
    }

    @Test
    fun `lazy index offsets by leading items`() {
        val u1 = msg("u1", MessageRole.USER)
        val u2 = msg("u2", MessageRole.USER)
        // loading-older spinner occupies lazy item 0.
        val map = messageIdToLazyIndex(groupIntoTurns(listOf(u1, u2)), leadingItems = 1)
        assertEquals(1, map["u1"])
        assertEquals(2, map["u2"])
    }

    // ── currentMatchMessageId / matchedMessageIds ─────────────────────────

    @Test
    fun `currentMatchMessageId resolves once for the current index`() {
        val u1 = msg("u1", MessageRole.USER)
        val a1 = msg("a1", MessageRole.ASSISTANT)
        val msgs = listOf(u1, a1)
        assertEquals("a1", currentMatchMessageId(msgs, listOf(0, 1), 1))
        assertEquals("u1", currentMatchMessageId(msgs, listOf(0, 1), 0))
        assertNull(currentMatchMessageId(msgs, listOf(0, 1), -1))
        assertNull(currentMatchMessageId(msgs, emptyList(), 0))
        assertNull(currentMatchMessageId(msgs, listOf(9), 0))
    }

    @Test
    fun `matchedMessageIds returns unique ids for match indices`() {
        val u1 = msg("u1", MessageRole.USER)
        val a1 = msg("a1", MessageRole.ASSISTANT)
        val a2 = msg("a2", MessageRole.ASSISTANT)
        val msgs = listOf(u1, a1, a2)
        assertEquals(setOf("u1", "a2"), matchedMessageIds(msgs, listOf(0, 2, 0)))
        assertEquals(emptySet<String>(), matchedMessageIds(msgs, listOf(9)))
    }
}
