package com.m57.hermescontrol.ui.system

import android.app.Application
import com.m57.hermescontrol.data.model.GatewayMigrationPlan
import com.m57.hermescontrol.data.model.GatewayMigrationProfile
import com.m57.hermescontrol.data.model.GatewayMigrationStartResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.ui.common.ActionProgressPhase
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayMigrationViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val mockApp = mockk<Application>(relaxed = true)
    private lateinit var mockApi: HermesApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `unsupported migration plan is capability local`() {
        coEvery { mockApi.getGatewayMigrationPlan() } returns
            Response.error(404, "unsupported".toResponseBody(null))

        val viewModel = SystemViewModel(mockApp, migrationDispatcher = testDispatcher)
        viewModel.loadMigrationPlan()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.migrationSupported == true)
        assertEquals(false, viewModel.uiState.value.migrationSupported)
        assertNull(viewModel.uiState.value.migrationPlan)
    }

    @Test
    fun `migration plan exposes backend states without inventing eligibility rules`() {
        val plan =
            GatewayMigrationPlan(
                profiles = listOf(GatewayMigrationProfile(profile = "default")),
                eligible = false,
                blockers = emptyList(),
                notices = listOf("explicit migration is available"),
            )
        coEvery { mockApi.getGatewayMigrationPlan() } returns Response.success(plan)

        val viewModel = SystemViewModel(mockApp, migrationDispatcher = testDispatcher)
        viewModel.loadMigrationPlan()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(plan, viewModel.uiState.value.migrationPlan)
        assertEquals(
            false,
            viewModel.uiState.value.migrationPlan
                ?.eligible,
        )
        assertTrue(
            viewModel.uiState.value.migrationPlan
                ?.blockers
                ?.isEmpty() == true,
        )
    }

    @Test
    fun `migration POST only starts action tracking and does not report completion`() {
        val plan = GatewayMigrationPlan(eligible = false)
        coEvery { mockApi.getGatewayMigrationPlan() } returns Response.success(plan)
        coEvery { mockApi.startGatewayMigration() } returns
            Response.success(
                GatewayMigrationStartResponse(
                    ok = true,
                    pid = 1234,
                    name = "gateway-migrate",
                ),
            )

        val viewModel = SystemViewModel(mockApp, migrationDispatcher = testDispatcher)
        viewModel.loadMigrationPlan()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.startMigration()
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.migrationProgress.state.value.visible)
        assertEquals(ActionProgressPhase.RUNNING, viewModel.migrationProgress.state.value.phase)
        assertEquals("gateway-migrate", viewModel.migrationProgress.state.value.actionName)
        coVerify { mockApi.startGatewayMigration() }
    }

    @Test
    fun `migration POST with an unsuccessful body is shown as rejected`() {
        coEvery { mockApi.getGatewayMigrationPlan() } returns Response.success(GatewayMigrationPlan())
        coEvery { mockApi.startGatewayMigration() } returns
            Response.success(
                GatewayMigrationStartResponse(
                    ok = false,
                    name = "gateway-migrate",
                ),
            )

        val viewModel = SystemViewModel(mockApp, migrationDispatcher = testDispatcher)
        viewModel.loadMigrationPlan()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.startMigration()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(ActionProgressPhase.FAILED, viewModel.migrationProgress.state.value.phase)
        assertEquals("Migration was rejected by the backend", viewModel.migrationProgress.state.value.error)
        coVerify { mockApi.startGatewayMigration() }
    }

    @Test
    fun `already multiplexed plan never starts backend action`() {
        coEvery { mockApi.getGatewayMigrationPlan() } returns
            Response.success(GatewayMigrationPlan(alreadyMultiplexed = true))

        val viewModel = SystemViewModel(mockApp, migrationDispatcher = testDispatcher)
        viewModel.loadMigrationPlan()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.startMigration()
        testDispatcher.scheduler.runCurrent()

        assertFalse(viewModel.migrationProgress.state.value.visible)
        assertTrue(
            viewModel.uiState.value.toastMessage
                ?.contains("already multiplexed") == true,
        )
        coVerify(exactly = 0) { mockApi.startGatewayMigration() }
    }

    @Test
    fun `migration with blockers never starts backend action`() {
        coEvery { mockApi.getGatewayMigrationPlan() } returns
            Response.success(GatewayMigrationPlan(blockers = listOf("duplicate credential")))

        val viewModel = SystemViewModel(mockApp, migrationDispatcher = testDispatcher)
        viewModel.loadMigrationPlan()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.startMigration()
        testDispatcher.scheduler.runCurrent()

        assertFalse(viewModel.migrationProgress.state.value.visible)
        assertTrue(
            viewModel.uiState.value.toastMessage
                ?.contains("blocked") == true,
        )
        coVerify(exactly = 0) { mockApi.startGatewayMigration() }
    }
}
