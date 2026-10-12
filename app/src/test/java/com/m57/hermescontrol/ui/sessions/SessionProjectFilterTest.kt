package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.ProjectFolder
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionTreeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionProjectFilterTest {
    private val projects =
        listOf(
            ProjectInfo(
                id = "p1",
                name = "Alpha",
                color = "hsl(210 68% 58%)",
                folders = listOf(ProjectFolder(path = "/work/alpha")),
            ),
        )

    private val rows =
        listOf(
            SessionInfo("a1", cwd = "/work/alpha/src"),
            SessionInfo("b1", cwd = "/work/beta"),
            SessionInfo("a2", cwd = "/work/alpha"),
            SessionInfo("h1"),
            SessionInfo("b2", cwd = "/work/beta"),
        )

    private fun state(
        filter: String? = null,
        sessions: List<SessionInfo> = rows,
    ) = SessionsUiState(sessions = sessions, projects = projects, projectFilter = filter)

    @Test
    fun availableProjects_countOrdered_noProjectLast() {
        val keys = state().availableProjects
        assertEquals(listOf("Alpha", "beta", NO_PROJECT_KEY), keys.map { it.key })
        assertEquals(listOf(2, 2, 1), keys.map { it.count })
        assertEquals("hsl(210 68% 58%)", keys.first().color)
    }

    @Test
    fun filter_narrowsToProject() {
        assertEquals(listOf("a1", "a2"), state("Alpha").displaySessions.map { it.id })
        assertEquals(listOf("h1"), state(NO_PROJECT_KEY).displaySessions.map { it.id })
    }

    @Test
    fun staleFilter_fallsBackToAll() {
        val s = state("gone")
        assertNull(s.activeProjectFilter)
        assertEquals(5, s.displaySessions.size)
    }

    @Test
    fun projectChips_followSourceFilter() {
        val tagged = rows.map { it.copy(source = if (it.id.startsWith("a")) "cli" else "desktop") }
        val s = state(sessions = tagged).copy(sourceFilter = "cli")
        assertEquals(listOf("Alpha"), s.availableProjects.map { it.key })
    }

    @Test
    fun grouping_keepsOrderWithinGroup_andNoProjectLast() {
        val items = rows.map { SessionTreeItem(it, 0, null, displayTitle = it.id) }
        val groups = groupSessionsByProject(items, projects)
        assertEquals(listOf("Alpha", "beta", NO_PROJECT_KEY), groups.map { it.key })
        assertEquals(listOf("a1", "a2"), groups[0].items.map { it.session.id })
        assertEquals(listOf("b1", "b2"), groups[1].items.map { it.session.id })
        assertEquals("hsl(210 68% 58%)", groups[0].color)
    }
}
