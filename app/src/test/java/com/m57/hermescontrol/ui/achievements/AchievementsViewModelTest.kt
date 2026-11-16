package com.m57.hermescontrol.ui.achievements

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.AchievementsResponse
import com.m57.hermescontrol.data.model.ScanMeta
import com.m57.hermescontrol.data.model.ScanStatus
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var api: HermesApiService

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(AuthManager)
        mockkObject(ApiClient)
        api = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns api
    }

    @After
    fun tearDown() {
        unmockkObject(AuthManager)
        unmockkObject(ApiClient)
        Dispatchers.resetMain()
    }

    private fun response(
        state: String,
        stale: Boolean,
    ) = Response.success(
        AchievementsResponse(
            achievements = emptyList(),
            totalCount = 60,
            isStale = stale,
            scanMeta = ScanMeta(status = ScanStatus(state = state)),
        ),
    )

    @Test
    fun runningScan_isReReadUntilIdle_clearingScanningAndCached() =
        runTest(testDispatcher) {
            coEvery { api.getAchievements() } returnsMany
                listOf(response("running", stale = true), response("idle", stale = false))

            val vm = AchievementsViewModel()
            vm.loadAchievements(forceRefresh = true)
            advanceUntilIdle()

            assertEquals("idle", vm.uiState.value.scanState)
            assertFalse(vm.uiState.value.isStale)
            coVerify(exactly = 2) { api.getAchievements() }
        }

    @Test
    fun idleScan_isNotPolled() =
        runTest(testDispatcher) {
            coEvery { api.getAchievements() } returns response("idle", stale = false)

            val vm = AchievementsViewModel()
            vm.loadAchievements(forceRefresh = true)
            advanceUntilIdle()
            testDispatcher.scheduler.advanceTimeBy(SCAN_POLL_INTERVAL_MS * 3)
            advanceUntilIdle()

            coVerify(exactly = 1) { api.getAchievements() }
        }
}
