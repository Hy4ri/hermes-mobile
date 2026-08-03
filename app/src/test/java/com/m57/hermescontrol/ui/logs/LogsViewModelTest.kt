package com.m57.hermescontrol.ui.logs

import com.m57.hermescontrol.data.model.LogResponse
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApi = mockk<HermesApiService>(relaxed = true)

    /**
     * Pump the test scheduler while letting the real Dispatchers.IO hops
     * (safeLaunchLoad / withContext(IO)) land their resumptions.
     */
    private fun settle() {
        repeat(20) {
            testDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
        }
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun stubLogs(lines: List<String> = listOf("INFO line one", "ERROR line two")) {
        coEvery { mockApi.getLogs(any(), any(), any(), any()) } returns
            Response.success(LogResponse(lines = lines))
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
    fun `loadLogs requests default desktop-mirror filters`() {
        stubLogs()
        val vm = LogsViewModel()
        vm.loadLogs()
        settle()

        coVerify {
            mockApi.getLogs(
                file = "agent",
                lines = 100,
                level = "ALL",
                component = "all",
            )
        }
        assertEquals(listOf("INFO line one", "ERROR line two"), vm.uiState.value.logs)
        assertEquals(LogsFilters(), vm.uiState.value.filters)
    }

    @Test
    fun `setFilters reloads with the new server-side filters`() {
        stubLogs()
        val vm = LogsViewModel()
        vm.loadLogs()
        settle()

        val newFilters = LogsFilters(file = "errors", level = "ERROR", component = "gateway", lines = 50)
        vm.setFilters(newFilters)
        settle()

        coVerify {
            mockApi.getLogs(
                file = "errors",
                lines = 50,
                level = "ERROR",
                component = "gateway",
            )
        }
        assertEquals(newFilters, vm.uiState.value.filters)
        assertEquals(listOf("INFO line one", "ERROR line two"), vm.uiState.value.logs)
    }

    @Test
    fun `loadLogs falls back to legacy logs field when lines is absent`() {
        coEvery { mockApi.getLogs(any(), any(), any(), any()) } returns
            Response.success(LogResponse(logs = listOf("legacy line")))
        val vm = LogsViewModel()
        vm.loadLogs()
        settle()

        assertEquals(listOf("legacy line"), vm.uiState.value.logs)
    }
}
