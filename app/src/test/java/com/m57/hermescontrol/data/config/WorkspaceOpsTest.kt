package com.m57.hermescontrol.data.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceOpsTest {
    @Test
    fun `self healing creates one default workspace and selects it`() {
        val healed = ServerStoreState(workspaces = emptyList(), selectedWorkspaceId = "missing").selfHealed()

        assertEquals(1, healed.workspaces.size)
        assertEquals(healed.workspaces.single().id, healed.selectedWorkspaceId)
        assertEquals("Focus", healed.workspaces.single().name)
    }

    @Test
    fun `opening sessions keeps stable order and a maximum of eight tabs`() {
        var state = ServerStoreState().selfHealed()
        repeat(10) { index -> state = state.openSessionTab("session-$index") }

        assertEquals((2..9).map { "session-$it" }, state.openSessionIds)
        assertEquals("session-9", state.activeSessionId)
    }

    @Test
    fun `pinned sessions survive tab pressure`() {
        var state = ServerStoreState().selfHealed().openSessionTab("important").toggleSessionPin("important")
        repeat(10) { index -> state = state.openSessionTab("session-$index") }

        assertTrue("important" in state.openSessionIds)
        assertTrue(state.sessionPreferences.single { it.sessionId == "important" }.pinned)
    }

    @Test
    fun `session can move between workspaces without duplicate membership`() {
        val first = WorkspaceDefinition(id = "first", name = "Focus")
        val second = WorkspaceDefinition(id = "second", name = "Research")
        val state =
            ServerStoreState(
                workspaces = listOf(first.copy(sessionIds = listOf("s1")), second),
                selectedWorkspaceId = "first",
            ).moveSessionToWorkspace("s1", "second")

        assertFalse("s1" in state.workspaces.first { it.id == "first" }.sessionIds)
        assertTrue("s1" in state.workspaces.first { it.id == "second" }.sessionIds)
    }

    @Test
    fun `model preference is stored per session`() {
        val state = ServerStoreState().setSessionModel("s1", "minimax-m3")

        assertEquals("minimax-m3", state.sessionPreferences.single { it.sessionId == "s1" }.modelAlias)
    }

    @Test
    fun `workspace lifecycle preserves sessions and always keeps one workspace`() {
        var state = ServerStoreState().selfHealed().createWorkspace("research", "Research")
        state = state.moveSessionToWorkspace("s1", "research").selectWorkspace("research")
        state = state.removeWorkspace("research")

        assertEquals(1, state.workspaces.size)
        assertTrue("s1" in state.workspaces.single().sessionIds)
        assertEquals(state.workspaces.single().id, state.selectedWorkspaceId)
        assertEquals(state, state.removeWorkspace(state.workspaces.single().id))
    }
}
