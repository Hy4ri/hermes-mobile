package com.m57.hermescontrol.ui.pairing

import android.app.Application
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PairingCodeEntryViewModelTest {
    private val mockApp: Application = mockk(relaxed = true)

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun testOnManualCodeChange_updatesStateAndClearsError() {
        val viewModel = PairingCodeEntryViewModel(mockApp)

        // Force an error to verify it gets cleared
        // using onCodeDetected with invalid input to set the error
        viewModel.onCodeDetected("invalid-code")
        assertEquals(true, viewModel.uiState.value.errorMessage != null)

        viewModel.onManualCodeChange("new-code")

        val state = viewModel.uiState.value
        assertEquals("new-code", state.manualCode)
        assertNull(state.errorMessage)
    }

    @Test
    fun testOnManualCodeChange_trimsInput() {
        val viewModel = PairingCodeEntryViewModel(mockApp)

        viewModel.onManualCodeChange("  trimmed-code  \n")

        val state = viewModel.uiState.value
        assertEquals("trimmed-code", state.manualCode)
    }
}
