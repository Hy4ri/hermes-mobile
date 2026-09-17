package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.LiveSessionSnapshot
import com.m57.hermescontrol.data.model.ProjectInfo
import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.model.SessionLiveStatus
import com.m57.hermescontrol.data.model.SessionSearchResponse
import com.m57.hermescontrol.data.model.SessionSearchResult
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.ws.ChangeEventHub
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.data.ws.ConnectionStatus
import com.m57.hermescontrol.data.ws.ProjectsSource
import com.m57.hermescontrol.data.ws.SessionLiveStatusSource
import com.m57.hermescontrol.data.ws.WsEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)

    private class FakeSessionLiveStatusSource : SessionLiveStatusSource {
        val eventsFlow = MutableSharedFlow<WsEvent>(extraBufferCapacity = 16)
        val connectionStatusFlow = MutableStateFlow(ConnectionStatus.CONNECTED)
        var snapshotToReturn: LiveSessionSnapshot? = null
        var fetchCallCount = 0
        var snapshotDeferred: CompletableDeferred<LiveSessionSnapshot?>? = null

        override suspend fun fetchActiveSessionsSnapshot(): LiveSessionSnapshot? {
            fetchCallCount++
            val deferred = snapshotDeferred
            return if (deferred != null) deferred.await() else snapshotToReturn
        }

        override val events: Flow<WsEvent> = eventsFlow
        override val connectionStatus: StateFlow<ConnectionStatus> = connectionStatusFlow
    }

    private class FakeProjectsSource : ProjectsSource {
        var projectsToReturn: List<ProjectInfo>? = emptyList()
        var fetchCallCount = 0
        var projectsDeferred: CompletableDeferred<List<ProjectInfo>?>? = null

        override suspend fun fetchProjects(): List<ProjectInfo>? {
            fetchCallCount++
            return projectsDeferred?.await() ?: projectsToReturn
        }
    }

    private fun createViewModel(
        source: SessionLiveStatusSource = FakeSessionLiveStatusSource(),
        projectsSource: ProjectsSource = FakeProjectsSource(),
    ): SessionsViewModel {
        val vm = SessionsViewModel(liveStatusSource = source, projectsSource = projectsSource)
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `wire paging metadata drives a second request without changing history paging`() {
        val first =
            kotlinx.serialization.json.Json.decodeFromString<SessionSearchResponse>(
                """{"results":[{"session_id":"a"},{"session_id":"a"}],"has_more":true,"next_offset":20}""",
            )
        coEvery { mockApi.searchSessions("launch", null, null, "cron", 20, 0) } returns Response.success(first)
        coEvery { mockApi.searchSessions("launch", null, null, "cron", 20, 20) } returns
            Response.success(
                SessionSearchResponse(listOf(SessionSearchResult("b")), has_more = false),
            )
        val vm = createViewModel()
        vm.setSearchQuery("launch")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.loadMoreSearch()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("a", "b"),
            vm.uiState.value.searchResults
                .map { it.session_id },
        )
        assertFalse(vm.uiState.value.searchHasMore)
        assertFalse(vm.uiState.value.hasMore)
        coVerify(exactly = 1) { mockApi.searchSessions("launch", null, null, "cron", 20, 20) }
    }

    @Test
    fun `bulk delete removes selected search hits and preserves others`() {
        mockkObject(com.m57.hermescontrol.data.local.AuthManager)
        every {
            com.m57.hermescontrol.data.local.AuthManager
                .getLastOpenedSessionId()
        } returns null
        coEvery { mockApi.searchSessions("launch", null, null, "cron", 20, 0) } returns
            Response.success(SessionSearchResponse(listOf(SessionSearchResult("a"), SessionSearchResult("b"))))
        coEvery { mockApi.bulkDeleteSessions(any()) } returns
            Response.success(
                com.m57.hermescontrol.data.model
                    .BulkDeleteResponse(ok = true, deleted = 1),
            )
        coEvery { mockApi.getSessions(any(), any(), any(), any(), any()) } returns
            Response.success(SessionListResponse(sessions = emptyList(), total = 0))
        val vm = createViewModel()
        vm.setSearchQuery("launch")
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleSessionSelection("a")
        vm.confirmBulkDelete()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("b"),
            vm.uiState.value.searchResults
                .map { it.session_id },
        )
        assertTrue(
            vm.uiState.value.selectedIds
                .isEmpty(),
        )
        assertFalse(vm.uiState.value.isDeletingBulk)
    }

    @Test
    fun `tracking loads the project list`() {
        val projects = FakeProjectsSource().apply { projectsToReturn = listOf(ProjectInfo(id = "p_1", name = "App")) }
        val vm = createViewModel(projectsSource = projects)

        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()

        assertEquals(
            listOf("App"),
            vm.uiState.value.projects
                .map { it.name },
        )
        vm.stopLiveStatusTracking()
    }

    @Test
    fun `stopping tracking cancels an in-flight project fetch`() {
        val pending = CompletableDeferred<List<ProjectInfo>?>()
        val projects = FakeProjectsSource().apply { projectsDeferred = pending }
        val vm = createViewModel(projectsSource = projects)
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, projects.fetchCallCount)

        vm.stopLiveStatusTracking()
        pending.complete(listOf(ProjectInfo(id = "p_late", name = "Late")))
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(
            vm.uiState.value.projects
                .isEmpty(),
        )
    }

    @Test
    fun `loadSessions without tracking does not fetch projects`() {
        val projects = FakeProjectsSource()
        val vm = createViewModel(projectsSource = projects)

        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, projects.fetchCallCount)
    }

    @Test
    fun `failed project refresh keeps the last good list`() {
        val projects = FakeProjectsSource().apply { projectsToReturn = listOf(ProjectInfo(id = "p_1", name = "App")) }
        val vm = createViewModel(projectsSource = projects)
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()

        projects.projectsToReturn = null
        vm.loadSessions()
        testDispatcher.scheduler.runCurrent()

        assertEquals(2, projects.fetchCallCount)
        assertEquals(
            listOf("App"),
            vm.uiState.value.projects
                .map { it.name },
        )
        vm.stopLiveStatusTracking()
    }

    @Test
    fun `reconnecting refreshes the project list`() {
        val live = FakeSessionLiveStatusSource()
        val projects = FakeProjectsSource()
        val vm = createViewModel(source = live, projectsSource = projects)
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()
        live.connectionStatusFlow.value = ConnectionStatus.DISCONNECTED
        testDispatcher.scheduler.runCurrent()
        val before = projects.fetchCallCount

        projects.projectsToReturn = listOf(ProjectInfo(id = "p_2", name = "Web"))
        live.connectionStatusFlow.value = ConnectionStatus.CONNECTED
        testDispatcher.scheduler.runCurrent()

        assertTrue(projects.fetchCallCount > before)
        assertEquals(
            listOf("Web"),
            vm.uiState.value.projects
                .map { it.name },
        )
        vm.stopLiveStatusTracking()
    }

    @Test
    fun `search is pending immediately before debounce`() {
        val vm = createViewModel()
        vm.setSearchQuery("deploy")
        assertTrue(vm.uiState.value.isSearching)
        assertNull(vm.uiState.value.searchError)
        vm.setSearchQuery("")
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `search pagination keeps results on failure and retries same offset`() {
        coEvery { mockApi.searchSessions("deploy", null, null, "cron", 20, 0) } returns
            Response.success(SessionSearchResponse(listOf(SessionSearchResult("one")), true, 1))
        coEvery { mockApi.searchSessions("deploy", null, null, "cron", 20, 1) } returns
            Response.error(503, "busy".toResponseBody()) andThen
            Response.error(503, "busy".toResponseBody()) andThen
            Response.error(503, "busy".toResponseBody()) andThen
            Response.success(
                SessionSearchResponse(listOf(SessionSearchResult("one"), SessionSearchResult("two")), false),
            )
        val vm = createViewModel()
        vm.setSearchQuery("deploy")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.searchHasMore)
        vm.loadMoreSearch()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("one"),
            vm.uiState.value.searchResults
                .map { it.session_id },
        )
        assertNotNull(vm.uiState.value.searchLoadMoreError)
        assertNull(vm.uiState.value.searchError)
        vm.loadMoreSearch()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            listOf("one", "two"),
            vm.uiState.value.searchResults
                .map { it.session_id },
        )
        assertFalse(vm.uiState.value.searchHasMore)
        assertFalse(vm.uiState.value.hasMore)
    }

    @Test
    fun `legacy search and non advancing page do not loop`() {
        coEvery { mockApi.searchSessions("old", null, null, "cron", 20, 0) } returns
            Response.success(SessionSearchResponse(listOf(SessionSearchResult("one"))))
        coEvery { mockApi.searchSessions("new", null, null, "cron", 20, 0) } returns
            Response.success(SessionSearchResponse(listOf(SessionSearchResult("two")), true, 0))
        val vm = createViewModel()
        for (query in listOf("old", "new")) {
            vm.setSearchQuery(query)
            testDispatcher.scheduler.advanceUntilIdle()
            assertFalse(vm.uiState.value.searchHasMore)
            vm.loadMoreSearch()
        }
        coVerify(exactly = 2) { mockApi.searchSessions(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `blank query resets search mode`() {
        val vm = createViewModel()
        vm.setSearchQuery("something")
        vm.setSearchQuery("")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("", vm.uiState.value.searchQuery)
        assertFalse(vm.uiState.value.isSearchMode)
        assertEquals(0, vm.uiState.value.searchResults.size)
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `non-blank query enters search mode and resolves`() {
        val vm = createViewModel()
        vm.setSearchQuery("hello")
        // state is set synchronously
        assertEquals("hello", vm.uiState.value.searchQuery)
        assertTrue(vm.uiState.value.isSearchMode)
        // advance past debounce + (failing, offline) network call
        testDispatcher.scheduler.advanceTimeBy(500)
        testDispatcher.scheduler.advanceUntilIdle()
        // Either way the spinner must stop and the query persists.
        assertFalse(vm.uiState.value.isSearching)
        assertEquals("hello", vm.uiState.value.searchQuery)
    }

    @Test
    fun `select all uses the IDs shown in the current view`() {
        val vm = createViewModel()

        vm.selectAll(setOf("search-session-1", "search-session-2"))

        assertEquals(
            setOf("search-session-1", "search-session-2"),
            vm.uiState.value.selectedIds,
        )
    }

    @Test
    fun `sections request conversations excluding cron and automations from cron`() {
        val vm = createViewModel()
        coEvery { mockApi.getSessions(50, 0, any(), null, "cron") } returns
            Response.success(SessionListResponse(listOf(SessionInfo("conversation"))))
        coEvery { mockApi.getSessions(50, 0, any(), "cron", null) } returns
            Response.success(SessionListResponse(listOf(SessionInfo("automation", source = "cron"))))

        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.toggleSelecting()
        vm.toggleSessionSelection("conversation")

        vm.selectSection(HistorySection.AUTOMATIONS)
        assertFalse(vm.uiState.value.isSelecting)
        assertTrue(
            vm.uiState.value.selectedIds
                .isEmpty(),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("automation"),
            vm.uiState.value.sessions
                .map { it.id },
        )
        coVerify(exactly = 1) { mockApi.getSessions(50, 0, any(), null, "cron") }
        coVerify(exactly = 1) { mockApi.getSessions(50, 0, any(), "cron", null) }
    }

    @Test
    fun `section change clears old results and repeats query in the new scope`() {
        val vm = createViewModel()
        coEvery { mockApi.searchSessions("deploy", null, null, "cron") } returns
            Response.success(
                SessionSearchResponse(listOf(SessionSearchResult(session_id = "conversation-hit"))),
            )
        coEvery { mockApi.searchSessions("deploy", null, "cron", null) } returns
            Response.success(
                SessionSearchResponse(listOf(SessionSearchResult(session_id = "automation-hit", source = "cron"))),
            )

        vm.setSearchQuery("deploy")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "conversation-hit",
            vm.uiState.value.searchResults
                .single()
                .session_id,
        )
        vm.selectAll(setOf("conversation-hit"))

        vm.selectSection(HistorySection.AUTOMATIONS)
        assertEquals("deploy", vm.uiState.value.searchQuery)
        assertTrue(
            vm.uiState.value.searchResults
                .isEmpty(),
        )
        assertTrue(
            vm.uiState.value.selectedIds
                .isEmpty(),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "automation-hit",
            vm.uiState.value.searchResults
                .single()
                .session_id,
        )
        coVerify(exactly = 1) { mockApi.searchSessions("deploy", null, null, "cron") }
        coVerify(exactly = 1) { mockApi.searchSessions("deploy", null, "cron", null) }
    }

    @Test
    fun `automation pagination keeps the source filter and combines runs by job`() {
        val vm = createViewModel()
        val firstPage = (0 until 50).map { SessionInfo("cron_job-a_20260905_1800%02d".format(it), source = "cron") }
        val lastRun = SessionInfo("cron_job-a_20260905_190000", source = "cron")
        coEvery { mockApi.getSessions(50, 0, any(), "cron", null) } returns
            Response.success(SessionListResponse(firstPage, total = 51, limit = 50))
        coEvery { mockApi.getSessions(50, 50, any(), "cron", null) } returns
            Response.success(SessionListResponse(listOf(lastRun), total = 51, limit = 50, offset = 50))

        vm.selectSection(HistorySection.AUTOMATIONS)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.hasMore)
        vm.loadMore()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.hasMore)
        assertEquals(51, vm.uiState.value.total)
        assertEquals(firstPage + lastRun, automationGroups(vm.uiState.value.sessions).single().sessions)
        coVerify(exactly = 1) { mockApi.getSessions(50, 50, any(), "cron", null) }
    }

    @Test
    fun `late load from a previous section cannot replace the current rows`() {
        val vm = createViewModel()
        val oldResponse = kotlinx.coroutines.CompletableDeferred<Response<SessionListResponse>>()
        coEvery { mockApi.getSessions(50, 0, any(), null, "cron") } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldResponse.await() }
        }
        coEvery { mockApi.getSessions(50, 0, any(), "cron", null) } returns
            Response.success(SessionListResponse(listOf(SessionInfo("automation", source = "cron"))))

        vm.loadSessions()
        testDispatcher.scheduler.runCurrent()
        vm.selectSection(HistorySection.AUTOMATIONS)
        testDispatcher.scheduler.runCurrent()
        oldResponse.complete(Response.success(SessionListResponse(listOf(SessionInfo("old-conversation")))))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(HistorySection.AUTOMATIONS, vm.uiState.value.section)
        assertEquals(
            listOf("automation"),
            vm.uiState.value.sessions
                .map { it.id },
        )
    }

    // ── Pagination (fluid load-more) ──────────────────────────────────────

    @Test
    fun `loadSessions with fewer than page size items caps total and sets hasMore false`() {
        val vm = createViewModel()

        // Server reports total = 10 (e.g. cross-profile count), but returns only 2 sessions (< PAGE_SIZE 50).
        // hasMore must be false and total must be capped at 2 to prevent infinite auto-load loops.
        coEvery { mockApi.getSessions(any(), any(), any(), null, "cron") } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("s-1"), SessionInfo("s-2")),
                    total = 10,
                ),
            )
        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("s-1", "s-2"),
            vm.uiState.value.sessions
                .map { it.id },
        )
        assertEquals(2, vm.uiState.value.total)
        assertFalse(vm.uiState.value.hasMore)
        assertFalse(vm.uiState.value.isLoadingMore)
    }

    @Test
    fun `loadMore appends the next page and dedupes overlapping ids`() {
        val vm = createViewModel()

        val page1Sessions = (1..50).map { SessionInfo("s-$it") }
        // Page 1: 50 sessions of 51 total — hasMore stays true.
        coEvery {
            mockApi.getSessions(
                limit = 50,
                offset = 0,
                order = any(),
                source = null,
                excludeSources = "cron",
            )
        } returns
            Response.success(
                SessionListResponse(
                    sessions = page1Sessions,
                    total = 51,
                ),
            )
        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(50, vm.uiState.value.sessions.size)
        assertTrue(vm.uiState.value.hasMore)
        assertFalse(vm.uiState.value.isLoadingMore)

        // Page 2 overlaps page 1 (offset churn: a new session landed on top
        // between loads) — the duplicate id must not double-append.
        coEvery {
            mockApi.getSessions(
                limit = 50,
                offset = 50,
                order = any(),
                source = null,
                excludeSources = "cron",
            )
        } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("s-51"), SessionInfo("s-50")),
                    total = 51,
                ),
            )
        vm.loadMore()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(51, vm.uiState.value.sessions.size)
        assertEquals(51, vm.uiState.value.total)
        assertFalse(vm.uiState.value.hasMore)
        assertFalse(vm.uiState.value.isLoadingMore)
    }

    @Test
    fun `loadMore is a no-op while a load is already running`() {
        val vm = createViewModel()

        val page1Sessions = (1..50).map { SessionInfo("s-$it") }
        coEvery {
            mockApi.getSessions(
                limit = 50,
                offset = 0,
                order = any(),
                source = null,
                excludeSources = "cron",
            )
        } returns
            Response.success(
                SessionListResponse(
                    sessions = page1Sessions,
                    total = 52,
                ),
            )
        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()

        // Fire two loadMore calls back-to-back before the dispatcher runs: the
        // first sets isLoadingMore=true synchronously, the second must be dropped.
        vm.loadMore()
        vm.loadMore()
        testDispatcher.scheduler.advanceUntilIdle()

        // loadSessions (1) + exactly one loadMore (1) = 2 API hits total.
        coVerify(exactly = 2) { mockApi.getSessions(any(), any(), any(), null, "cron") }
        assertFalse(vm.uiState.value.isLoadingMore)
    }

    // ── Pin / unpin ────────────────────────────────────────────────────────

    @Test
    fun `loaded list retains server order and identifies pinned sessions`() {
        val vm = createViewModel()
        coEvery { mockApi.getSessions(any(), any(), any(), null, "cron") } returns
            Response.success(
                SessionListResponse(
                    sessions =
                        listOf(
                            SessionInfo("recent", pinned = false),
                            SessionInfo("old-pinned", pinned = true),
                        ),
                    total = 2,
                ),
            )
        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()

        // Sessions retain server order (family grouping is handled by flattenSessionsWithBranches)
        assertEquals(
            listOf("recent", "old-pinned"),
            vm.uiState.value.sessions
                .map { it.id },
        )
        // pinnedSessions derives the pinned items
        assertEquals(
            listOf("old-pinned"),
            vm.uiState.value.pinnedSessions
                .map { it.id },
        )
    }

    @Test
    fun `togglePin updates the session pinned state without mangling session order`() {
        val vm = createViewModel()
        coEvery { mockApi.getSessions(any(), any(), any(), null, "cron") } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("s-1"), SessionInfo("s-2")),
                    total = 2,
                ),
            )
        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { mockApi.setSessionPinned(any(), any()) } returns Response.success(Unit)
        vm.togglePin("s-2")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("s-1", "s-2"),
            vm.uiState.value.sessions
                .map { it.id },
        )
        assertEquals(
            true,
            vm.uiState.value.sessions
                .first { it.id == "s-2" }
                .pinned,
        )
        assertEquals(
            listOf("s-2"),
            vm.uiState.value.pinnedSessions
                .map { it.id },
        )
        assertEquals("Session pinned", vm.uiState.value.toastMessage)
    }

    @Test
    fun `togglePin failure keeps the order and surfaces a toast`() {
        val vm = createViewModel()
        coEvery { mockApi.getSessions(any(), any(), any(), null, "cron") } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("s-1", pinned = true), SessionInfo("s-2")),
                    total = 2,
                ),
            )
        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { mockApi.setSessionPinned(any(), any()) } returns
            retrofit2.Response.error(500, "".toResponseBody(null))
        vm.togglePin("s-2")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("s-1", "s-2"),
            vm.uiState.value.sessions
                .map { it.id },
        )
        assertFalse(
            vm.uiState.value.sessions[1]
                .pinned ?: false,
        )
        assertNotNull(vm.uiState.value.toastMessage)
        assertTrue(
            vm.uiState.value.toastMessage!!
                .contains("Pin failed"),
        )
    }

    @Test
    fun `displaySessions filters out hidden sessions by default and shows them on toggleShowHidden`() {
        val vm = createViewModel()
        coEvery { mockApi.getSessions(any(), any(), any(), null, "cron") } returns
            Response.success(
                SessionListResponse(
                    sessions =
                        listOf(
                            SessionInfo("s-visible-1", hidden = false),
                            SessionInfo("s-hidden", hidden = true),
                            SessionInfo("s-visible-2", hidden = null),
                        ),
                    total = 3,
                ),
            )
        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.hasHiddenSessions)
        assertFalse(vm.uiState.value.showHidden)
        assertEquals(
            listOf("s-visible-1", "s-visible-2"),
            vm.uiState.value.displaySessions
                .map { it.id },
        )

        vm.toggleShowHidden()
        assertTrue(vm.uiState.value.showHidden)
        assertEquals(
            listOf("s-visible-1", "s-hidden", "s-visible-2"),
            vm.uiState.value.displaySessions
                .map { it.id },
        )

        vm.toggleShowHidden()
        assertFalse(vm.uiState.value.showHidden)
        assertEquals(
            listOf("s-visible-1", "s-visible-2"),
            vm.uiState.value.displaySessions
                .map { it.id },
        )
    }

    @Test
    fun `toggleHide updates hidden status and surfaces toast`() {
        val vm = createViewModel()
        coEvery { mockApi.getSessions(any(), any(), any(), null, "cron") } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("s-1", hidden = false)),
                    total = 1,
                ),
            )
        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()

        coEvery { mockApi.setSessionHidden(any(), any()) } returns Response.success(Unit)
        vm.toggleHide("s-1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            true,
            vm.uiState.value.sessions
                .first()
                .hidden,
        )
        assertEquals("Session hidden", vm.uiState.value.toastMessage)

        vm.toggleHide("s-1")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            false,
            vm.uiState.value.sessions
                .first()
                .hidden,
        )
        assertEquals("Session unhidden", vm.uiState.value.toastMessage)
    }

    @Test
    fun `stitchMissingParents fetches missing parent sessions up to cap`() {
        val vm = createViewModel()
        coEvery { mockApi.getSessions(any(), any(), any(), null, "cron") } returns
            Response.success(
                SessionListResponse(
                    sessions =
                        listOf(
                            SessionInfo("child", parent_session_id = "parent", title = "Child"),
                        ),
                    total = 1,
                ),
            )
        coEvery { mockApi.getSessionInfo("parent") } returns
            Response.success(
                SessionInfo("parent", title = "Fetched Parent"),
            )

        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.uiState.value.sessions.size)
        assertTrue(
            vm.uiState.value.sessions
                .any { it.id == "parent" },
        )
    }

    @Test
    fun `togglePinnedExpanded toggles expanded state`() {
        val vm = createViewModel()
        assertTrue(vm.uiState.value.pinnedExpanded)
        vm.togglePinnedExpanded()
        assertFalse(vm.uiState.value.pinnedExpanded)
        vm.togglePinnedExpanded()
        assertTrue(vm.uiState.value.pinnedExpanded)
    }

    // ── Live Session Status Tracking (issue #1101) ───────────────────────

    @Test
    fun `initial snapshot hydration updates liveStatuses`() {
        val source = FakeSessionLiveStatusSource()
        source.snapshotToReturn =
            LiveSessionSnapshot(
                statusByStoredId = mapOf("stored-1" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            )

        val vm = createViewModel(source)
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()

        assertEquals(SessionLiveStatus.WORKING, vm.uiState.value.liveStatuses["stored-1"])
        assertEquals(1, source.fetchCallCount)
        vm.stopLiveStatusTracking()
    }

    @Test
    fun `successful absence reaping clears statuses not in snapshot`() {
        val source = FakeSessionLiveStatusSource()
        source.snapshotToReturn =
            LiveSessionSnapshot(
                statusByStoredId = mapOf("stored-1" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            )

        val vm = createViewModel(source)
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()
        assertEquals(SessionLiveStatus.WORKING, vm.uiState.value.liveStatuses["stored-1"])

        // Second snapshot returns empty
        source.snapshotToReturn = LiveSessionSnapshot(emptyMap(), emptyMap())
        vm.refreshLiveStatuses()
        testDispatcher.scheduler.runCurrent()

        assertTrue(
            vm.uiState.value.liveStatuses
                .isEmpty(),
        )
        assertEquals(2, source.fetchCallCount)
        vm.stopLiveStatusTracking()
    }

    @Test
    fun `working to idle event transition via SessionInfo updates liveStatuses`() {
        val source = FakeSessionLiveStatusSource()
        source.snapshotToReturn =
            LiveSessionSnapshot(
                statusByStoredId = mapOf("stored-1" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            )

        val vm = createViewModel(source)
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()

        assertEquals(SessionLiveStatus.WORKING, vm.uiState.value.liveStatuses["stored-1"])

        // Send running = false
        source.eventsFlow.tryEmit(
            WsEvent.SessionInfo(
                data =
                    mapOf(
                        "session_id" to "rt-1",
                        "stored_session_id" to "stored-1",
                        "running" to false,
                    ),
            ),
        )
        testDispatcher.scheduler.runCurrent()

        assertNull(vm.uiState.value.liveStatuses["stored-1"])
        vm.stopLiveStatusTracking()
    }

    @Test
    fun `disconnect clears immediately and reconnect rehydrates`() {
        val source = FakeSessionLiveStatusSource()
        source.snapshotToReturn =
            LiveSessionSnapshot(
                statusByStoredId = mapOf("stored-1" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            )

        val vm = createViewModel(source)
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()
        assertEquals(SessionLiveStatus.WORKING, vm.uiState.value.liveStatuses["stored-1"])

        // Disconnect
        source.connectionStatusFlow.value = ConnectionStatus.DISCONNECTED
        testDispatcher.scheduler.runCurrent()
        assertTrue(
            vm.uiState.value.liveStatuses
                .isEmpty(),
        )

        // Reconnect
        source.snapshotToReturn =
            LiveSessionSnapshot(
                statusByStoredId = mapOf("stored-1" to SessionLiveStatus.WAITING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            )
        source.connectionStatusFlow.value = ConnectionStatus.CONNECTED
        testDispatcher.scheduler.runCurrent()

        assertEquals(SessionLiveStatus.WAITING, vm.uiState.value.liveStatuses["stored-1"])
        vm.stopLiveStatusTracking()
    }

    @Test
    fun `late pre-disconnect response is ignored`() {
        val source = FakeSessionLiveStatusSource()
        val deferred = CompletableDeferred<LiveSessionSnapshot?>()
        source.snapshotDeferred = deferred

        val vm = createViewModel(source)
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()

        // Disconnect before deferred completes
        source.connectionStatusFlow.value = ConnectionStatus.DISCONNECTED
        testDispatcher.scheduler.runCurrent()

        // Complete the late response
        deferred.complete(
            LiveSessionSnapshot(
                statusByStoredId = mapOf("stored-1" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            ),
        )
        testDispatcher.scheduler.runCurrent()

        // Must remain empty because the response was from the previous generation
        assertTrue(
            vm.uiState.value.liveStatuses
                .isEmpty(),
        )
        vm.stopLiveStatusTracking()
    }

    @Test
    fun `repeated start is idempotent`() {
        val source = FakeSessionLiveStatusSource()
        source.snapshotToReturn = LiveSessionSnapshot()

        val vm = createViewModel(source)
        vm.startLiveStatusTracking()
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, source.fetchCallCount)
        vm.stopLiveStatusTracking()
    }

    @Test
    fun `stop cancels ticker and clears state`() {
        val source = FakeSessionLiveStatusSource()
        source.snapshotToReturn =
            LiveSessionSnapshot(
                statusByStoredId = mapOf("stored-1" to SessionLiveStatus.WORKING),
                storedIdByRuntimeId = mapOf("rt-1" to "stored-1"),
            )

        val vm = createViewModel(source)
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, vm.uiState.value.liveStatuses.size)

        vm.stopLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()
        assertTrue(
            vm.uiState.value.liveStatuses
                .isEmpty(),
        )

        // Advancing time past the 30s poll interval should NOT trigger another fetch
        testDispatcher.scheduler.advanceTimeBy(60_000)
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, source.fetchCallCount)
    }

    @Test
    fun `change bursts coalesce into at most one trailing request`() {
        val source = FakeSessionLiveStatusSource()
        val deferred1 = CompletableDeferred<LiveSessionSnapshot?>()
        source.snapshotDeferred = deferred1

        val vm = createViewModel(source)
        vm.startLiveStatusTracking()
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, source.fetchCallCount)

        // Multiple change events while request 1 is in flight
        source.snapshotDeferred = CompletableDeferred()
        ChangeEventHub.emit(WsEvent.ChangeEvent(ChangeEvents.SESSIONS, null))
        ChangeEventHub.emit(WsEvent.ChangeEvent(ChangeEvents.SESSIONS, null))
        testDispatcher.scheduler.runCurrent()

        // Call count should still be 1 (in-flight)
        assertEquals(1, source.fetchCallCount)

        // Complete request 1
        deferred1.complete(LiveSessionSnapshot())
        testDispatcher.scheduler.runCurrent()

        // Request 2 was coalesced and launched as the trailing request
        assertEquals(2, source.fetchCallCount)
        vm.stopLiveStatusTracking()
    }
}
