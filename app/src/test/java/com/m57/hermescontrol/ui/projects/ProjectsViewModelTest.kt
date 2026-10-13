package com.m57.hermescontrol.ui.projects

import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.DiscoveredRepo
import com.m57.hermescontrol.data.model.ProjectFolder
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.data.ws.ProjectsRepository
import com.m57.hermescontrol.data.ws.ProjectsResult
import com.m57.hermescontrol.data.ws.WsEvent
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<ProjectsRepository>()

    private val app =
        ProjectInfo(id = "p_1", name = "App", folders = listOf(ProjectFolder("/code/app", isPrimary = true)))
    private val old = ProjectInfo(id = "p_2", name = "Old", archived = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.list() } returns ProjectsResult.Ok(listOf(app, old))
        coEvery { repo.discoveredRepos() } returns emptyList()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() =
        ProjectsViewModel(
            repository = repo,
            scopes = MutableStateFlow<DataScope?>(null),
            events = emptyFlow<WsEvent>(),
        )

    @Test
    fun activeAndArchivedFiltersSplitTheList() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(
                listOf("p_1"),
                vm.uiState.value.visibleProjects
                    .map { it.id },
            )
            vm.setFilter(ProjectsFilter.ARCHIVED)
            assertEquals(
                listOf("p_2"),
                vm.uiState.value.visibleProjects
                    .map { it.id },
            )
        }

    @Test
    fun searchMatchesFolderPaths() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setQuery("code/app")
            assertEquals(
                listOf("p_1"),
                vm.uiState.value.visibleProjects
                    .map { it.id },
            )
        }

    @Test
    fun blankNameIsRejectedWithoutCallingTheGateway() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.save(ProjectDraft(name = "  "))
            assertEquals(ProjectsViewModel.NAME_REQUIRED, vm.uiState.value.editorError)
            coVerify(exactly = 0) { repo.create(any(), any(), any()) }
        }

    @Test
    fun createFailureKeepsEditorOpenWithGatewayMessage() =
        runTest(dispatcher) {
            coEvery { repo.create("Dup", "/code/app", null) } returns ProjectsResult.Failed("folder already belongs")
            val vm = viewModel()
            advanceUntilIdle()
            vm.startCreate()
            vm.save(ProjectDraft(name = "Dup", folder = " /code/app "))
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.editor)
            assertEquals("folder already belongs", vm.uiState.value.editorError)
        }

    @Test
    fun editSendsUpdateAndClosesEditor() =
        runTest(dispatcher) {
            coEvery { repo.update("p_1", "Renamed", "", "hsl(0 68% 58%)") } returns ProjectsResult.Ok(Unit)
            val vm = viewModel()
            advanceUntilIdle()
            vm.startEdit(app)
            vm.save(
                vm.uiState.value.editor!!
                    .copy(name = "Renamed", color = "hsl(0 68% 58%)"),
            )
            advanceUntilIdle()
            assertNull(vm.uiState.value.editor)
            coVerify { repo.update("p_1", "Renamed", "", "hsl(0 68% 58%)") }
        }

    @Test
    fun deleteRunsOnlyAfterConfirm() =
        runTest(dispatcher) {
            coEvery { repo.delete("p_1") } returns ProjectsResult.Ok(Unit)
            val vm = viewModel()
            advanceUntilIdle()
            vm.requestDelete(app)
            coVerify(exactly = 0) { repo.delete(any()) }
            vm.confirmDelete()
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.delete("p_1") }
            assertNull(vm.uiState.value.deleteTarget)
        }

    @Test
    fun failedMutationSurfacesToast() =
        runTest(dispatcher) {
            coEvery { repo.setArchived("p_1", true) } returns ProjectsResult.Failed("no such project")
            val vm = viewModel()
            advanceUntilIdle()
            vm.setArchived(app, true)
            advanceUntilIdle()
            assertEquals("no such project", vm.uiState.value.toastMessage)
            assertNull(vm.uiState.value.busyId)
        }

    @Test
    fun suggestionsHideReposAlreadyInAProject() =
        runTest(dispatcher) {
            coEvery { repo.discoveredRepos() } returns
                listOf(
                    DiscoveredRepo("/code/app", "app", 3),
                    DiscoveredRepo("/opt/monit", "monit", 2),
                )
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(
                listOf("/opt/monit"),
                vm.uiState.value.suggestions
                    .map { it.root },
            )
        }

    @Test
    fun projectChangeEvents_coalesceAndRefreshProjectsListSilently() =
        runTest(dispatcher) {
            val eventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 16)
            val updatedApp = app.copy(name = "App Refreshed")
            val vm =
                ProjectsViewModel(
                    repository = repo,
                    scopes = MutableStateFlow<DataScope?>(null),
                    events = eventsFlow,
                )
            advanceUntilIdle()
            assertEquals(
                "App",
                vm.uiState.value.projects
                    .first()
                    .name,
            )
            coVerify(exactly = 1) { repo.list() }

            // Stub new list return
            coEvery { repo.list() } returns ProjectsResult.Ok(listOf(updatedApp, old))

            // Multiple rapid project change events within the 250ms coalescing window
            eventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.PROJECTS, data = null))
            eventsFlow.emit(WsEvent.ChangeEvent(type = "projects.changed", data = mapOf("id" to "p_1")))
            advanceTimeBy(100L)
            // Should not have reloaded yet (250ms debounce)
            coVerify(exactly = 1) { repo.list() }

            eventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.PROJECTS, data = null))
            advanceTimeBy(260L)
            advanceUntilIdle()

            // Coalesced into exactly one refresh reload
            coVerify(exactly = 2) { repo.list() }
            assertEquals(
                "App Refreshed",
                vm.uiState.value.projects
                    .first()
                    .name,
            )
        }

    @Test
    fun nonMatchingChangeEvents_areIgnoredByProjectsViewModel() =
        runTest(dispatcher) {
            val eventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 16)
            val vm =
                ProjectsViewModel(
                    repository = repo,
                    scopes = MutableStateFlow<DataScope?>(null),
                    events = eventsFlow,
                )
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.list() }

            eventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.SESSIONS, data = null))
            eventsFlow.emit(WsEvent.ChangeEvent(type = "cron.changed", data = null))
            advanceTimeBy(500L)
            advanceUntilIdle()

            // No extra reload
            coVerify(exactly = 1) { repo.list() }
        }

    @Test
    fun projectChangeEvents_preserveOpenEditorAndDoNotBlankState() =
        runTest(dispatcher) {
            val eventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 16)
            val vm =
                ProjectsViewModel(
                    repository = repo,
                    scopes = MutableStateFlow<DataScope?>(null),
                    events = eventsFlow,
                )
            advanceUntilIdle()
            // Open the editor with an in-progress draft (save() would submit and close it).
            vm.startCreate(DiscoveredRepo(root = "/code/app", label = "Draft Name"))
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.editor)

            // Trigger change event while editor is open
            eventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.PROJECTS, data = null))
            advanceTimeBy(300L)
            advanceUntilIdle()

            // Editor remains open and visible projects not wiped to blank
            assertNotNull(vm.uiState.value.editor)
            assertEquals(
                "Draft Name",
                vm.uiState.value.editor
                    ?.name,
            )
            assertEquals(false, vm.uiState.value.isLoading)
            assertEquals(1, vm.uiState.value.visibleProjects.size)
        }

    @Test
    fun projectChangeEvents_scopedToCurrentDataScope_fenceStaleResults() =
        runTest(dispatcher) {
            val scope1 = DataScope("c1", "http://server1", "p1", 0L)
            val scope2 = DataScope("c2", "http://server2", "p2", 0L)
            val scopesFlow = MutableStateFlow<DataScope?>(scope1)
            val eventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 16)

            val vm =
                ProjectsViewModel(
                    repository = repo,
                    scopes = scopesFlow,
                    events = eventsFlow,
                )
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.list() }

            // Switch scope while an event refresh or pending call would be under scope1
            scopesFlow.value = scope2
            advanceUntilIdle()
            // Scope change itself triggers load
            coVerify(atLeast = 2) { repo.list() }
        }

    @Test
    fun scopeChangeWhileDiscoveredReposPendingFencesOldResultsAfterRepositoryListSucceeds() =
        runTest(dispatcher) {
            val scope1 = DataScope("c1", "http://server1", "p1", 0L)
            val scope2 = DataScope("c2", "http://server2", "p2", 0L)
            val scopesFlow = MutableStateFlow<DataScope?>(scope1)

            val oldProjects = listOf(ProjectInfo(id = "p_old", name = "Old Project"))
            val oldDiscoveredRepos = listOf(DiscoveredRepo("/repo/old", "old", 1))
            val pendingOldDiscovered = CompletableDeferred<List<DiscoveredRepo>>()

            // Scope 1: repo.list() succeeds immediately, but discoveredRepos() stays pending
            coEvery { repo.list() } returns ProjectsResult.Ok(oldProjects)
            coEvery { repo.discoveredRepos() } coAnswers { pendingOldDiscovered.await() }

            val vm =
                ProjectsViewModel(
                    repository = repo,
                    scopes = scopesFlow,
                    events = emptyFlow(),
                )

            // Let repo.list() complete while discoveredRepos() is still suspended
            dispatcher.scheduler.runCurrent()

            // Change scope before discoveredRepos() finishes
            val newProjects = listOf(ProjectInfo(id = "p_new", name = "New Project"))
            coEvery { repo.list() } returns ProjectsResult.Ok(newProjects)
            coEvery { repo.discoveredRepos() } returns emptyList()

            scopesFlow.value = scope2

            // Now release the old discoveredRepos call
            pendingOldDiscovered.complete(oldDiscoveredRepos)
            advanceUntilIdle()

            // None of old projects or suggestions from old scope should be published in new scope
            val state = vm.uiState.value
            assertEquals(listOf("p_new"), state.projects.map { it.id })
            assertEquals(listOf("p_new"), state.visibleProjects.map { it.id })
            assertTrue(state.suggestions.none { it.root == "/repo/old" })
            assertTrue(state.suggestions.isEmpty())
        }

    @Test
    fun standardLoadStaleResultIgnoredAfterScopeChange() =
        runTest(dispatcher) {
            val scope1 = DataScope("c1", "http://server1", "p1", 0L)
            val scope2 = DataScope("c2", "http://server2", "p2", 0L)
            val scopesFlow = MutableStateFlow<DataScope?>(scope1)

            val staleProjects = listOf(ProjectInfo(id = "p_stale", name = "Stale Project"))
            val pendingStaleList = CompletableDeferred<ProjectsResult<List<ProjectInfo>>>()

            coEvery { repo.list() } coAnswers { pendingStaleList.await() }
            coEvery { repo.discoveredRepos() } returns emptyList()

            val vm =
                ProjectsViewModel(
                    repository = repo,
                    scopes = scopesFlow,
                    events = emptyFlow(),
                )

            // Let load start and suspend at repo.list()
            dispatcher.scheduler.runCurrent()

            // Change scope before stale load finishes
            val activeProjects = listOf(ProjectInfo(id = "p_active", name = "Active Project"))
            coEvery { repo.list() } returns ProjectsResult.Ok(activeProjects)
            scopesFlow.value = scope2

            // Complete the stale deferred response from scope1
            pendingStaleList.complete(ProjectsResult.Ok(staleProjects))
            advanceUntilIdle()

            // Stale load result from scope1 must be ignored, active projects must be present
            val state = vm.uiState.value
            assertEquals(listOf("p_active"), state.projects.map { it.id })
            assertEquals(listOf("p_active"), state.visibleProjects.map { it.id })
            assertTrue(state.projects.none { it.id == "p_stale" })
        }

    @Test
    fun rapidChangeEventsBurstCountsOnceAndPreservesOpenEditor() =
        runTest(dispatcher) {
            val eventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 16)
            val updatedApp = app.copy(name = "App Updated")
            val vm =
                ProjectsViewModel(
                    repository = repo,
                    scopes = MutableStateFlow<DataScope?>(null),
                    events = eventsFlow,
                )
            advanceUntilIdle()
            coVerify(exactly = 1) { repo.list() }

            // Open editor with an in-progress draft
            // Open the editor with an in-progress draft (save() would submit and close it).
            vm.startCreate(DiscoveredRepo(root = "/code/app", label = "InProgress Draft"))
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.editor)
            assertEquals(
                "InProgress Draft",
                vm.uiState.value.editor
                    ?.name,
            )

            coEvery { repo.list() } returns ProjectsResult.Ok(listOf(updatedApp, old))

            // Fire rapid burst of change events within 250ms window
            eventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.PROJECTS, data = null))
            advanceTimeBy(50L)
            eventsFlow.emit(WsEvent.ChangeEvent(type = "projects.changed", data = mapOf("id" to "p_1")))
            advanceTimeBy(80L)
            eventsFlow.emit(WsEvent.ChangeEvent(type = ChangeEvents.PROJECTS, data = null))
            advanceTimeBy(100L)
            eventsFlow.emit(WsEvent.ChangeEvent(type = "projects.changed", data = null))

            // Still within debounce window, list should not have been called again yet
            coVerify(exactly = 1) { repo.list() }

            // Advance past the 250ms debounce window from the last event
            advanceTimeBy(260L)
            advanceUntilIdle()

            // The rapid burst should have triggered repo.list exactly once more (2 calls total)
            coVerify(exactly = 2) { repo.list() }

            val state = vm.uiState.value
            // Editor must be preserved across the silent refresh
            assertNotNull(state.editor)
            assertEquals("InProgress Draft", state.editor?.name)
            // Projects list must be updated silently without blanking state
            assertEquals(false, state.isLoading)
            assertEquals("App Updated", state.projects.first().name)
        }
}
