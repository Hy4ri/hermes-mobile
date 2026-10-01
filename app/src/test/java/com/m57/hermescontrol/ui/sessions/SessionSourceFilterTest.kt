package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.ui.sessions.components.sourceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSourceFilterTest {
    private val rows =
        listOf(
            SessionInfo("a", source = "cli"),
            SessionInfo("b", source = "CLI"),
            SessionInfo("c", source = "desktop"),
            SessionInfo("d", source = "api_server"),
            SessionInfo("e", source = "desktop", hidden = true),
            SessionInfo("f", source = null),
        )

    @Test
    fun availableSources_onlyLoadedVisibleSources_orderedByCountThenName() {
        val state = SessionsUiState(sessions = rows)
        assertEquals(listOf("cli", "api_server", "desktop"), state.availableSources)
    }

    @Test
    fun availableSources_includesHiddenOnlyWhenShown() {
        val onlyHidden = listOf(SessionInfo("x", source = "tui", hidden = true), SessionInfo("y", source = "cli"))
        assertEquals(listOf("cli"), SessionsUiState(sessions = onlyHidden).availableSources)
        assertEquals(
            listOf("cli", "tui"),
            SessionsUiState(sessions = onlyHidden, showHidden = true).availableSources,
        )
    }

    @Test
    fun filter_isCaseInsensitiveAndNarrowsList() {
        val state = SessionsUiState(sessions = rows, sourceFilter = "cli")
        assertEquals(listOf("a", "b"), state.displaySessions.map { it.id })
    }

    @Test
    fun staleFilter_fallsBackToAll() {
        val state = SessionsUiState(sessions = rows, sourceFilter = "telegram")
        assertNull(state.activeSourceFilter)
        assertEquals(5, state.displaySessions.size)
    }

    @Test
    fun searchMode_usesSearchResultSources() {
        val state =
            SessionsUiState(
                searchQuery = "q",
                sessions = rows,
                searchResults =
                    listOf(
                        SessionSearchResult("s1", source = "telegram"),
                        SessionSearchResult("s2", source = "cli"),
                    ),
                sourceFilter = "telegram",
            )
        assertEquals(listOf("cli", "telegram"), state.availableSources)
        assertEquals(listOf("s1"), displayedSessions(state).map { it.session.id })
    }

    @Test
    fun sourceLabel_mapsKnownAndTitleCasesUnknown() {
        assertEquals("API", sourceLabel("api_server"))
        assertEquals("Desktop", sourceLabel("desktop"))
        assertEquals("TUI", sourceLabel("tui"))
        assertEquals("Whatsapp cloud", sourceLabel("whatsapp_cloud"))
        assertEquals("Unknown", sourceLabel(null))
    }
}
