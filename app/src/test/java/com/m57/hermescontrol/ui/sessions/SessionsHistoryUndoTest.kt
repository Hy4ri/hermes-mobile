package com.m57.hermescontrol.ui.sessions

import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.local.SessionListCacheStore
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.model.SessionRenameRequest
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsHistoryUndoTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<HermesApiService>(relaxed = true)
    private val ambientApi = mockk<HermesApiService>(relaxed = true)
    private var owner = DataScope("server-a", "http://example.test/", "profile-a")
    private val rows = linkedMapOf("s" to SessionInfo("s"), "other" to SessionInfo("other"))
    private val viewModels = mutableListOf<SessionsViewModel>()
    private val capturedOwners = mutableListOf<DataScope>()

    private fun idle() = dispatcher.scheduler.advanceUntilIdle()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkObject(ApiClient, AuthManager, SessionListCacheStore)
        every { AuthManager.currentDataScope() } answers { owner }
        every { ApiClient.hermesApi } returns ambientApi
        every { SessionListCacheStore.get(any()) } returns null
        every { SessionListCacheStore.put(any(), any()) } just Runs
        every { SessionListCacheStore.remove(any()) } just Runs
        coEvery { ambientApi.getSessions(any(), any(), any(), any(), any(), any()) } answers {
            val archived = arg<String?>(5) == "only"
            val visible = rows.values.filter { (it.archived == true) == archived }
            Response.success(SessionListResponse(sessions = visible, total = visible.size))
        }
        coEvery { api.getSessionInfo(any(), any()) } answers {
            rows[firstArg<String>()]?.let { Response.success(it) }
                ?: Response.error(404, "missing".toResponseBody(null))
        }
        coEvery { api.setSessionPinned(any(), any()) } answers {
            val id = firstArg<String>()
            val body = secondArg<SessionRenameRequest>()
            val row = rows.getValue(id)
            rows[id] =
                row.copy(pinned = body.pinned, hidden = if (body.pinned == true) false else body.hidden ?: row.hidden)
            Response.success(Unit)
        }
        coEvery { api.setSessionArchived(any(), any()) } answers {
            val id = firstArg<String>()
            rows[id] = rows.getValue(id).copy(archived = secondArg<SessionRenameRequest>().archived)
            Response.success(Unit)
        }
    }

    private fun viewModel(): SessionsViewModel {
        val vm =
            SessionsViewModel(
                currentActionScope = { owner },
                actionServiceFactory = {
                    capturedOwners += it
                    api
                },
            )
        viewModels += vm
        vm.loadSessions()
        idle()
        return vm
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun pinAndUnpinRestoreTheirExplicitPreviousValue() {
        val vm = viewModel()
        for (previous in listOf(false, true)) {
            rows["s"] = SessionInfo("s", pinned = previous)
            vm.loadSessions(forceRefresh = true)
            idle()
            vm.togglePin("s")
            idle()
            assertEquals(!previous, rows.getValue("s").pinned)
            val feedback = vm.uiState.value.historyFeedback!!
            assertTrue(feedback.canUndo)
            vm.undoHistoryChange(feedback.id)
            idle()
            assertEquals(previous, rows.getValue("s").pinned)
            assertEquals(
                previous,
                vm.uiState.value.sessions
                    .first { it.id == "s" }
                    .pinned,
            )
            assertEquals(2, vm.uiState.value.total)
            assertEquals(
                R.string.sessions_undo_done,
                vm.uiState.value.historyFeedback
                    ?.messageRes,
            )
            assertFalse(
                vm.uiState.value.historyFeedback!!
                    .canUndo,
            )
        }
        coVerify(exactly = 0) { ambientApi.setSessionPinned(any(), any()) }
        assertTrue(capturedOwners.all { it == owner })
    }

    @Test
    fun archiveAndUnarchiveRestoreServerStateAndListPlacement() {
        val vm = viewModel()
        vm.toggleArchive("s")
        idle()
        assertTrue(rows.getValue("s").archived == true)
        assertEquals(
            listOf("other"),
            vm.uiState.value.sessions
                .map { it.id },
        )
        assertEquals(1, vm.uiState.value.total)
        vm.undoHistoryChange(
            vm.uiState.value.historyFeedback!!
                .id,
        )
        idle()
        assertFalse(rows.getValue("s").archived == true)
        assertEquals(
            listOf("s", "other"),
            vm.uiState.value.sessions
                .map { it.id },
        )
        assertEquals(2, vm.uiState.value.total)

        rows["s"] = rows.getValue("s").copy(archived = true)
        vm.toggleShowArchived()
        idle()
        vm.toggleArchive("s")
        idle()
        assertTrue(
            vm.uiState.value.sessions
                .isEmpty(),
        )
        vm.undoHistoryChange(
            vm.uiState.value.historyFeedback!!
                .id,
        )
        idle()
        assertTrue(rows.getValue("s").archived == true)
        assertEquals(
            listOf("s"),
            vm.uiState.value.sessions
                .map { it.id },
        )
        assertEquals(1, vm.uiState.value.total)
        coVerify(exactly = 0) { ambientApi.setSessionArchived(any(), any()) }
        coVerify { api.setSessionArchived("s", SessionRenameRequest(archived = false)) }
        coVerify { api.setSessionArchived("s", SessionRenameRequest(archived = true)) }
    }

    @Test
    fun pinUndoRestoresHiddenWithoutConflatingItWithArchived() {
        rows["s"] = SessionInfo("s", hidden = true, archived = false)
        val vm = viewModel()
        vm.togglePin("s")
        idle()
        assertFalse(rows.getValue("s").hidden == true)
        vm.undoHistoryChange(
            vm.uiState.value.historyFeedback!!
                .id,
        )
        idle()
        assertTrue(rows.getValue("s").hidden == true)
        assertFalse(rows.getValue("s").archived == true)
        coVerify { api.setSessionPinned("s", SessionRenameRequest(pinned = false, hidden = true)) }
    }

    @Test
    fun failedOriginalOffersNoUndoAndNeverReplaysTheWrite() {
        val vm = viewModel()
        coEvery { api.setSessionPinned(any(), any()) } returns Response.error(500, "failed".toResponseBody(null))
        vm.togglePin("s")
        idle()
        assertNull(vm.uiState.value.historyFeedback)
        assertFalse(rows.getValue("s").pinned == true)
        assertTrue(
            vm.uiState.value.toastMessage!!
                .contains("Pin failed"),
        )
        coVerify(exactly = 1) { api.setSessionPinned(any(), any()) }
    }

    @Test
    fun failedUndoReportsFailureAndReloadsServerTruthWithoutReplay() {
        val vm = viewModel()
        vm.toggleArchive("s")
        idle()
        val id =
            vm.uiState.value.historyFeedback!!
                .id
        coEvery { api.setSessionArchived(any(), any()) } returns Response.error(500, "failed".toResponseBody(null))
        vm.undoHistoryChange(id)
        idle()
        assertEquals(
            R.string.sessions_undo_failed,
            vm.uiState.value.historyFeedback
                ?.messageRes,
        )
        assertFalse(
            vm.uiState.value.historyFeedback!!
                .canUndo,
        )
        assertEquals(
            listOf("other"),
            vm.uiState.value.sessions
                .map { it.id },
        )
        assertEquals(1, vm.uiState.value.total)
        vm.undoHistoryChange(id)
        idle()
        coVerify(exactly = 2) { api.setSessionArchived(any(), any()) }
    }

    @Test
    fun repeatedUndoWhileInverseIsInFlightSubmitsOnce() {
        val vm = viewModel()
        vm.togglePin("s")
        idle()
        val id =
            vm.uiState.value.historyFeedback!!
                .id
        val gate = CompletableDeferred<Response<Unit>>()
        coEvery { api.setSessionPinned("s", match { it.pinned == false }) } coAnswers { gate.await() }
        vm.undoHistoryChange(id)
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.isChangingHistory)
        vm.undoHistoryChange(id)
        rows["s"] = rows.getValue("s").copy(pinned = false)
        gate.complete(Response.success(Unit))
        idle()
        coVerify(exactly = 2) { api.setSessionPinned(any(), any()) }
        assertFalse(vm.uiState.value.isChangingHistory)
    }

    @Test
    fun twoRapidOriginalActionsCannotOverlapOrReplaceTheWrongInverse() {
        val vm = viewModel()
        val gate = CompletableDeferred<Response<SessionInfo>>()
        coEvery { api.getSessionInfo("s", any()) } coAnswers { gate.await() }
        vm.togglePin("s")
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.uiState.value.isChangingHistory)
        vm.toggleArchive("other")
        gate.complete(Response.success(rows.getValue("s")))
        idle()
        coVerify(exactly = 1) { api.setSessionPinned("s", any()) }
        coVerify(exactly = 0) { api.setSessionArchived(any(), any()) }
        assertTrue(
            vm.uiState.value.historyFeedback!!
                .canUndo,
        )
        assertFalse(vm.uiState.value.isChangingHistory)
    }

    @Test
    fun newerActionAndDismissalInvalidateOlderTokens() {
        val vm = viewModel()
        vm.togglePin("s")
        idle()
        val old =
            vm.uiState.value.historyFeedback!!
                .id
        vm.togglePin("other")
        idle()
        val latest =
            vm.uiState.value.historyFeedback!!
                .id
        vm.dismissHistoryFeedback(old)
        assertEquals(
            latest,
            vm.uiState.value.historyFeedback!!
                .id,
        )
        vm.undoHistoryChange(old)
        idle()
        coVerify(exactly = 2) { api.setSessionPinned(any(), any()) }
        vm.dismissHistoryFeedback(latest)
        vm.undoHistoryChange(latest)
        idle()
        coVerify(exactly = 2) { api.setSessionPinned(any(), any()) }
        assertNull(vm.uiState.value.historyFeedback)
    }

    @Test
    fun externalFlagChangeAndDeletionRejectTheInverseAndReconcile() {
        val vm = viewModel()
        vm.togglePin("s")
        idle()
        val id =
            vm.uiState.value.historyFeedback!!
                .id
        rows["s"] = rows.getValue("s").copy(pinned = false)
        vm.undoHistoryChange(id)
        idle()
        assertEquals(
            R.string.sessions_undo_changed,
            vm.uiState.value.historyFeedback
                ?.messageRes,
        )
        coVerify(exactly = 1) { api.setSessionPinned(any(), any()) }
        assertFalse(
            vm.uiState.value.sessions
                .first { it.id == "s" }
                .pinned == true,
        )

        vm.toggleArchive("s")
        idle()
        val archiveId =
            vm.uiState.value.historyFeedback!!
                .id
        rows.remove("s")
        vm.undoHistoryChange(archiveId)
        idle()
        assertEquals(
            R.string.sessions_undo_failed,
            vm.uiState.value.historyFeedback
                ?.messageRes,
        )
        coVerify(exactly = 1) { api.setSessionArchived(any(), any()) }
        assertEquals(1, vm.uiState.value.total)
    }

    @Test
    fun profileServerAndAuthGenerationChangesRejectOldUndo() {
        val vm = viewModel()
        val original = owner
        for (next in listOf(
            original.copy(activeProfileId = "other-profile"),
            original.copy(connectionProfileId = "server-b", baseUrl = "http://other.test/"),
            original.copy(inMemoryAuthGeneration = 2),
        )) {
            owner = original
            vm.loadSessions(forceRefresh = true)
            idle()
            vm.togglePin("s")
            idle()
            val id =
                vm.uiState.value.historyFeedback!!
                    .id
            val reads = capturedOwners.size
            owner = next
            vm.undoHistoryChange(id)
            idle()
            assertNull(vm.uiState.value.historyFeedback)
            assertEquals(reads, capturedOwners.size)
        }
        coVerify(exactly = 3) { api.setSessionPinned(any(), any()) }
    }

    @Test
    fun scopeChangeDuringFreshReadPreventsBothOriginalAndInverseWrites() {
        val vm = viewModel()
        vm.togglePin("s")
        idle()
        val id =
            vm.uiState.value.historyFeedback!!
                .id
        val original = owner
        val gate = CompletableDeferred<Response<SessionInfo>>()
        coEvery { api.getSessionInfo("s", any()) } coAnswers { gate.await() }
        vm.undoHistoryChange(id)
        dispatcher.scheduler.runCurrent()
        owner = owner.copy(activeProfileId = "other")
        gate.complete(Response.success(rows.getValue("s")))
        idle()
        coVerify(exactly = 1) { api.setSessionPinned(any(), any()) }

        owner = original
        val second = CompletableDeferred<Response<SessionInfo>>()
        coEvery { api.getSessionInfo("other", any()) } coAnswers { second.await() }
        vm.toggleArchive("other")
        dispatcher.scheduler.runCurrent()
        owner = owner.copy(connectionProfileId = "new-server")
        second.complete(Response.success(rows.getValue("other")))
        idle()
        coVerify(exactly = 0) { api.setSessionArchived(any(), any()) }
    }

    @Test
    fun allOwningHistoryCachesAreInvalidatedAndCacheFirstReloadCannotResurrectOldPin() {
        val vm = viewModel()
        vm.togglePin("s")
        idle()
        for (key in listOf(
            "CONVERSATIONS:null:cron:false",
            "CONVERSATIONS:null:cron:true",
            "AUTOMATIONS:cron:null:false",
            "AUTOMATIONS:cron:null:true",
        )) {
            verify { SessionListCacheStore.remove(owner.persistentKey(key)) }
        }
        vm.loadSessions()
        assertTrue(
            vm.uiState.value.sessions
                .first { it.id == "s" }
                .pinned == true,
        )
        idle()
        assertEquals(2, vm.uiState.value.total)
    }

    @Test
    fun listViewChangeDuringOriginalMutationReloadsTheCurrentView() {
        val vm = viewModel()
        val gate = CompletableDeferred<Response<SessionInfo>>()
        coEvery { api.getSessionInfo("s", any()) } coAnswers { gate.await() }
        vm.toggleArchive("s")
        dispatcher.scheduler.runCurrent()
        vm.toggleShowArchived()
        dispatcher.scheduler.runCurrent()
        assertTrue(
            vm.uiState.value.sessions
                .isEmpty(),
        )
        gate.complete(Response.success(rows.getValue("s")))
        idle()
        assertEquals(
            listOf("s"),
            vm.uiState.value.sessions
                .map { it.id },
        )
        assertEquals(1, vm.uiState.value.total)
        coEvery { api.getSessionInfo("s", any()) } answers { Response.success(rows.getValue("s")) }
        vm.undoHistoryChange(
            vm.uiState.value.historyFeedback!!
                .id,
        )
        idle()
        assertTrue(
            vm.uiState.value.sessions
                .isEmpty(),
        )
        assertEquals(0, vm.uiState.value.total)
    }

    @Test
    fun staleOriginalMenuCannotToggleAnExternallyChangedFlag() {
        val vm = viewModel()
        rows["s"] = rows.getValue("s").copy(pinned = true)
        vm.togglePin("s")
        idle()
        coVerify(exactly = 0) { api.setSessionPinned(any(), any()) }
        assertNull(vm.uiState.value.historyFeedback)
        assertTrue(
            vm.uiState.value.sessions
                .first { it.id == "s" }
                .pinned == true,
        )
    }

    @Test
    fun leavingScreenSuppressesLateUndoWithoutDroppingTheRequestedMutation() {
        val vm = viewModel()
        val gate = CompletableDeferred<Response<SessionInfo>>()
        coEvery { api.getSessionInfo("s", any()) } coAnswers { gate.await() }
        vm.togglePin("s")
        dispatcher.scheduler.runCurrent()
        vm.clearHistoryFeedback()
        gate.complete(Response.success(rows.getValue("s")))
        idle()
        assertTrue(rows.getValue("s").pinned == true)
        assertNull(vm.uiState.value.historyFeedback)
        assertFalse(vm.uiState.value.isChangingHistory)
    }

    @Test
    fun undoUnpinRejectsAnExternalHideInsteadOfUnhidingIt() {
        rows["s"] = SessionInfo("s", pinned = true, hidden = false)
        val vm = viewModel()
        vm.togglePin("s")
        idle()
        val id =
            vm.uiState.value.historyFeedback!!
                .id
        rows["s"] = rows.getValue("s").copy(hidden = true)
        vm.undoHistoryChange(id)
        idle()
        coVerify(exactly = 1) { api.setSessionPinned(any(), any()) }
        assertTrue(rows.getValue("s").hidden == true)
        assertFalse(rows.getValue("s").pinned == true)
        assertEquals(
            R.string.sessions_undo_changed,
            vm.uiState.value.historyFeedback
                ?.messageRes,
        )
    }

    @Test
    fun backgroundingDuringInverseWriteStillReconcilesWithoutReplayingFeedback() {
        val vm = viewModel()
        vm.togglePin("s")
        idle()
        val id =
            vm.uiState.value.historyFeedback!!
                .id
        val gate = CompletableDeferred<Response<Unit>>()
        coEvery { api.setSessionPinned("s", match { it.pinned == false }) } coAnswers { gate.await() }
        vm.undoHistoryChange(id)
        dispatcher.scheduler.runCurrent()
        vm.clearHistoryFeedback()
        rows["s"] = rows.getValue("s").copy(pinned = false)
        gate.complete(Response.success(Unit))
        idle()
        assertFalse(
            vm.uiState.value.sessions
                .first { it.id == "s" }
                .pinned == true,
        )
        assertNull(vm.uiState.value.historyFeedback)
        assertFalse(vm.uiState.value.isChangingHistory)
        coVerify(exactly = 2) { api.setSessionPinned(any(), any()) }
    }
}
