package com.m57.hermescontrol.ui.memory

import com.m57.hermescontrol.data.model.MemoryProviderStatusRow
import com.m57.hermescontrol.data.model.MemoryResponse
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Memory management home (moved out of the System tab). No
 * mockkStatic(Dispatchers) — the ViewModel does no explicit IO hop, so
 * setMain(StandardTestDispatcher) alone is deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockApi: HermesApiService

    private val memoryResponse =
        MemoryResponse(
            active = "web",
            builtin_files =
                com.m57.hermescontrol.data.model.BuiltinFileSizes(
                    memory = 1024,
                    user = 2048,
                ),
            providers =
                listOf(
                    MemoryProviderStatusRow(name = "web", status = "ready"),
                    MemoryProviderStatusRow(name = "tts", status = "needs_config"),
                ),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(ApiClient)
        mockApi = mockk(relaxed = true)
        every { ApiClient.hermesApi } returns mockApi
        coEvery { mockApi.getMemory() } returns Response.success(memoryResponse)
    }

    @After
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `load fetches memory status with providers`() {
        val vm = MemoryViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("web", state.memory?.active)
        assertEquals(2, state.memory?.providers?.size)
        assertEquals(1024L, state.memory?.builtin_files?.memory)
    }

    @Test
    fun `load also fetches learning graph for self-improvement section`() {
        val graph = com.m57.hermescontrol.data.model.LearningGraphResponse(nodes = emptyList())
        coEvery { mockApi.getLearningGraph() } returns Response.success(graph)
        val vm = MemoryViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.getLearningGraph() }
        assertEquals(graph, vm.uiState.value.learningGraph)
    }

    @Test
    fun `load failure surfaces error message`() {
        coEvery { mockApi.getMemory() } returns
            Response.error(500, "boom".toResponseBody())
        val vm = MemoryViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.uiState.value.memory)
        assertTrue(vm.uiState.value.errorMessage.orEmpty().isNotBlank())
    }

    @Test
    fun `resetMemory posts target and reloads`() {
        coEvery { mockApi.resetMemory(mapOf("target" to "all")) } returns
            Response.success(emptyMap<String, String>())
        val vm = MemoryViewModel()
        vm.load()
        testDispatcher.scheduler.advanceUntilIdle()
        vm.resetMemory("all")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { mockApi.resetMemory(mapOf("target" to "all")) }
        assertEquals("Memory (all) reset successfully", vm.uiState.value.toastMessage)
        // One load + one reload after reset.
        coVerify(exactly = 2) { mockApi.getMemory() }
    }

    @Test
    fun `resetMemory failure shows error toast`() {
        coEvery { mockApi.resetMemory(mapOf("target" to "memory")) } returns
            Response.error(500, "boom".toResponseBody())
        val vm = MemoryViewModel()
        vm.resetMemory("memory")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.toastMessage.orEmpty().contains("Failed to reset memory"))
        assertNull(vm.uiState.value.resetting)
    }
}
