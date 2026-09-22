package com.m57.hermescontrol.ui.model

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.local.DataScope
import com.m57.hermescontrol.data.model.ModelCapabilities
import com.m57.hermescontrol.data.model.ModelInfoResponse
import com.m57.hermescontrol.data.model.ModelOptionsResponse
import com.m57.hermescontrol.data.model.ModelProvider
import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.ModelCatalogStore
import com.m57.hermescontrol.data.ws.ModelOptionsRepository
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private var storedPinnedModels: MutableList<PinnedModel> = mutableListOf()

    private fun createViewModel(): ModelViewModel {
        val vm = ModelViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        mockkObject(AuthManager)

        storedPinnedModels = mutableListOf()

        every { AuthManager.getPinnedModels() } answers { storedPinnedModels.toList() }
        every { AuthManager.savePinnedModels(any()) } answers {
            storedPinnedModels = firstArg<List<PinnedModel>>().toMutableList()
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `init loads pinned models from AuthManager`() {
        storedPinnedModels.add(PinnedModel("openai", "gpt-4"))
        storedPinnedModels.add(PinnedModel("anthropic", "claude-3"))

        val viewModel = createViewModel()
        val state = viewModel.uiState.value

        assertEquals(2, state.pinnedModels.size)
        assertEquals("gpt-4", state.pinnedModels[0].modelName)
        assertEquals("claude-3", state.pinnedModels[1].modelName)
    }

    @Test
    fun `init with empty pinned models`() {
        val viewModel = createViewModel()
        assertTrue(
            viewModel.uiState.value.pinnedModels
                .isEmpty(),
        )
    }

    @Test
    fun `pinModel adds model to pinned list`() {
        val viewModel = createViewModel()

        viewModel.pinModel("openai", "gpt-4")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.pinnedModels.size)
        assertEquals(PinnedModel("openai", "gpt-4"), state.pinnedModels[0])
    }

    @Test
    fun `pinModel prevents duplicate pins`() {
        val viewModel = createViewModel()
        viewModel.pinModel("openai", "gpt-4")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.pinnedModels.size)

        viewModel.pinModel("openai", "gpt-4")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.pinnedModels.size)
    }

    @Test
    fun `pinModel persists to AuthManager`() {
        val viewModel = createViewModel()
        viewModel.pinModel("openai", "gpt-4")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { AuthManager.savePinnedModels(listOf(PinnedModel("openai", "gpt-4"))) }
    }

    @Test
    fun `unpinModel removes model from pinned list`() {
        val viewModel = createViewModel()
        viewModel.pinModel("openai", "gpt-4")
        viewModel.pinModel("anthropic", "claude-3")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.pinnedModels.size)

        viewModel.unpinModel("openai", "gpt-4")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.pinnedModels.size)
        assertEquals(PinnedModel("anthropic", "claude-3"), state.pinnedModels[0])
    }

    @Test
    fun `unpinModel does nothing for non-pinned model`() {
        val viewModel = createViewModel()
        viewModel.pinModel("openai", "gpt-4")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.unpinModel("openai", "nonexistent-model")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.pinnedModels.size)
    }

    @Test
    fun `unpinModel persists removal to AuthManager`() {
        val viewModel = createViewModel()
        viewModel.pinModel("openai", "gpt-4")
        viewModel.pinModel("anthropic", "claude-3")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.unpinModel("openai", "gpt-4")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { AuthManager.savePinnedModels(listOf(PinnedModel("anthropic", "claude-3"))) }
    }

    @Test
    fun `catalog scope reset clears providers from previous scope`() =
        runTest(testDispatcher) {
            var currentScope =
                DataScope(
                    connectionProfileId = "conn-1",
                    baseUrl = "http://localhost:9119",
                    activeProfileId = "default",
                    inMemoryAuthGeneration = 1L,
                )

            val response =
                ModelOptionsResponse(
                    providers =
                        listOf(
                            ModelProvider(
                                slug = "openai",
                                name = "OpenAI",
                                models = listOf("gpt-4o"),
                                capabilities = emptyMap(),
                            ),
                        ),
                )

            val store =
                ModelCatalogStore(
                    repository =
                        ModelOptionsRepository(
                            connected = { false },
                            rest = { NetworkResult.Success(response) },
                        ),
                    getCurrentScope = { currentScope },
                    scope = this,
                )

            val viewModel = ModelViewModel(catalogStore = store)

            store.ensureLoaded()
            advanceUntilIdle()
            assertEquals(response.providers, viewModel.uiState.value.providers)

            currentScope = currentScope.copy(activeProfileId = "work")
            store.onScopeChanged(currentScope)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.providers
                    .isEmpty(),
            )
        }

    @Test
    fun `model info enriches active model and is cleared on profile switch`() =
        runTest(testDispatcher) {
            var currentScope =
                DataScope(
                    connectionProfileId = "conn-1",
                    baseUrl = "http://localhost:9119",
                    activeProfileId = "default",
                    inMemoryAuthGeneration = 1L,
                )
            val response =
                ModelOptionsResponse(
                    providers =
                        listOf(
                            ModelProvider(
                                slug = "openai",
                                name = "OpenAI",
                                models = listOf("gpt-5"),
                                capabilities = mapOf("gpt-5" to ModelCapabilities(reasoning = true)),
                            ),
                        ),
                )
            val store =
                ModelCatalogStore(
                    repository =
                        ModelOptionsRepository(
                            connected = { false },
                            rest = { NetworkResult.Success(response) },
                        ),
                    getCurrentScope = { currentScope },
                    scope = this,
                )
            val requestedProfiles = mutableListOf<String?>()
            val viewModel =
                ModelViewModel(
                    catalogStore = store,
                    getCurrentScope = { currentScope },
                    ioDispatcher = testDispatcher,
                    getModelInfoCall = { profile ->
                        requestedProfiles += profile
                        NetworkResult.Success(
                            ModelInfoResponse(
                                provider = "openai",
                                model = "gpt-5",
                                capabilities = ModelCapabilities(supports_vision = true),
                            ),
                        )
                    },
                )

            store.ensureLoaded()
            viewModel.loadModelCapabilities()
            advanceUntilIdle()

            assertEquals(listOf("default"), requestedProfiles)
            assertEquals(
                true,
                viewModel.uiState.value.providers
                    .single()
                    .capabilities
                    ?.get("gpt-5")
                    ?.supports_vision,
            )

            currentScope = currentScope.copy(activeProfileId = "work")
            store.onScopeChanged(currentScope)
            store.ensureLoaded()
            advanceUntilIdle()

            assertNull(
                viewModel.uiState.value.providers
                    .single()
                    .capabilities
                    ?.get("gpt-5")
                    ?.supports_vision,
            )
        }

    @Test
    fun `pinModel shows toast when max cap reached`() {
        val viewModel = createViewModel()

        // Pin 15 models to reach cap
        for (i in 1..15) {
            viewModel.pinModel("provider-$i", "model-$i")
        }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(15, viewModel.uiState.value.pinnedModels.size)

        // Try pinning one more
        viewModel.pinModel("overflow", "too-many")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(15, state.pinnedModels.size)
        assertNull(state.pinnedModels.find { it.providerSlug == "overflow" })

        // Toast should be set
        val toastMessage = state.toastMessage
        assertTrue(toastMessage?.contains("15") == true)
    }

    @Test
    fun `pin and unpin cycle works`() {
        val viewModel = createViewModel()
        viewModel.pinModel("openai", "gpt-4")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.pinnedModels.size)

        viewModel.unpinModel("openai", "gpt-4")
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(
            viewModel.uiState.value.pinnedModels
                .isEmpty(),
        )

        // Re-pin same model after unpin
        viewModel.pinModel("openai", "gpt-4")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.pinnedModels.size)
    }
}
