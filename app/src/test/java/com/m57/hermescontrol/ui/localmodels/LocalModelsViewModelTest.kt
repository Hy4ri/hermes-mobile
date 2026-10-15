package com.m57.hermescontrol.ui.localmodels

import com.m57.hermescontrol.data.model.LocalModelJob
import com.m57.hermescontrol.data.model.LocalModelJobActionResponse
import com.m57.hermescontrol.data.model.LocalModelJobsResponse
import com.m57.hermescontrol.data.model.LocalModelsHardware
import com.m57.hermescontrol.data.model.LocalModelsStatus
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
class LocalModelsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val api = mockk<HermesApiService>(relaxed = true)

    private val downloading =
        LocalModelJob(
            jobId = "abc",
            kind = "model-download",
            target = "Qwen (Q4)",
            status = "running",
            totalBytes = 1000,
            doneBytes = 250,
            canPause = true,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns api
        coEvery { api.getLocalModelsStatus() } returns Response.success(LocalModelsStatus(runtimeInstalled = true))
        coEvery { api.getLocalModelsHardware() } returns Response.success(LocalModelsHardware(gpuName = "RTX"))
        coEvery { api.getLocalModelJobs() } returns Response.success(LocalModelJobsResponse(listOf(downloading)))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `load populates status hardware and jobs`() {
        val vm = LocalModelsViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        val s = vm.uiState.value
        assertFalse(s.isLoading)
        assertTrue(s.status!!.runtimeInstalled)
        assertEquals("RTX", s.hardware?.gpuName)
        assertEquals(listOf("abc"), s.jobs.map { it.jobId })
        assertFalse(s.unavailable)
    }

    @Test
    fun `404 from older gateway marks feature unavailable instead of error`() {
        coEvery { api.getLocalModelsStatus() } returns Response.error(404, "".toResponseBody())
        val vm = LocalModelsViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.unavailable)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `server error without snapshot surfaces error message`() {
        coEvery { api.getLocalModelsStatus() } returns Response.error(500, "".toResponseBody())
        val vm = LocalModelsViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.unavailable)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test
    fun `pause sends the job id and refreshes`() {
        coEvery { api.pauseLocalModelDownload(any()) } returns
            Response.success(LocalModelJobActionResponse(ok = true, paused = true))
        val vm = LocalModelsViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.pause(downloading)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { api.pauseLocalModelDownload(match { it.jobId == "abc" }) }
        assertTrue(
            vm.uiState.value.busyJobIds
                .isEmpty(),
        )
    }

    @Test
    fun `resume sends the job id`() {
        coEvery { api.resumeLocalModelDownload(any()) } returns
            Response.success(LocalModelJobActionResponse(ok = true, resumed = true))
        val vm = LocalModelsViewModel()
        vm.resume(downloading.copy(status = "paused", canResume = true))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { api.resumeLocalModelDownload(match { it.jobId == "abc" }) }
    }

    @Test
    fun `failed pause toasts the error and clears busy flag`() {
        coEvery { api.pauseLocalModelDownload(any()) } returns Response.error(404, "".toResponseBody())
        val vm = LocalModelsViewModel()
        vm.pause(downloading)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.uiState.value.toastMessage)
        assertTrue(
            vm.uiState.value.busyJobIds
                .isEmpty(),
        )
    }
}
