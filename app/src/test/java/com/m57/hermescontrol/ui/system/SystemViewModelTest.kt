package com.m57.hermescontrol.ui.system

import android.app.Application
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.ActionResponse
import com.m57.hermescontrol.data.model.ActionStatusResponse
import com.m57.hermescontrol.data.model.CheckpointsResponse
import com.m57.hermescontrol.data.model.CredentialPoolResponse
import com.m57.hermescontrol.data.model.CuratorResponse
import com.m57.hermescontrol.data.model.DoctorResponse
import com.m57.hermescontrol.data.model.HookResponse
import com.m57.hermescontrol.data.model.MemoryResponse
import com.m57.hermescontrol.data.model.PortalResponse
import com.m57.hermescontrol.data.model.StatusResponse
import com.m57.hermescontrol.data.model.SystemStatsResponse
import com.m57.hermescontrol.data.model.UpdateCheckResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
class SystemViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApp = mockk<Application>(relaxed = true)
    private lateinit var mockApi: HermesApiService

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
    }

    private val viewModelStore = androidx.lifecycle.ViewModelStore()

    private fun newViewModel(): SystemViewModel =
        androidx.lifecycle.ViewModelProvider(
            viewModelStore,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    SystemViewModel(
                        mockApp,
                        migrationDispatcher = testDispatcher,
                        ioDispatcher = testDispatcher,
                    ) as T
            },
        )[SystemViewModel::class.java]

    @After
    fun teardown() {
        viewModelStore.clear()
        testDispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun stubDiagnosticReads(
        status: StatusResponse = StatusResponse(version = "1.0", gateway_running = true, gateway_pid = 9999),
        stats: SystemStatsResponse = SystemStatsResponse(cpu_percent = 25.0),
    ) {
        coEvery { mockApi.getStatus() } returns Response.success(status)
        coEvery { mockApi.getSystemStats() } returns Response.success(stats)
        coEvery { mockApi.getPortal() } returns Response.success(PortalResponse(logged_in = false))
        coEvery { mockApi.getCurator() } returns Response.success(CuratorResponse())
        coEvery { mockApi.getMemory() } returns Response.success(MemoryResponse())
        coEvery { mockApi.getCredentialPool() } returns Response.success(CredentialPoolResponse())
        coEvery { mockApi.getCheckpoints() } returns Response.success(CheckpointsResponse())
        coEvery { mockApi.getHooks() } returns Response.success(HookResponse())
        coEvery { mockApi.checkHermesUpdate(any()) } returns Response.success(UpdateCheckResponse())
    }

    @Test
    fun `openUpdateConfirm sets updateConfirmOpen to true`() {
        val viewModel = newViewModel()

        assertFalse(viewModel.uiState.value.updateConfirmOpen)

        viewModel.openUpdateConfirm()

        assertTrue(viewModel.uiState.value.updateConfirmOpen)
    }

    @Test
    fun `closeUpdateConfirm sets updateConfirmOpen to false`() {
        val viewModel = newViewModel()

        viewModel.openUpdateConfirm()
        assertTrue(viewModel.uiState.value.updateConfirmOpen)

        viewModel.closeUpdateConfirm()
        assertFalse(viewModel.uiState.value.updateConfirmOpen)
    }

    @Test
    fun `loadAll performs diagnostic reads and never calls runDoctor`() {
        stubDiagnosticReads()

        val viewModel = newViewModel()
        viewModel.loadAll()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockApi.runDoctor() }
        coVerify(atLeast = 1) { mockApi.getStatus() }
        coVerify(atLeast = 1) { mockApi.getSystemStats() }
        assertEquals(
            9999,
            viewModel.uiState.value.status
                ?.gateway_pid,
        )
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `refreshing via loadAll multiple times never invokes runDoctor`() {
        stubDiagnosticReads()

        val viewModel = newViewModel()
        viewModel.loadAll()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.loadAll()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { mockApi.runDoctor() }
        coVerify(atLeast = 2) { mockApi.getStatus() }
    }

    @Test
    fun `runDoctor explicitly triggers doctor once and starts action log polling on success`() {
        val doctorResponse = DoctorResponse(ok = true, name = "hermes-doctor", pid = 12345)
        coEvery { mockApi.runDoctor() } returns Response.success(doctorResponse)
        coEvery { mockApi.getActionStatus("hermes-doctor") } returnsMany
            listOf(
                Response.success(
                    ActionStatusResponse(
                        name = "hermes-doctor",
                        running = true,
                        lines = listOf("Checking doctor rules..."),
                    ),
                ),
                Response.success(
                    ActionStatusResponse(
                        name = "hermes-doctor",
                        running = false,
                        exit_code = 0,
                        lines = listOf("Checking doctor rules...", "All clean"),
                    ),
                ),
            )
        stubDiagnosticReads()

        val viewModel = newViewModel()
        viewModel.runDoctor()
        testDispatcher.scheduler.runCurrent()

        // S1559: doctor reuses runOperation's action-log polling, not the update progress popup.
        coVerify(exactly = 1) { mockApi.runDoctor() }
        assertEquals("hermes-doctor", viewModel.uiState.value.activeAction)
        assertFalse(viewModel.actionProgress.state.value.visible)

        testDispatcher.scheduler.advanceTimeBy(1300)
        testDispatcher.scheduler.runCurrent()
        assertEquals(
            true,
            viewModel.uiState.value.actionLog
                ?.running,
        )

        testDispatcher.scheduler.advanceTimeBy(1300)
        testDispatcher.scheduler.runCurrent()
        assertEquals(
            0,
            viewModel.uiState.value.actionLog
                ?.exit_code,
        )
        coVerify(exactly = 2) { mockApi.getActionStatus("hermes-doctor") }
        coVerify(exactly = 1) { mockApi.runDoctor() }
    }

    @Test
    fun `runDoctor handles doctor response with ok false as failure not action start`() {
        val doctorResponse = DoctorResponse(ok = false, name = "hermes-doctor", pid = null)
        coEvery { mockApi.runDoctor() } returns Response.success(doctorResponse)

        val viewModel = newViewModel()
        viewModel.runDoctor()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { mockApi.runDoctor() }
        coVerify(exactly = 0) { mockApi.getActionStatus(any()) }
        assertNull(viewModel.uiState.value.activeAction)
        verify { mockApp.getString(R.string.system_action_failed, *anyVararg()) }
    }

    @Test
    fun `runDoctor handles HTTP error response as failure`() {
        coEvery { mockApi.runDoctor() } returns Response.error(500, "Internal Server Error".toResponseBody())

        val viewModel = newViewModel()
        viewModel.runDoctor()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { mockApi.runDoctor() }
        coVerify(exactly = 0) { mockApi.getActionStatus(any()) }
        assertNull(viewModel.uiState.value.activeAction)
        verify { mockApp.getString(R.string.system_action_failed, *anyVararg()) }
    }
}
