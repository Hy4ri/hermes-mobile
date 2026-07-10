package com.m57.hermescontrol.ui.skills

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.Skill
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService
    private lateinit var app: Application
    private lateinit var viewModel: SkillsViewModel
    private var callCount = 0

    // Test owns this flow (assigned to AuthManager), keeping the test hermetic
    // from the AuthManager singleton's real state across the suite.
    private val profileFlow = MutableStateFlow<String?>("default")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher

        app = mockk(relaxed = true)
        mockApi = mockk()
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi

        AuthManager.setSelectedProfileIdFlowForTest(profileFlow)

        coEvery { mockApi.getSkills() } answers {
            callCount++
            // Return a single marker skill whose name encodes the active profile.
            val p = profileFlow.value ?: "default"
            Response.success(listOf(Skill(name = "skill-for-$p", enabled = true)))
        }
    }

    @After
    fun tearDown() {
        AuthManager.resetSelectedProfileIdFlowForTest()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `init loads skills for the initially selected profile`() =
        runTest {
            profileFlow.value = "default"
            viewModel = SkillsViewModel(app)
            // The screen's LaunchedEffect(Unit) performs the initial load.
            viewModel.loadSkills()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("skill-for-default", viewModel.uiState.value.skills.firstOrNull()?.name)
            assertEquals(1, callCount)
            // Tear down the VM scope so no Main-dispatched collector survives
            // past resetMain() (avoids cross-test "Main absent" leakage).
            viewModel.viewModelScope.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `switching profile re-fetches skills for the new profile`() =
        runTest {
            profileFlow.value = "default"
            viewModel = SkillsViewModel(app)
            viewModel.loadSkills()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("skill-for-default", viewModel.uiState.value.skills.firstOrNull()?.name)

            profileFlow.value = "work"
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("skill-for-work", viewModel.uiState.value.skills.firstOrNull()?.name)
            assertEquals(2, callCount) // init + one re-fetch on switch
            assertTrue(viewModel.uiState.value.isLoading.not())
            viewModel.viewModelScope.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
        }
}
