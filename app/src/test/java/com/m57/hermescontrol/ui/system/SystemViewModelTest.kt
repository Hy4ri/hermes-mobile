package com.m57.hermescontrol.ui.system

import com.m57.hermescontrol.data.local.AuthManager
import com.m57.hermescontrol.data.ws.HermesWsClient
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SystemViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns testDispatcher
        every { Dispatchers.Default } returns testDispatcher
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkObject(AuthManager)
        every { AuthManager.isAutoReconnect() } returns false

        HermesWsClient.disconnect()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
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

    @Test
    fun `loadBattery when disconnected sets battery state to unavailable`() =
        runTest {
            val viewModel = SystemViewModel()
            viewModel.loadBattery()
            advanceUntilIdle()

            val battery = viewModel.uiState.value.battery
            assertNotNull(battery)
            assertFalse(battery!!.available)
        }
}
