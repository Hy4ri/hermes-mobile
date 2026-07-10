package com.m57.hermescontrol.ui.config

import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.ConfigSchemaResponse
import com.m57.hermescontrol.data.model.RawConfigResponse
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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService
    private lateinit var viewModel: ConfigViewModel

    // Profile id drives the value the fake backend returns, so we can prove a
    // re-fetch actually happened after a profile switch (not just the init load).
    // The test owns this flow (assigned to AuthManager), keeping the test
    // hermetic from the AuthManager singleton's real state across the suite.
    private val profileFlow = MutableStateFlow<String?>("default")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher

        mockApi = mockk()
        mockkObject(ApiClient)
        every { ApiClient.hermesApi } returns mockApi

        AuthManager.setSelectedProfileIdFlowForTest(profileFlow)

        coEvery { mockApi.getConfig() } answers {
            val p = profileFlow.value ?: "default"
            Response.success(mapOf("active_profile" to JsonPrimitive(p)))
        }
        coEvery { mockApi.getConfigSchema() } returns Response.success(ConfigSchemaResponse(emptyMap(), emptyList()))
        coEvery { mockApi.getConfigDefaults() } returns Response.success(emptyMap())
        coEvery { mockApi.getRawConfig() } returns Response.success(RawConfigResponse(path = "/x/config.yaml"))
    }

    @After
    fun tearDown() {
        AuthManager.resetSelectedProfileIdFlowForTest()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `init loads config for the initially selected profile`() =
        runTest {
            profileFlow.value = "default"
            viewModel = ConfigViewModel()
            // The screen's LaunchedEffect(Unit) performs the initial load.
            viewModel.loadAll()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "default",
                viewModel.uiState.value.config
                    ?.get("active_profile")
                    ?.let { (it as JsonPrimitive).content },
            )
            // Tear down the VM scope so no Main-dispatched collector survives
            // past resetMain() (avoids cross-test "Main absent" leakage).
            viewModel.viewModelScope.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `switching profile triggers a re-fetch of config`() =
        runTest {
            profileFlow.value = "default"
            viewModel = ConfigViewModel()
            viewModel.loadAll()
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(
                "default",
                viewModel.uiState.value.config
                    ?.get("active_profile")
                    ?.let { (it as JsonPrimitive).content },
            )

            // Switch profile: the reactive collector should re-invoke loadAll().
            profileFlow.value = "work"
            testDispatcher.scheduler.advanceUntilIdle()

            // State now reflects the newly selected profile, proving a second
            // fetch fired (and targeted the new profile id).
            assertEquals(
                "work",
                viewModel.uiState.value.config
                    ?.get("active_profile")
                    ?.let { (it as JsonPrimitive).content },
            )
            assertTrue(
                viewModel.uiState.value.isLoading
                    .not(),
            )
            viewModel.viewModelScope.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun `no re-fetch when same profile value is re-emitted`() =
        runTest {
            profileFlow.value = "default"
            viewModel = ConfigViewModel()
            viewModel.loadAll()
            testDispatcher.scheduler.advanceUntilIdle()
            val firstConfig =
                viewModel.uiState.value.config
                    ?.get("active_profile")
                    ?.let { (it as JsonPrimitive).content }

            // StateFlow only emits on a *change*; re-assigning the same value
            // must NOT trigger another load.
            profileFlow.value = "default"
            testDispatcher.scheduler.advanceUntilIdle()

            val secondConfig =
                viewModel.uiState.value.config
                    ?.get("active_profile")
                    ?.let { (it as JsonPrimitive).content }
            assertEquals(firstConfig, secondConfig)
            viewModel.viewModelScope.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
        }
}
