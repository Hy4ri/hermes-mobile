package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurnsWithStreaming
import org.junit.Assert.assertEquals
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
}
