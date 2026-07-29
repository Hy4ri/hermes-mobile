package com.m57.hermescontrol.ui.starmap

import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StarMapViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiService = mockk<HermesApiService>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial uiState has loading default`() {
        val viewModel = StarMapViewModel()
        assertNotNull(viewModel.uiState.value)
    }

    @Test
    fun `selecting node updates selectedNodeId`() {
        val viewModel = StarMapViewModel()
        viewModel.selectNode("memory:user:0")
        assertEquals("memory:user:0", viewModel.uiState.value.selectedNodeId)

        viewModel.selectNode(null)
        assertNull(viewModel.uiState.value.selectedNodeId)
    }

    @Test
    fun `filtering and search queries update state`() {
        val viewModel = StarMapViewModel()
        viewModel.setFilter(StarMapFilter.MEMORIES)
        assertEquals(StarMapFilter.MEMORIES, viewModel.uiState.value.filter)

        viewModel.setSearchQuery("git")
        assertEquals("git", viewModel.uiState.value.searchQuery)
    }
}
