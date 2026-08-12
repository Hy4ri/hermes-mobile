package com.m57.hermescontrol.ui.sessions

import com.m57.hermescontrol.data.model.SessionInfo
import com.m57.hermescontrol.data.model.SessionListResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)

    private fun createViewModel(): SessionsViewModel {
        val vm = SessionsViewModel()
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
    fun `clean search snippet extracts text from JSON payload`() {
        assertEquals(
            "Find the deployment logs",
            cleanSearchSnippet("{\"role\":\"user\",\"content\":\">>>Find<<< the deployment logs\"}"),
        )
    }

    @Test
    fun `loadMore appends the next page and dedupes overlapping ids`() {
        val vm = createViewModel()

        // Page 1: 2 sessions of 3 total — hasMore stays true.
        coEvery { mockApi.getSessions(any(), any(), any()) } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("s-1"), SessionInfo("s-2")),
                    total = 3,
                ),
            )
        vm.loadSessions()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("s-1", "s-2"), vm.uiState.value.sessions.map { it.id })
        assertTrue(vm.uiState.value.hasMore)
        assertFalse(vm.uiState.value.isLoadingMore)

        // Page 2 overlaps page 1 (offset churn: a new session landed on top
        // between loads) — the duplicate id must not double-append.
        coEvery { mockApi.getSessions(any(), any(), any()) } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("s-3"), SessionInfo("s-2")),
                    total = 3,
                ),
            )
        vm.loadMore()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("s-1", "s-2", "s-3"), vm.uiState.value.sessions.map { it.id })
        assertEquals(3, vm.uiState.value.total)
        assertFalse(vm.uiState.value.hasMore)
        assertFalse(vm.uiState.value.isLoadingMore)
    }

    @Test
    fun `loadMore is a no-op while a load is already running`() {
        val vm = createViewModel()

        coEvery { mockApi.getSessions(any(), any(), any()) } returns
            Response.success(
                SessionListResponse(
                    sessions = listOf(SessionInfo("s-1")),
                    total = 2,
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
        coVerify(exactly = 2) { mockApi.getSessions(any(), any(), any()) }
        assertEquals(listOf("s-1"), vm.uiState.value.sessions.map { it.id })
        assertFalse(vm.uiState.value.isLoadingMore)
    }
}
