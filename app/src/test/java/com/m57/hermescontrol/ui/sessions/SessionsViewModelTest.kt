package com.m57.hermescontrol.ui.sessions

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

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createViewModel(): SessionsViewModel {
        val vm = SessionsViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
}
