package com.m57.hermescontrol.ui.keys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

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
}
