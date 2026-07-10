package com.m57.hermescontrol.ui.system

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
    private lateinit var viewModel: SystemViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `openUpdateConfirm sets updateConfirmOpen to true`() {
        viewModel = SystemViewModel()

        assertFalse(viewModel.uiState.value.updateConfirmOpen)

        viewModel.openUpdateConfirm()

        assertTrue(viewModel.uiState.value.updateConfirmOpen)
    }

    @Test
    fun `closeUpdateConfirm sets updateConfirmOpen to false`() {
        viewModel = SystemViewModel()

        // First open it
        viewModel.openUpdateConfirm()
        assertTrue(viewModel.uiState.value.updateConfirmOpen)

        // Then close it
        viewModel.closeUpdateConfirm()
        assertFalse(viewModel.uiState.value.updateConfirmOpen)
    }
}
