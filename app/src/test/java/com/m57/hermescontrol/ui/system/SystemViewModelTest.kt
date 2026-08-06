package com.m57.hermescontrol.ui.system

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SystemViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `openUpdateConfirm sets updateConfirmOpen to true`() {
        val viewModel = SystemViewModel()

        assertFalse(viewModel.uiState.value.updateConfirmOpen)

        viewModel.openUpdateConfirm()

        assertTrue(viewModel.uiState.value.updateConfirmOpen)
    }

    @Test
    fun `closeUpdateConfirm sets updateConfirmOpen to false`() {
        val viewModel = SystemViewModel()

        // First open it
        viewModel.openUpdateConfirm()
        assertTrue(viewModel.uiState.value.updateConfirmOpen)

        // Then close it
        viewModel.closeUpdateConfirm()
        assertFalse(viewModel.uiState.value.updateConfirmOpen)
    }
}
