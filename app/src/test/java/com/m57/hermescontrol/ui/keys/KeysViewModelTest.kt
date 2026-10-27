package com.m57.hermescontrol.ui.keys

import com.m57.hermescontrol.data.model.EnvVarConfig
import com.m57.hermescontrol.data.model.EnvVarUpdate
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class KeysViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private fun createViewModel(): KeysViewModel {
        val vm = KeysViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setNewKeyName updates state flow correctly`() {
        val viewModel = createViewModel()

        assertEquals("", viewModel.uiState.value.newKeyName)

        viewModel.setNewKeyName("MY_NEW_API_KEY")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("MY_NEW_API_KEY", viewModel.uiState.value.newKeyName)
    }

    @Test
    fun `setNewKeyValue updates state flow correctly`() {
        val viewModel = createViewModel()

        assertEquals("", viewModel.uiState.value.newKeyValue)

        viewModel.setNewKeyValue("dummy_token_value_that_is_long_enough")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("dummy_token_value_that_is_long_enough", viewModel.uiState.value.newKeyValue)
    }

    @Test
    fun testToggleCategory() {
        // Arrange
        val viewModel = createViewModel()

        // Seed initial categories via reflection (no test-only seeding path exists in source)
        val uiStateField = KeysViewModel::class.java.getDeclaredField("_uiState")
        uiStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val uiStateFlow =
            uiStateField.get(viewModel) as MutableStateFlow<KeysUiState>

        val initialCategories =
            listOf(
                CategorySection(
                    name = "LLM Providers",
                    vars =
                        mapOf(
                            "OPENAI_API_KEY" to
                                EnvVarConfig(
                                    isSet = true,
                                    category = "LLM Providers",
                                    isPassword = true,
                                ),
                        ),
                    expanded = false,
                ),
                CategorySection(
                    name = "Tool API Keys",
                    vars =
                        mapOf(
                            "WEATHER_API_KEY" to
                                EnvVarConfig(
                                    isSet = true,
                                    category = "Tool API Keys",
                                    isPassword = true,
                                ),
                        ),
                    expanded = true,
                ),
            )
        uiStateFlow.update { it.copy(categories = initialCategories) }

        // Act - expand an unexpanded category
        viewModel.toggleCategory("LLM Providers")

        // Assert
        var categories = viewModel.uiState.value.categories
        assertEquals(2, categories.size)
        assertTrue(
            "LLM Providers should be expanded",
            categories.first { it.name == "LLM Providers" }.expanded,
        )
        assertTrue(
            "Tool API Keys should remain expanded",
            categories.first { it.name == "Tool API Keys" }.expanded,
        )

        // Act - collapse it again
        viewModel.toggleCategory("LLM Providers")

        // Assert
        categories = viewModel.uiState.value.categories
        assertEquals(2, categories.size)
        assertFalse(
            "LLM Providers should be collapsed",
            categories.first { it.name == "LLM Providers" }.expanded,
        )
        assertTrue(
            "Tool API Keys should remain expanded",
            categories.first { it.name == "Tool API Keys" }.expanded,
        )

        // Act - toggle the other category, ensure first stays untouched
        viewModel.toggleCategory("Tool API Keys")

        // Assert
        categories = viewModel.uiState.value.categories
        assertFalse(
            "LLM Providers should stay collapsed",
            categories.first { it.name == "LLM Providers" }.expanded,
        )
        assertFalse(
            "Tool API Keys should now be collapsed",
            categories.first { it.name == "Tool API Keys" }.expanded,
        )
    }

    @Test
    fun `loadKeys and updateKey complete successfully using flow first suspension`() =
        runTest {
            val mockApi = mockk<HermesApiService>()
            mockkObject(ApiClient)
            every { ApiClient.hermesApi } returns mockApi
            try {
                coEvery { mockApi.getEnvVars() } returns
                    Response.success(mapOf("KEY1" to EnvVarConfig(true, "value1", "desc", null, null, false)))
                coEvery { mockApi.updateEnvVar(any()) } returns Response.success(Unit)

                val viewModel = KeysViewModel()
                viewModel.loadKeys()

                val loadedState =
                    viewModel.uiState.first { state ->
                        !state.isLoading && state.categories.isNotEmpty()
                    }

                val allVars =
                    loadedState.categories
                        .flatMap { it.vars.entries }
                        .associate { it.key to it.value }
                assertEquals("value1", allVars["KEY1"]?.redactedValue)

                viewModel.updateKey("KEY1", "newValue")

                val updatedState =
                    viewModel.uiState.first { state ->
                        state.toastMessage == "Key updated successfully"
                    }
                assertEquals("Key updated successfully", updatedState.toastMessage)
                coVerify { mockApi.updateEnvVar(EnvVarUpdate("KEY1", "newValue")) }
            } finally {
                unmockkObject(ApiClient)
            }
        }
}
