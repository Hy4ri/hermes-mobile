package com.m57.hermescontrol.ui.gateway

import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher

        mockApi = mockk()
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `loadStatus success updates state with status data`() =
        runTest {
            val mockResponse = mockk<StatusResponse>()
            coEvery { mockApi.getStatus() } returns Response.success(mockResponse)

            val viewModel = GatewayViewModel()

            // Before calling, status should be null
            assertNull(viewModel.uiState.value.status)
            assertFalse(viewModel.uiState.value.isLoading)

            viewModel.loadStatus()

            // Assert loading state (before coroutine executes)
            assertTrue(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.errorMessage)

            testDispatcher.scheduler.advanceUntilIdle()

            // Assert final state
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(mockResponse, viewModel.uiState.value.status)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `loadStatus error updates state with error message`() =
        runTest {
            coEvery { mockApi.getStatus() } returns Response.error(500, "Server Error".toResponseBody(null))

            val viewModel = GatewayViewModel()

            viewModel.loadStatus()
            assertTrue(viewModel.uiState.value.isLoading)

            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.status)
            assertTrue(viewModel.uiState.value.errorMessage?.contains("500") == true)
        }

    @Test
    fun `loadStatus network exception updates state with error message`() =
        runTest {
            coEvery { mockApi.getStatus() } throws RuntimeException("Network timeout")

            val viewModel = GatewayViewModel()

            viewModel.loadStatus()

            testDispatcher.scheduler.advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isLoading)
            assertNull(viewModel.uiState.value.status)
            assertTrue(viewModel.uiState.value.errorMessage?.contains("Network timeout") == true)
        }
}
