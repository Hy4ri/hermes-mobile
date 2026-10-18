package com.m57.hermescontrol.ui.sessions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.SessionListCacheStore
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.ProjectsSource
import com.m57.hermescontrol.data.ws.SessionLiveStatusSource
import com.m57.hermescontrol.theme.HermesControlTheme
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/** Device UI integration with an isolated in-memory backend; no live server or saved credentials. */
@RunWith(AndroidJUnit4::class)
class HistoryUndoTest {
    @get:Rule
    val compose = createComposeRule()
    private val api = mockk<HermesApiService>(relaxed = true)
    private var row = SessionInfo("undo-fixture", title = "Undo fixture", source = "desktop", message_count = 2)
    private var vm: SessionsViewModel? = null
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        val owner = DataScope("undo-fixture", "http://example.test/", "test")
        mockkObject(ApiClient, AuthManager, SessionListCacheStore)
        every { ApiClient.hermesApi } returns api
        every { AuthManager.currentDataScope() } returns owner
        every { SessionListCacheStore.get(any()) } returns null
        every { SessionListCacheStore.put(any(), any()) } just Runs
        every { SessionListCacheStore.remove(any()) } just Runs
        coEvery { api.getSessions(any(), any(), any(), any(), any(), any()) } answers {
            val visible = if ((arg<String?>(5) == "only") == (row.archived == true)) listOf(row) else emptyList()
            Response.success(SessionListResponse(visible, total = visible.size))
        }
        coEvery { api.getSessionInfo(row.id, any()) } answers { Response.success(row) }
        coEvery { api.setSessionPinned(row.id, any()) } answers {
            row = row.copy(pinned = secondArg<SessionRenameRequest>().pinned)
            Response.success(Unit)
        }
        coEvery { api.setSessionArchived(row.id, any()) } answers {
            row = row.copy(archived = secondArg<SessionRenameRequest>().archived)
            Response.success(Unit)
        }
    }

    private fun showHistory(): SessionsViewModel {
        val live = mockk<SessionLiveStatusSource>()
        every { live.events } returns emptyFlow()
        every { live.connectionStatus } returns MutableStateFlow(ConnectionStatus.DISCONNECTED)
        coEvery { live.fetchActiveSessionsSnapshot() } returns null
        val projects = mockk<ProjectsSource>()
        coEvery { projects.fetchProjects() } returns emptyList()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = SessionsViewModel(live, projects, { AuthManager.currentDataScope() }, { api })
        }
        compose.setContent { HermesControlTheme { SessionsScreen(viewModel = requireNotNull(vm)) } }
        compose.waitUntil(5_000) {
            vm!!
                .uiState.value.sessions
                .isNotEmpty()
        }
        return requireNotNull(vm)
    }

    @After
    fun tearDown() {
        // Cancel ViewModel jobs before restoring global dependencies.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm?.viewModelScope?.cancel() }
        unmockkAll()
    }

    @Test
    fun archiveMenuRemovesRowAndUndoRestoresItAfterServerReload() {
        val viewModel = showHistory()
        compose.onNodeWithTag("session_card_${row.id}").performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.sessions_action_archive)).performClick()
        compose.onNodeWithTag("session_card_${row.id}").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.sessions_undo_action)).assertHasClickAction().performClick()
        compose.onNodeWithTag("session_card_${row.id}").assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(row.archived == true)
            assertEquals(1, viewModel.uiState.value.total)
            viewModel.loadSessions(forceRefresh = true)
        }
        compose.onNodeWithTag("session_card_${row.id}").assertIsDisplayed()
        coVerify(exactly = 1) { api.setSessionArchived(row.id, SessionRenameRequest(archived = true)) }
        coVerify(exactly = 1) { api.setSessionArchived(row.id, SessionRenameRequest(archived = false)) }
    }

    @Test
    fun pinMenuOffersLocalizedUndoAndRestoresPreviousServerValue() {
        showHistory()
        compose.onNodeWithTag("session_card_${row.id}").performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.sessions_action_pin)).performClick()
        compose.onNodeWithText(context.getString(R.string.sessions_feedback_pinned)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sessions_undo_action)).assertHasClickAction().performClick()
        compose.onNodeWithText(context.getString(R.string.sessions_undo_done)).assertIsDisplayed()
        compose.runOnIdle { assertFalse(row.pinned == true) }
        coVerify(exactly = 1) { api.setSessionPinned(row.id, SessionRenameRequest(pinned = false, hidden = false)) }
    }

    @Test
    fun replacingFeedbackRemovesOldActionAndDispatchesLatestIdOnce() {
        var notice by mutableStateOf<HistoryActionFeedback?>(
            HistoryActionFeedback(1, R.string.sessions_feedback_pinned, true),
        )
        val calls = mutableListOf<Long>()
        compose.setContent {
            HermesControlTheme {
                HistoryActionSnackbarHost(notice, {
                    calls += it
                    notice = null
                }, { notice = null })
            }
        }
        compose.onNodeWithText(context.getString(R.string.sessions_feedback_pinned)).assertIsDisplayed()
        compose.runOnIdle { notice = HistoryActionFeedback(2, R.string.sessions_feedback_archived, true) }
        compose.onNodeWithText(context.getString(R.string.sessions_feedback_pinned)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.sessions_feedback_archived)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.sessions_undo_action)).performClick()
        compose.onNodeWithText(context.getString(R.string.sessions_undo_action)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(2L), calls) }
    }
}
