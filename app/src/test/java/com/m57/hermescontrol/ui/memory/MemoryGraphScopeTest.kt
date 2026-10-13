package com.m57.hermescontrol.ui.memory

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.model.LearningGraphResponse
import com.m57.hermescontrol.data.model.MemoryResponse
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.HermesApiService
import com.m57.hermescontrol.data.session.ProfileSwitchCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryGraphScopeTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService
    private val mockSwitchFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val mockConnSwitchFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val sampleMemory = MemoryResponse(active = "local")
    private val sampleGraph = LearningGraphResponse(nodes = emptyList())

    @Before
    fun setUp() {
        AuthManager.resetAuthStateForTest()
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        mockkObject(ProfileSwitchCoordinator)
        every { ProfileSwitchCoordinator.switched } returns mockSwitchFlow
        every { ProfileSwitchCoordinator.connectionSwitched } returns mockConnSwitchFlow
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        coEvery { mockApi.getMemory() } returns Response.success(sampleMemory)
    }

    @After
    fun tearDown() {
        AuthManager.resetAuthStateForTest()
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `load passes captured active profile to getLearningGraph`() {
        AuthManager.setActiveProfileId("work-profile")
        coEvery { mockApi.getLearningGraph(profile = "work-profile") } returns Response.success(sampleGraph)

        val vm = MemoryViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { mockApi.getLearningGraph(profile = "work-profile") }
        assertEquals(sampleGraph, vm.uiState.value.learningGraph)
    }

    @Test
    fun `load passes fallback active profile when scope profile sentinel applies`() {
        // Without an active profile set, it uses captured requestScope.activeProfileId or fallback sentinel (e.g. "default")
        AuthManager.setBaseUrlForTest("http://127.0.0.1:9119")
        val expectedProfile = requireNotNull(AuthManager.currentDataScope()).activeProfileId
        coEvery { mockApi.getLearningGraph(profile = expectedProfile) } returns Response.success(sampleGraph)

        val vm = MemoryViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { mockApi.getLearningGraph(profile = expectedProfile) }
        assertEquals(sampleGraph, vm.uiState.value.learningGraph)
    }

    @Test
    fun `scope change after memory await fences late graph publication`() {
        AuthManager.setActiveProfileId("initial-profile")

        val graphDeferred = CompletableDeferred<Response<LearningGraphResponse>>()
        coEvery { mockApi.getLearningGraph(profile = any()) } coAnswers { graphDeferred.await() }

        val vm = MemoryViewModel()
        vm.load()

        // Advance until memory call has finished awaiting but graph call is still suspended
        testDispatcher.scheduler.runCurrent()

        // Memory should be populated
        assertEquals(
            "local",
            vm.uiState.value.memory
                ?.active,
        )
        assertNull(vm.uiState.value.learningGraph)

        // Now change scope / profile BEFORE graph completes
        AuthManager.setActiveProfileId("different-profile")

        // Now resume graph deferred
        graphDeferred.complete(Response.success(sampleGraph))
        testDispatcher.scheduler.advanceUntilIdle()

        // Late graph response must be fenced — NOT published to UI state because scope changed
        assertNull(
            "Late graph publication must be fenced when scope changes after memory await",
            vm.uiState.value.learningGraph,
        )
    }
}
